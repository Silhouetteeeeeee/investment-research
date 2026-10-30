package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("fin_metrics")
public class FinMetricsEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String reportDate;
    private Double revenue;
    private Double revenueYoy;
    private Double revenueSq;
    private Double revenueSqYoy;
    private Double netProfit;
    private Double netProfitYoy;
    private Double netProfitSq;
    private Double netProfitSqYoy;
    private Double grossMargin;
    private Double netMargin;
    private Double rdRatio;
    private Double roe;
    private Double roa;
    private Double debtRatio;
    private Double currentRatio;
    private Double quickRatio;
    private Double arTurnoverDays;
    private Double inventoryTurnoverDays;
    private Double ocfToNetProfit;
    private Double cashToRevenue;
    private Double operatingCashFlow;
    private Double capex;
    private Double freeCashFlow;
    private Double netDebt;
    private String computedAt;

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

    public Double getRevenue() {
        return revenue;
    }

    public void setRevenue(Double revenue) {
        this.revenue = revenue;
    }

    public Double getRevenueYoy() {
        return revenueYoy;
    }

    public void setRevenueYoy(Double revenueYoy) {
        this.revenueYoy = revenueYoy;
    }

    public Double getRevenueSq() {
        return revenueSq;
    }

    public void setRevenueSq(Double revenueSq) {
        this.revenueSq = revenueSq;
    }

    public Double getRevenueSqYoy() {
        return revenueSqYoy;
    }

    public void setRevenueSqYoy(Double revenueSqYoy) {
        this.revenueSqYoy = revenueSqYoy;
    }

    public Double getNetProfit() {
        return netProfit;
    }

    public void setNetProfit(Double netProfit) {
        this.netProfit = netProfit;
    }

    public Double getNetProfitYoy() {
        return netProfitYoy;
    }

    public void setNetProfitYoy(Double netProfitYoy) {
        this.netProfitYoy = netProfitYoy;
    }

    public Double getNetProfitSq() {
        return netProfitSq;
    }

    public void setNetProfitSq(Double netProfitSq) {
        this.netProfitSq = netProfitSq;
    }

    public Double getNetProfitSqYoy() {
        return netProfitSqYoy;
    }

    public void setNetProfitSqYoy(Double netProfitSqYoy) {
        this.netProfitSqYoy = netProfitSqYoy;
    }

    public Double getGrossMargin() {
        return grossMargin;
    }

    public void setGrossMargin(Double grossMargin) {
        this.grossMargin = grossMargin;
    }

    public Double getNetMargin() {
        return netMargin;
    }

    public void setNetMargin(Double netMargin) {
        this.netMargin = netMargin;
    }

    public Double getRdRatio() {
        return rdRatio;
    }

    public void setRdRatio(Double rdRatio) {
        this.rdRatio = rdRatio;
    }

    public Double getRoe() {
        return roe;
    }

    public void setRoe(Double roe) {
        this.roe = roe;
    }

    public Double getRoa() {
        return roa;
    }

    public void setRoa(Double roa) {
        this.roa = roa;
    }

    public Double getDebtRatio() {
        return debtRatio;
    }

    public void setDebtRatio(Double debtRatio) {
        this.debtRatio = debtRatio;
    }

    public Double getCurrentRatio() {
        return currentRatio;
    }

    public void setCurrentRatio(Double currentRatio) {
        this.currentRatio = currentRatio;
    }

    public Double getQuickRatio() {
        return quickRatio;
    }

    public void setQuickRatio(Double quickRatio) {
        this.quickRatio = quickRatio;
    }

    public Double getArTurnoverDays() {
        return arTurnoverDays;
    }

    public void setArTurnoverDays(Double arTurnoverDays) {
        this.arTurnoverDays = arTurnoverDays;
    }

    public Double getInventoryTurnoverDays() {
        return inventoryTurnoverDays;
    }

    public void setInventoryTurnoverDays(Double inventoryTurnoverDays) {
        this.inventoryTurnoverDays = inventoryTurnoverDays;
    }

    public Double getOcfToNetProfit() {
        return ocfToNetProfit;
    }

    public void setOcfToNetProfit(Double ocfToNetProfit) {
        this.ocfToNetProfit = ocfToNetProfit;
    }

    public Double getCashToRevenue() {
        return cashToRevenue;
    }

    public void setCashToRevenue(Double cashToRevenue) {
        this.cashToRevenue = cashToRevenue;
    }

    public Double getOperatingCashFlow() {
        return operatingCashFlow;
    }

    public void setOperatingCashFlow(Double operatingCashFlow) {
        this.operatingCashFlow = operatingCashFlow;
    }

    public Double getCapex() {
        return capex;
    }

    public void setCapex(Double capex) {
        this.capex = capex;
    }

    public Double getFreeCashFlow() {
        return freeCashFlow;
    }

    public void setFreeCashFlow(Double freeCashFlow) {
        this.freeCashFlow = freeCashFlow;
    }

    public Double getNetDebt() {
        return netDebt;
    }

    public void setNetDebt(Double netDebt) {
        this.netDebt = netDebt;
    }

    public String getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(String computedAt) {
        this.computedAt = computedAt;
    }
}
