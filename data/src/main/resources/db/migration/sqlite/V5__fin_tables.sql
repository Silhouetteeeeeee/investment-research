CREATE TABLE fin_balance (
            code text not null,
            report_date text not null,
            cash real,
    trading_fin_assets real,
    notes_receivable real,
    accounts_receivable real,
    receivables_financing real,
    prepayments real,
    other_receivables real,
    inventory real,
    current_assets_total real,
    long_term_equity_invest real,
    investment_property real,
    fixed_assets real,
    construction_in_progress real,
    right_of_use_assets real,
    intangible_assets real,
    development_expenditure real,
    goodwill real,
    deferred_tax_assets real,
    other_non_current_assets real,
    non_current_assets_total real,
    total_assets real,
    short_term_borrowings real,
    notes_payable real,
    accounts_payable real,
    advances_from_customers real,
    contract_liabilities real,
    employee_benefits_payable real,
    taxes_payable real,
    other_payables real,
    current_liabilities_total real,
    long_term_borrowings real,
    bonds_payable real,
    lease_liabilities real,
    current_portion_lt_debt real,
    non_current_liabilities_total real,
    total_liabilities real,
    paid_in_capital real,
    capital_reserve real,
    treasury_stock real,
    surplus_reserve real,
    retained_earnings real,
    equity_parent real,
    minority_interest real,
    total_equity real,
    total_liabilities_and_equity real,
            ann_date text,
            currency text,
            audited text,
            statement_type text,
            source text,
            fetched_at text,
            primary key (code, report_date)
        );
CREATE TABLE fin_cashflow (
            code text not null,
            report_date text not null,
            cash_from_sales real,
    tax_refund real,
    operating_cash_inflow real,
    cash_paid_for_goods real,
    cash_paid_to_employees real,
    taxes_paid real,
    operating_cash_outflow real,
    operating_cash_flow real,
    investment_cash_inflow real,
    capex real,
    investment_cash_outflow real,
    investment_cash_flow real,
    financing_cash_inflow real,
    financing_cash_outflow real,
    financing_cash_flow real,
    fx_effect real,
    net_cash_increase real,
    beginning_cash real,
    ending_cash real,
            ann_date text,
            currency text,
            audited text,
            statement_type text,
            source text,
            fetched_at text,
            primary key (code, report_date)
        );
CREATE TABLE fin_income (
            code text not null,
            report_date text not null,
            total_revenue real,
    revenue real,
    total_cost real,
    cost_of_revenue real,
    taxes_and_surcharges real,
    rd_expense real,
    selling_expense real,
    admin_expense real,
    finance_expense real,
    interest_expense real,
    investment_income real,
    fair_value_change real,
    other_income real,
    asset_impairment real,
    credit_impairment real,
    asset_disposal_income real,
    operating_profit real,
    non_operating_income real,
    non_operating_expense real,
    total_profit real,
    income_tax real,
    net_profit real,
    net_profit_attributable real,
    minority_profit real,
    basic_eps real,
    diluted_eps real,
            ann_date text,
            currency text,
            audited text,
            statement_type text,
            source text,
            fetched_at text,
            primary key (code, report_date)
        );
CREATE TABLE fin_metrics (
            code text not null,
            report_date text not null,
            revenue real,
    revenue_yoy real,
    revenue_sq real,
    revenue_sq_yoy real,
    net_profit real,
    net_profit_yoy real,
    net_profit_sq real,
    net_profit_sq_yoy real,
    gross_margin real,
    net_margin real,
    rd_ratio real,
    roe real,
    roa real,
    debt_ratio real,
    current_ratio real,
    quick_ratio real,
    ar_turnover_days real,
    inventory_turnover_days real,
    ocf_to_net_profit real,
    cash_to_revenue real,
    operating_cash_flow real,
    capex real,
    free_cash_flow real,
    net_debt real,
            computed_at text,
            primary key (code, report_date)
        );

