CREATE TABLE announcement (
            code text not null,
            art_code text not null,
            notice_date text not null,
            title text,
            categories text,
            url text,
            fetched_at text,
            primary key (code, art_code)
        )
