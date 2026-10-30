#!/usr/bin/env python3
"""把一个真实上游响应录成 WireMock mapping（状态码 + Content-Type + base64 body + 请求 URL）。

不依赖 Python 版的任何模块：只用标准库发请求，方便在新仓库里独立重录。

用法：
  python tools/fixtures/record.py --case all --out data/src/test/resources/wiremock
  python tools/fixtures/record.py --url <url> --out <...>/<case>.json --charset gbk
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    " (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)
TIMEOUT_SECONDS = 60

# 五家金标准公司
COMPANIES = ("000858", "600600", "601318", "601606", "605499")
# 同业与估值只需两家（对应 Task 12 的两条金标准）
PEER_COMPANIES = ("600600", "605499")
ANNOUNCEMENT_COMPANIES = ("600600", "605499")

# 直连：本机系统代理会拦掉东财接口
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _query(params: dict) -> str:
    """百分号编码：空格写成 %20，WireMock 的 equalTo 才能对上解码后的值。"""
    return urllib.parse.urlencode(params, quote_via=urllib.parse.quote)


def exchange(prefix: str, code: str) -> str:
    return f"{prefix.upper()}{code}"


def sina_url(prefix: str, code: str, source: str) -> str:
    return "https://quotes.sina.cn/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022?" + _query(
        {
            "paperCode": f"{prefix.lower()}{code}",
            "source": source,
            "type": "0",
            "page": "1",
            "num": "1000",
        }
    )


def datacenter_url(report: str, filter_expr: str, page_size: int, sort_type: str, sort_column: str) -> str:
    return "https://datacenter-web.eastmoney.com/api/data/v1/get?" + _query(
        {
            "reportName": report,
            "columns": "ALL",
            "quoteColumns": "",
            "filter": filter_expr,
            "pageNumber": "1",
            "pageSize": str(page_size),
            "sortTypes": sort_type,
            "sortColumns": sort_column,
            "source": "WEB",
            "client": "WEB",
        }
    )


def simple_cases() -> list[tuple[str, str, str]]:
    cases: list[tuple[str, str, str]] = []
    for prefix, code in (("sh", c) if c.startswith("6") else ("sz", c) for c in COMPANIES):
        for source in ("fzb", "lrb", "llb"):
            cases.append(
                (f"sina/finance-{code}-{source}", sina_url(prefix, code, source), "utf-8")
            )
        cases.append(
            (
                f"eastmoney/f10-profile-{code}",
                "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax?"
                + _query({"code": exchange(prefix, code)}),
                "utf-8",
            )
        )
        cases.append(
            (
                f"eastmoney/f10-segments-{code}",
                "https://emweb.securities.eastmoney.com/PC_HSF10/BusinessAnalysis/PageAjax?"
                + _query({"code": f"{code}.{prefix.upper()}"}),
                "utf-8",
            )
        )
    for code in ANNOUNCEMENT_COMPANIES:
        cases.append(
            (
                f"eastmoney/announcements-{code}",
                "https://np-anotice-stock.eastmoney.com/api/security/ann?"
                + _query(
                    {
                        "sr": "-1",
                        "page_size": "100",
                        "page_index": "1",
                        "ann_type": "A",
                        "client_source": "web",
                        "stock_list": code,
                    }
                ),
                "utf-8",
            )
        )
    cases.append(
        (
            "cninfo/stock-list",
            "http://www.cninfo.com.cn/new/data/szse_stock.json",
            "utf-8",
        )
    )
    cases.append(
        (
            "eastmoney/stock-list-page1",
            "https://82.push2.eastmoney.com/api/qt/clist/get?"
            + _query(
                {
                    "pn": "1",
                    "pz": "100",
                    "po": "1",
                    "np": "1",
                    "fltt": "2",
                    "invt": "2",
                    "fid": "f3",
                    "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
                    "fields": "f12,f14",
                }
            ),
            "utf-8",
        )
    )
    return cases


def fetch(url: str) -> tuple[int, str | None, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
        return response.status, response.headers.get("Content-Type"), response.read()


def to_mapping(url: str, status: int, content_type: str | None, body: bytes, charset: str) -> dict:
    split = urllib.parse.urlsplit(url)
    request: dict = {"method": "GET", "urlPath": split.path}
    query = urllib.parse.parse_qsl(split.query, keep_blank_values=True)
    if query:
        request["queryParameters"] = {key: {"equalTo": value} for key, value in query}
    headers = {"Content-Type": content_type} if content_type else {}
    return {
        "metadata": {
            "url": url,
            "charset": charset,
            "bytes": len(body),
            "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        },
        "request": request,
        "response": {
            "status": status,
            "headers": headers,
            "base64Body": base64.b64encode(body).decode("ascii"),
        },
    }


def record_one(case: str, url: str, charset: str, out_root: Path, failures: list[str]) -> str | None:
    try:
        status, content_type, body = fetch(url)
    except Exception as error:  # noqa: BLE001 - 录制失败要逐条报告，不能中断整批
        failures.append(f"{case}: {type(error).__name__}: {error}")
        return None
    if status < 200 or status >= 300:
        failures.append(f"{case}: HTTP {status}")
        return None
    try:
        text = body.decode(charset)
    except UnicodeDecodeError as error:
        failures.append(f"{case}: 无法按 {charset} 解码: {error}")
        return None
    mapping = to_mapping(url, status, content_type, body, charset)
    target = out_root / f"{case}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"  {case}: HTTP {status}, {len(body)} bytes, {content_type}, charset={charset}")
    return text


def record_peers(code: str, out_root: Path, failures: list[str]) -> None:
    subject_url = datacenter_url(
        "RPT_LICO_FN_CPD", f'(SECURITY_CODE="{code}")', 40, "-1", "REPORTDATE"
    )
    case = f"eastmoney/datacenter-peers-subject-{code}"
    text = record_one(case, subject_url, "utf-8", out_root, failures)
    if text is None:
        return
    rows = (json.loads(text).get("result") or {}).get("data") or []
    if not rows:
        failures.append(f"{case}: 没有同业主体行，无法继续录 cohort")
        return
    board, report_date = rows[0]["BOARD_CODE"], rows[0]["REPORTDATE"]
    cohort_url = datacenter_url(
        "RPT_LICO_FN_CPD",
        f'(BOARD_CODE="{board}")(REPORTDATE=\'{report_date}\')',
        500,
        "-1",
        "TOTAL_OPERATE_INCOME",
    )
    record_one(f"eastmoney/datacenter-peers-{code}", cohort_url, "utf-8", out_root, failures)


def record_valuation(code: str, out_root: Path, failures: list[str]) -> None:
    history_url = datacenter_url(
        "RPT_VALUEANALYSIS_DET", f'(SECURITY_CODE="{code}")', 3000, "-1", "TRADE_DATE"
    )
    case = f"eastmoney/datacenter-valuation-history-{code}"
    text = record_one(case, history_url, "utf-8", out_root, failures)
    if text is None:
        return
    rows = (json.loads(text).get("result") or {}).get("data") or []
    if not rows:
        failures.append(f"{case}: 没有估值行，无法继续录同业估值")
        return
    board = rows[0]["BOARD_CODE"]
    trade_date = str(rows[0]["TRADE_DATE"])[:10]
    peers_url = datacenter_url(
        "RPT_VALUEANALYSIS_DET",
        f'(BOARD_CODE="{board}")(TRADE_DATE=\'{trade_date}\')',
        500,
        "-1",
        "TOTAL_MARKET_CAP",
    )
    record_one(f"eastmoney/datacenter-valuation-peers-{code}", peers_url, "utf-8", out_root, failures)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="单条模式：要录制的完整 URL")
    parser.add_argument("--out", required=True, help="单条模式为文件路径，--case 模式为目录")
    parser.add_argument("--charset", default="utf-8", choices=("gbk", "utf-8"))
    parser.add_argument("--case", help="'all' 或 simple_cases() 中的 case 名")
    args = parser.parse_args()

    out = Path(args.out)
    failures: list[str] = []

    if args.url:
        target = out
        if target.suffix != ".json":
            target = target / "recorded.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary_root = target.parent
        case = target.stem
        text = record_one(case, args.url, args.charset, temporary_root, failures)
        if text is not None:
            recorded = temporary_root / f"{case}.json"
            if recorded != target:
                recorded.replace(target)
    else:
        cases = simple_cases()
        selected = cases if not args.case or args.case == "all" else [c for c in cases if c[0] == args.case]
        if args.case and args.case != "all" and not selected:
            print(f"unknown case: {args.case}", file=sys.stderr)
            return 2
        for case, url, charset in selected:
            record_one(case, url, charset, out, failures)
        if args.case == "all" or not args.case:
            for code in PEER_COMPANIES:
                record_peers(code, out, failures)
            for code in PEER_COMPANIES:
                record_valuation(code, out, failures)

    if failures:
        print(f"\n{len(failures)} case(s) failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print("\nall cases recorded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
