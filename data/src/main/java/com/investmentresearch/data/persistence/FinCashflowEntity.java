package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("fin_cashflow")
public class FinCashflowEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String reportDate;
    private Double cashFromSales;
    private Double taxRefund;
    private Double operatingCashInflow;
    private Double cashPaidForGoods;
    private Double cashPaidToEmployees;
    private Double taxesPaid;
    private Double operatingCashOutflow;
    private Double operatingCashFlow;
    private Double investmentCashInflow;
    private Double capex;
    private Double investmentCashOutflow;
    private Double investmentCashFlow;
    private Double financingCashInflow;
    private Double financingCashOutflow;
    private Double financingCashFlow;
    private Double fxEffect;
    private Double netCashIncrease;
    private Double beginningCash;
    private Double endingCash;
    private String annDate;
    private String currency;
    private String audited;
    private String statementType;
    private String source;
    private String fetchedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReportDate() {
        return reportDate;
    }

    public void setReportDate(String reportDate) {
        this.reportDate = reportDate;
    }

    public Double getCashFromSales() {
        return cashFromSales;
    }

    public void setCashFromSales(Double cashFromSales) {
        this.cashFromSales = cashFromSales;
    }

    public Double getTaxRefund() {
        return taxRefund;
    }

    public void setTaxRefund(Double taxRefund) {
        this.taxRefund = taxRefund;
    }

    public Double getOperatingCashInflow() {
        return operatingCashInflow;
    }

    public void setOperatingCashInflow(Double operatingCashInflow) {
        this.operatingCashInflow = operatingCashInflow;
    }

    public Double getCashPaidForGoods() {
        return cashPaidForGoods;
    }

    public void setCashPaidForGoods(Double cashPaidForGoods) {
        this.cashPaidForGoods = cashPaidForGoods;
    }

    public Double getCashPaidToEmployees() {
        return cashPaidToEmployees;
    }

    public void setCashPaidToEmployees(Double cashPaidToEmployees) {
        this.cashPaidToEmployees = cashPaidToEmployees;
    }

    public Double getTaxesPaid() {
        return taxesPaid;
    }

    public void setTaxesPaid(Double taxesPaid) {
        this.taxesPaid = taxesPaid;
    }

    public Double getOperatingCashOutflow() {
        return operatingCashOutflow;
    }

    public void setOperatingCashOutflow(Double operatingCashOutflow) {
        this.operatingCashOutflow = operatingCashOutflow;
    }

    public Double getOperatingCashFlow() {
        return operatingCashFlow;
    }

    public void setOperatingCashFlow(Double operatingCashFlow) {
        this.operatingCashFlow = operatingCashFlow;
    }

    public Double getInvestmentCashInflow() {
        return investmentCashInflow;
    }

    public void setInvestmentCashInflow(Double investmentCashInflow) {
        this.investmentCashInflow = investmentCashInflow;
    }

    public Double getCapex() {
        return capex;
    }

    public void setCapex(Double capex) {
        this.capex = capex;
    }

    public Double getInvestmentCashOutflow() {
        return investmentCashOutflow;
    }

    public void setInvestmentCashOutflow(Double investmentCashOutflow) {
        this.investmentCashOutflow = investmentCashOutflow;
    }

    public Double getInvestmentCashFlow() {
        return investmentCashFlow;
    }

    public void setInvestmentCashFlow(Double investmentCashFlow) {
        this.investmentCashFlow = investmentCashFlow;
    }

    public Double getFinancingCashInflow() {
        return financingCashInflow;
    }

    public void setFinancingCashInflow(Double financingCashInflow) {
        this.financingCashInflow = financingCashInflow;
    }

    public Double getFinancingCashOutflow() {
        return financingCashOutflow;
    }

    public void setFinancingCashOutflow(Double financingCashOutflow) {
        this.financingCashOutflow = financingCashOutflow;
    }

    public Double getFinancingCashFlow() {
        return financingCashFlow;
    }

    public void setFinancingCashFlow(Double financingCashFlow) {
        this.financingCashFlow = financingCashFlow;
    }

    public Double getFxEffect() {
        return fxEffect;
    }

    public void setFxEffect(Double fxEffect) {
        this.fxEffect = fxEffect;
    }

    public Double getNetCashIncrease() {
        return netCashIncrease;
    }

    public void setNetCashIncrease(Double netCashIncrease) {
        this.netCashIncrease = netCashIncrease;
    }

    public Double getBeginningCash() {
        return beginningCash;
    }

    public void setBeginningCash(Double beginningCash) {
        this.beginningCash = beginningCash;
    }

    public Double getEndingCash() {
        return endingCash;
    }

    public void setEndingCash(Double endingCash) {
        this.endingCash = endingCash;
    }

    public String getAnnDate() {
        return annDate;
    }

    public void setAnnDate(String annDate) {
        this.annDate = annDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getAudited() {
        return audited;
    }

    public void setAudited(String audited) {
        this.audited = audited;
    }

    public String getStatementType() {
        return statementType;
    }

    public void setStatementType(String statementType) {
        this.statementType = statementType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
