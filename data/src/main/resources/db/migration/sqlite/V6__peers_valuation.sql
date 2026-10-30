CREATE TABLE company_peers (
            code text primary key,
            data text,
            fetched_at text
        );
CREATE TABLE val_history (
            code text not null,
            trade_date text not null,
            name text,
            pe_ttm real,
            pb_mrq real,
            total_mv real,
            close real,
            board_code text,
            board_name text,
            fetched_at text,
            primary key (code, trade_date)
        );
CREATE TABLE val_peer (
            board_code text not null,
            trade_date text not null,
            code text not null,
            name text,
            pe_ttm real,
            pb_mrq real,
            total_mv real,
            fetched_at text,
            primary key (board_code, trade_date, code)
        );
