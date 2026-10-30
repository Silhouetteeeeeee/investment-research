package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("fin_income")
public class FinIncomeEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String reportDate;
    private Double totalRevenue;
    private Double revenue;
    private Double totalCost;
    private Double costOfRevenue;
    private Double taxesAndSurcharges;
    private Double rdExpense;
    private Double sellingExpense;
    private Double adminExpense;
    private Double financeExpense;
    private Double interestExpense;
    private Double investmentIncome;
    private Double fairValueChange;
    private Double otherIncome;
    private Double assetImpairment;
    private Double creditImpairment;
    private Double assetDisposalIncome;
    private Double operatingProfit;
    private Double nonOperatingIncome;
    private Double nonOperatingExpense;
    private Double totalProfit;
    private Double incomeTax;
    private Double netProfit;
    private Double netProfitAttributable;
    private Double minorityProfit;
    private Double basicEps;
    private Double dilutedEps;
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

    public Double getTotalRevenue() {
        return totalRevenue;
    }

    public void setTotalRevenue(Double totalRevenue) {
        this.totalRevenue = totalRevenue;
    }

    public Double getRevenue() {
        return revenue;
    }

    public void setRevenue(Double revenue) {
        this.revenue = revenue;
    }

    public Double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(Double totalCost) {
        this.totalCost = totalCost;
    }

    public Double getCostOfRevenue() {
        return costOfRevenue;
    }

    public void setCostOfRevenue(Double costOfRevenue) {
        this.costOfRevenue = costOfRevenue;
    }

    public Double getTaxesAndSurcharges() {
        return taxesAndSurcharges;
    }

    public void setTaxesAndSurcharges(Double taxesAndSurcharges) {
        this.taxesAndSurcharges = taxesAndSurcharges;
    }

    public Double getRdExpense() {
        return rdExpense;
    }

    public void setRdExpense(Double rdExpense) {
        this.rdExpense = rdExpense;
    }

    public Double getSellingExpense() {
        return sellingExpense;
    }

    public void setSellingExpense(Double sellingExpense) {
        this.sellingExpense = sellingExpense;
    }

    public Double getAdminExpense() {
        return adminExpense;
    }

    public void setAdminExpense(Double adminExpense) {
        this.adminExpense = adminExpense;
    }

    public Double getFinanceExpense() {
        return financeExpense;
    }

    public void setFinanceExpense(Double financeExpense) {
        this.financeExpense = financeExpense;
    }

    public Double getInterestExpense() {
        return interestExpense;
    }

    public void setInterestExpense(Double interestExpense) {
        this.interestExpense = interestExpense;
    }

    public Double getInvestmentIncome() {
        return investmentIncome;
    }

    public void setInvestmentIncome(Double investmentIncome) {
        this.investmentIncome = investmentIncome;
    }

    public Double getFairValueChange() {
        return fairValueChange;
    }

    public void setFairValueChange(Double fairValueChange) {
        this.fairValueChange = fairValueChange;
    }

    public Double getOtherIncome() {
        return otherIncome;
    }

    public void setOtherIncome(Double otherIncome) {
        this.otherIncome = otherIncome;
    }

    public Double getAssetImpairment() {
        return assetImpairment;
    }

    public void setAssetImpairment(Double assetImpairment) {
        this.assetImpairment = assetImpairment;
    }

    public Double getCreditImpairment() {
        return creditImpairment;
    }

    public void setCreditImpairment(Double creditImpairment) {
        this.creditImpairment = creditImpairment;
    }

    public Double getAssetDisposalIncome() {
        return assetDisposalIncome;
    }

    public void setAssetDisposalIncome(Double assetDisposalIncome) {
        this.assetDisposalIncome = assetDisposalIncome;
    }

    public Double getOperatingProfit() {
        return operatingProfit;
    }

    public void setOperatingProfit(Double operatingProfit) {
        this.operatingProfit = operatingProfit;
    }

    public Double getNonOperatingIncome() {
        return nonOperatingIncome;
    }

    public void setNonOperatingIncome(Double nonOperatingIncome) {
        this.nonOperatingIncome = nonOperatingIncome;
    }

    public Double getNonOperatingExpense() {
        return nonOperatingExpense;
    }

    public void setNonOperatingExpense(Double nonOperatingExpense) {
        this.nonOperatingExpense = nonOperatingExpense;
    }

    public Double getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(Double totalProfit) {
        this.totalProfit = totalProfit;
    }

    public Double getIncomeTax() {
        return incomeTax;
    }

    public void setIncomeTax(Double incomeTax) {
        this.incomeTax = incomeTax;
    }

    public Double getNetProfit() {
        return netProfit;
    }

    public void setNetProfit(Double netProfit) {
        this.netProfit = netProfit;
    }

    public Double getNetProfitAttributable() {
        return netProfitAttributable;
    }

    public void setNetProfitAttributable(Double netProfitAttributable) {
        this.netProfitAttributable = netProfitAttributable;
    }

    public Double getMinorityProfit() {
        return minorityProfit;
    }

    public void setMinorityProfit(Double minorityProfit) {
        this.minorityProfit = minorityProfit;
    }

    public Double getBasicEps() {
        return basicEps;
    }

    public void setBasicEps(Double basicEps) {
        this.basicEps = basicEps;
    }

    public Double getDilutedEps() {
        return dilutedEps;
    }

    public void setDilutedEps(Double dilutedEps) {
        this.dilutedEps = dilutedEps;
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
