package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("fin_balance")
public class FinBalanceEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String reportDate;
    private Double cash;
    private Double tradingFinAssets;
    private Double notesReceivable;
    private Double accountsReceivable;
    private Double receivablesFinancing;
    private Double prepayments;
    private Double otherReceivables;
    private Double inventory;
    private Double currentAssetsTotal;
    private Double longTermEquityInvest;
    private Double investmentProperty;
    private Double fixedAssets;
    private Double constructionInProgress;
    private Double rightOfUseAssets;
    private Double intangibleAssets;
    private Double developmentExpenditure;
    private Double goodwill;
    private Double deferredTaxAssets;
    private Double otherNonCurrentAssets;
    private Double nonCurrentAssetsTotal;
    private Double totalAssets;
    private Double shortTermBorrowings;
    private Double notesPayable;
    private Double accountsPayable;
    private Double advancesFromCustomers;
    private Double contractLiabilities;
    private Double employeeBenefitsPayable;
    private Double taxesPayable;
    private Double otherPayables;
    private Double currentLiabilitiesTotal;
    private Double longTermBorrowings;
    private Double bondsPayable;
    private Double leaseLiabilities;
    private Double currentPortionLtDebt;
    private Double nonCurrentLiabilitiesTotal;
    private Double totalLiabilities;
    private Double paidInCapital;
    private Double capitalReserve;
    private Double treasuryStock;
    private Double surplusReserve;
    private Double retainedEarnings;
    private Double equityParent;
    private Double minorityInterest;
    private Double totalEquity;
    private Double totalLiabilitiesAndEquity;
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

    public Double getCash() {
        return cash;
    }

    public void setCash(Double cash) {
        this.cash = cash;
    }

    public Double getTradingFinAssets() {
        return tradingFinAssets;
    }

    public void setTradingFinAssets(Double tradingFinAssets) {
        this.tradingFinAssets = tradingFinAssets;
    }

    public Double getNotesReceivable() {
        return notesReceivable;
    }

    public void setNotesReceivable(Double notesReceivable) {
        this.notesReceivable = notesReceivable;
    }

    public Double getAccountsReceivable() {
        return accountsReceivable;
    }

    public void setAccountsReceivable(Double accountsReceivable) {
        this.accountsReceivable = accountsReceivable;
    }

    public Double getReceivablesFinancing() {
        return receivablesFinancing;
    }

    public void setReceivablesFinancing(Double receivablesFinancing) {
        this.receivablesFinancing = receivablesFinancing;
    }

    public Double getPrepayments() {
        return prepayments;
    }

    public void setPrepayments(Double prepayments) {
        this.prepayments = prepayments;
    }

    public Double getOtherReceivables() {
        return otherReceivables;
    }

    public void setOtherReceivables(Double otherReceivables) {
        this.otherReceivables = otherReceivables;
    }

    public Double getInventory() {
        return inventory;
    }

    public void setInventory(Double inventory) {
        this.inventory = inventory;
    }

    public Double getCurrentAssetsTotal() {
        return currentAssetsTotal;
    }

    public void setCurrentAssetsTotal(Double currentAssetsTotal) {
        this.currentAssetsTotal = currentAssetsTotal;
    }

    public Double getLongTermEquityInvest() {
        return longTermEquityInvest;
    }

    public void setLongTermEquityInvest(Double longTermEquityInvest) {
        this.longTermEquityInvest = longTermEquityInvest;
    }

    public Double getInvestmentProperty() {
        return investmentProperty;
    }

    public void setInvestmentProperty(Double investmentProperty) {
        this.investmentProperty = investmentProperty;
    }

    public Double getFixedAssets() {
        return fixedAssets;
    }

    public void setFixedAssets(Double fixedAssets) {
        this.fixedAssets = fixedAssets;
    }

    public Double getConstructionInProgress() {
        return constructionInProgress;
    }

    public void setConstructionInProgress(Double constructionInProgress) {
        this.constructionInProgress = constructionInProgress;
    }

    public Double getRightOfUseAssets() {
        return rightOfUseAssets;
    }

    public void setRightOfUseAssets(Double rightOfUseAssets) {
        this.rightOfUseAssets = rightOfUseAssets;
    }

    public Double getIntangibleAssets() {
        return intangibleAssets;
    }

    public void setIntangibleAssets(Double intangibleAssets) {
        this.intangibleAssets = intangibleAssets;
    }

    public Double getDevelopmentExpenditure() {
        return developmentExpenditure;
    }

    public void setDevelopmentExpenditure(Double developmentExpenditure) {
        this.developmentExpenditure = developmentExpenditure;
    }

    public Double getGoodwill() {
        return goodwill;
    }

    public void setGoodwill(Double goodwill) {
        this.goodwill = goodwill;
    }

    public Double getDeferredTaxAssets() {
        return deferredTaxAssets;
    }

    public void setDeferredTaxAssets(Double deferredTaxAssets) {
        this.deferredTaxAssets = deferredTaxAssets;
    }

    public Double getOtherNonCurrentAssets() {
        return otherNonCurrentAssets;
    }

    public void setOtherNonCurrentAssets(Double otherNonCurrentAssets) {
        this.otherNonCurrentAssets = otherNonCurrentAssets;
    }

    public Double getNonCurrentAssetsTotal() {
        return nonCurrentAssetsTotal;
    }

    public void setNonCurrentAssetsTotal(Double nonCurrentAssetsTotal) {
        this.nonCurrentAssetsTotal = nonCurrentAssetsTotal;
    }

    public Double getTotalAssets() {
        return totalAssets;
    }

    public void setTotalAssets(Double totalAssets) {
        this.totalAssets = totalAssets;
    }

    public Double getShortTermBorrowings() {
        return shortTermBorrowings;
    }

    public void setShortTermBorrowings(Double shortTermBorrowings) {
        this.shortTermBorrowings = shortTermBorrowings;
    }

    public Double getNotesPayable() {
        return notesPayable;
    }

    public void setNotesPayable(Double notesPayable) {
        this.notesPayable = notesPayable;
    }

    public Double getAccountsPayable() {
        return accountsPayable;
    }

    public void setAccountsPayable(Double accountsPayable) {
        this.accountsPayable = accountsPayable;
    }

    public Double getAdvancesFromCustomers() {
        return advancesFromCustomers;
    }

    public void setAdvancesFromCustomers(Double advancesFromCustomers) {
        this.advancesFromCustomers = advancesFromCustomers;
    }

    public Double getContractLiabilities() {
        return contractLiabilities;
    }

    public void setContractLiabilities(Double contractLiabilities) {
        this.contractLiabilities = contractLiabilities;
    }

    public Double getEmployeeBenefitsPayable() {
        return employeeBenefitsPayable;
    }

    public void setEmployeeBenefitsPayable(Double employeeBenefitsPayable) {
        this.employeeBenefitsPayable = employeeBenefitsPayable;
    }

    public Double getTaxesPayable() {
        return taxesPayable;
    }

    public void setTaxesPayable(Double taxesPayable) {
        this.taxesPayable = taxesPayable;
    }

    public Double getOtherPayables() {
        return otherPayables;
    }

    public void setOtherPayables(Double otherPayables) {
        this.otherPayables = otherPayables;
    }

    public Double getCurrentLiabilitiesTotal() {
        return currentLiabilitiesTotal;
    }

    public void setCurrentLiabilitiesTotal(Double currentLiabilitiesTotal) {
        this.currentLiabilitiesTotal = currentLiabilitiesTotal;
    }

    public Double getLongTermBorrowings() {
        return longTermBorrowings;
    }

    public void setLongTermBorrowings(Double longTermBorrowings) {
        this.longTermBorrowings = longTermBorrowings;
    }

    public Double getBondsPayable() {
        return bondsPayable;
    }

    public void setBondsPayable(Double bondsPayable) {
        this.bondsPayable = bondsPayable;
    }

    public Double getLeaseLiabilities() {
        return leaseLiabilities;
    }

    public void setLeaseLiabilities(Double leaseLiabilities) {
        this.leaseLiabilities = leaseLiabilities;
    }

    public Double getCurrentPortionLtDebt() {
        return currentPortionLtDebt;
    }

    public void setCurrentPortionLtDebt(Double currentPortionLtDebt) {
        this.currentPortionLtDebt = currentPortionLtDebt;
    }

    public Double getNonCurrentLiabilitiesTotal() {
        return nonCurrentLiabilitiesTotal;
    }

    public void setNonCurrentLiabilitiesTotal(Double nonCurrentLiabilitiesTotal) {
        this.nonCurrentLiabilitiesTotal = nonCurrentLiabilitiesTotal;
    }

    public Double getTotalLiabilities() {
        return totalLiabilities;
    }

    public void setTotalLiabilities(Double totalLiabilities) {
        this.totalLiabilities = totalLiabilities;
    }

    public Double getPaidInCapital() {
        return paidInCapital;
    }

    public void setPaidInCapital(Double paidInCapital) {
        this.paidInCapital = paidInCapital;
    }

    public Double getCapitalReserve() {
        return capitalReserve;
    }

    public void setCapitalReserve(Double capitalReserve) {
        this.capitalReserve = capitalReserve;
    }

    public Double getTreasuryStock() {
        return treasuryStock;
    }

    public void setTreasuryStock(Double treasuryStock) {
        this.treasuryStock = treasuryStock;
    }

    public Double getSurplusReserve() {
        return surplusReserve;
    }

    public void setSurplusReserve(Double surplusReserve) {
        this.surplusReserve = surplusReserve;
    }

    public Double getRetainedEarnings() {
        return retainedEarnings;
    }

    public void setRetainedEarnings(Double retainedEarnings) {
        this.retainedEarnings = retainedEarnings;
    }

    public Double getEquityParent() {
        return equityParent;
    }

    public void setEquityParent(Double equityParent) {
        this.equityParent = equityParent;
    }

    public Double getMinorityInterest() {
        return minorityInterest;
    }

    public void setMinorityInterest(Double minorityInterest) {
        this.minorityInterest = minorityInterest;
    }

    public Double getTotalEquity() {
        return totalEquity;
    }

    public void setTotalEquity(Double totalEquity) {
        this.totalEquity = totalEquity;
    }

    public Double getTotalLiabilitiesAndEquity() {
        return totalLiabilitiesAndEquity;
    }

    public void setTotalLiabilitiesAndEquity(Double totalLiabilitiesAndEquity) {
        this.totalLiabilitiesAndEquity = totalLiabilitiesAndEquity;
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
