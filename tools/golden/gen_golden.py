"""Generate the Phase-1 golden fixtures from the Python research assistant.

Outputs (all deterministic, no network):

* ``<out>/tools/<tool>__<code>__<args-hash>.json`` — 34 tool cases, each carrying
  ``generated_on`` so later tests can pin a Clock
* ``<out>/rows/<table>__<code>.json`` — every snapshot table, per company
* ``<out>/tool-messages.json`` — the exact Python ``ToolResult.to_message()`` string per case
* ``--schemas`` — 33 OpenAI function schemas keyed by tool name
* ``--snapshot`` — SQLite copy holding only the five golden companies' rows

The Python repository is never written: the DB is copied to a temp file and the
tool modules are pointed there, cache freshness is forced to "fresh" so every
tool reads the local cache, and any attempted network call raises immediately.

Run with ``conda run -n stock_ai python -I tools/golden/gen_golden.py ...``.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
from datetime import date, timedelta
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

COMPANIES = (
    ("000858", "五粮液"),
    ("600600", "青岛啤酒"),
    ("601318", "中国平安"),
    ("605499", "东鹏饮料"),
    ("601606", "长城军工"),
)
CODES = tuple(code for code, _ in COMPANIES)

COMPANY_TABLES = (
    "stock",
    "company_profile",
    "company_peers",
    "fin_disclosure",
    "fin_income",
    "fin_balance",
    "fin_cashflow",
    "fin_metrics",
    "biz_segment",
    "announcement",
    "val_history",
)
# val_peer has no company code; copy the boards the five companies belong to.
SNAPSHOT_TABLES = COMPANY_TABLES + ("val_peer",)

COMPANY_TOOLS = (
    ("get_company_profile", {}),
    ("get_finance", {}),
    ("get_segments", {"years": 5}),
    ("get_peers", {"limit": 8}),
    ("get_announcements", {"days": 180}),
    ("get_valuation", {}),
)
RESOLVE_CASES = ("605499", "东鹏饮料", "不存在的公司")


class NetworkBlocked(BaseException):
    """Raised when a golden-generation tool tries to reach the network."""


def install_network_guard() -> None:
    import socket

    def blocked(*_args, **_kwargs):
        raise NetworkBlocked("network access is blocked during golden generation")

    socket.socket.connect = blocked
    socket.socket.connect_ex = blocked
    socket.create_connection = blocked
    socket.getaddrinfo = blocked


def patch_modules(db_path: Path, as_of: date) -> None:
    """Point the tool modules at the temp DB copy, force cache hits, pin today."""
    import assistant.company_profile as company_profile
    import stock.announcements as announcements
    import stock.db_service as db_service
    import stock.disclosure_service as disclosure_service
    import stock.finance_service as finance_service
    import stock.peers as peers
    import stock.segments as segments
    import stock.valuation as valuation

    for module in (
        db_service,
        disclosure_service,
        finance_service,
        segments,
        peers,
        announcements,
        valuation,
        company_profile,
    ):
        module.DB_PATH = str(db_path)

    for module in (segments, peers, announcements, valuation, company_profile):
        module._age_hours = lambda *_args, **_kwargs: 0.0
    finance_service._cache_age = lambda _history: timedelta(0)

    # The tool call path has no `today` parameter, so inject the frozen date at the
    # pure-function boundary instead of faking the clock.
    original = announcements.announcements_from_rows

    def pinned(rows, *args, **kwargs):
        kwargs.setdefault("today", as_of)
        return original(rows, *args, **kwargs)

    announcements.announcements_from_rows = pinned


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    return connection


def columns(connection: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in connection.execute(f'pragma table_info("{table}")')]


def build_snapshot(source: Path, dest: Path) -> dict[str, int]:
    dest.parent.mkdir(parents=True, exist_ok=True)
    for suffix in ("", "-wal", "-shm"):
        stale = Path(str(dest) + suffix)
        if stale.exists():
            stale.unlink()

    src = connect(source)
    dst = sqlite3.connect(dest)
    try:
        ddl = {
            table: src.execute(
                "select sql from sqlite_master where type='table' and name=?", (table,)
            ).fetchone()[0]
            for table in SNAPSHOT_TABLES
        }
        for table in SNAPSHOT_TABLES:
            dst.execute(ddl[table])

        marks = ",".join("?" for _ in CODES)
        boards = [
            row[0]
            for row in src.execute(
                f"select distinct board_code from val_history where code in ({marks})",
                CODES,
            )
        ]
        board_marks = ",".join("?" for _ in boards)

        counts: dict[str, int] = {}
        for table in SNAPSHOT_TABLES:
            if table == "val_peer":
                where = f'board_code in ({board_marks})'
                params: tuple = tuple(boards)
            else:
                where = f'code in ({marks})'
                params = CODES
            rows = src.execute(
                f'select * from "{table}" where {where} order by rowid', params
            ).fetchall()
            table_columns = columns(src, table)
            dst.executemany(
                'insert into "{}" ({}) values ({})'.format(
                    table,
                    ", ".join(f'"{column}"' for column in table_columns),
                    ", ".join("?" for _ in table_columns),
                ),
                [tuple(row[column] for column in table_columns) for row in rows],
            )
            counts[table] = len(rows)
        dst.commit()

        assert counts["stock"] == len(CODES), counts
        for table in COMPANY_TABLES:
            found = {
                row[0]
                for row in dst.execute(f'select distinct code from "{table}"')
            }
            assert found <= set(CODES), (table, found)
        peer_boards = {
            row[0] for row in dst.execute("select distinct board_code from val_peer")
        }
        assert peer_boards <= set(boards), (peer_boards, boards)
        return counts
    finally:
        src.close()
        dst.close()


def rows_for(connection: sqlite3.Connection, table: str, code: str) -> list[dict]:
    table_columns = columns(connection, table)
    if table == "val_peer":
        marks = ",".join("?" for _ in CODES)
        boards = [
            row[0]
            for row in connection.execute(
                f"select distinct board_code from val_history where code in ({marks})",
                CODES,
            )
        ]
        board_code = connection.execute(
            "select board_code from val_history where code = ? order by board_code limit 1",
            (code,),
        ).fetchone()[0]
        assert board_code in boards
        params: tuple = (board_code,)
        where = "board_code = ?"
    else:
        params = (code,)
        where = "code = ?"
    rows = connection.execute(
        f'select * from "{table}" where {where} order by rowid', params
    ).fetchall()
    return [{column: row[column] for column in table_columns} for row in rows]


def build_cases() -> list[tuple[str, dict, str | None, bool]]:
    """(tool, arguments, default_company, expect_ok)."""
    cases: list[tuple[str, dict, str | None, bool]] = []
    for code, _name in COMPANIES:
        for tool, arguments in COMPANY_TOOLS:
            cases.append((tool, arguments, code, True))
    for name_or_code in RESOLVE_CASES:
        cases.append(
            (
                "resolve_company",
                {"name_or_code": name_or_code},
                None,
                name_or_code != "不存在的公司",
            )
        )
    cases.append(("get_segments", {"years": 3}, "000858", True))
    return cases


def safe(text: str) -> str:
    return "".join("_" if character in '\\/:*?"<>|' else character for character in text)


def args_hash(arguments: dict) -> str:
    canonical = json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:8]


def write_json(path: Path, value: object) -> None:
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def clear_json(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for item in directory.glob("*.json"):
        item.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-repo", default="D:/Programming/PythonProject")
    parser.add_argument("--out", default=str(REPO_ROOT / "core/src/test/resources/golden"))
    parser.add_argument(
        "--snapshot",
        default=str(REPO_ROOT / "core/src/test/resources/fixtures/stock-snapshot.db"),
    )
    parser.add_argument(
        "--schemas", default=str(REPO_ROOT / "core/src/main/resources/tool-schemas.json")
    )
    parser.add_argument(
        "--as-of",
        default=None,
        help="frozen date (YYYY-MM-DD) for date-relative tools; defaults to today",
    )
    options = parser.parse_args()

    python_repo = Path(options.python_repo).resolve()
    out = Path(options.out).resolve()
    snapshot = Path(options.snapshot).resolve()
    schemas_path = Path(options.schemas).resolve()
    as_of = date.fromisoformat(options.as_of) if options.as_of else date.today()
    source_db = python_repo / "data" / "stock.db"
    if not source_db.is_file():
        raise SystemExit(f"missing Python DB: {source_db}")

    sys.dont_write_bytecode = True
    install_network_guard()
    sys.path.insert(0, str(python_repo))

    workdir = Path(tempfile.mkdtemp(prefix="stock-golden-"))
    work_db = workdir / "stock.db"
    shutil.copy2(source_db, work_db)
    try:
        patch_modules(work_db, as_of)

        import assistant.tools as tools
        from assistant.models import ToolResult

        counts = build_snapshot(work_db, snapshot)

        tools_dir = out / "tools"
        rows_dir = out / "rows"
        clear_json(tools_dir)
        clear_json(rows_dir)

        connection = connect(work_db)
        try:
            for table in SNAPSHOT_TABLES:
                for code, _name in COMPANIES:
                    write_json(rows_dir / f"{table}__{code}.json", rows_for(connection, table, code))
        finally:
            connection.close()

        messages: dict[str, str] = {}
        for tool, arguments, default_company, expect_ok in build_cases():
            try:
                result = tools.run_tool(tool, arguments, default_company)
            except BaseException as error:  # noqa: BLE001 - includes NetworkBlocked
                raise SystemExit(
                    f"{tool} {arguments} (default_company={default_company}) failed: "
                    f"{type(error).__name__}: {error}"
                ) from error
            if not isinstance(result, ToolResult):
                raise SystemExit(f"{tool} returned {type(result).__name__}, not ToolResult")
            if result.ok != expect_ok:
                raise SystemExit(
                    f"{tool} {arguments} (default_company={default_company}) "
                    f"unexpected ok={result.ok}: {result.error}"
                )
            label = default_company if default_company else str(arguments["name_or_code"])
            filename = f"{tool}__{safe(label)}__{args_hash(arguments)}.json"
            write_json(
                tools_dir / filename,
                {
                    "tool": tool,
                    "arguments": arguments,
                    "default_company": default_company,
                    "generated_on": as_of.isoformat(),
                    "result": dataclasses.asdict(result),
                },
            )
            messages[filename] = result.to_message()

        write_json(out / "tool-messages.json", messages)

        exported = tools.schemas()
        by_name = {item["function"]["name"]: item["function"] for item in exported}
        if len(by_name) != 33:
            raise SystemExit(f"expected 33 tool schemas, got {len(by_name)}")
        schemas_path.parent.mkdir(parents=True, exist_ok=True)
        write_json(schemas_path, by_name)

        print(
            f"tools={len(messages)} schemas={len(by_name)} "
            f"rows={len(SNAPSHOT_TABLES) * len(COMPANIES)} snapshot_rows={sum(counts.values())}"
        )
        for table, count in counts.items():
            print(f"  {table}: {count}")
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
