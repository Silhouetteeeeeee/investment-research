create table if not exists fin_disclosure (
    code text not null,
    name text,
    report_period text not null,
    first_scheduled text,
    change_1 text,
    change_2 text,
    change_3 text,
    actual_date text,
    fetched_at text,
    primary key (code, report_period)
)
