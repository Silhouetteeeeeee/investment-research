package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("val_history")
public class ValHistoryEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String tradeDate;
    private String name;
    private Double peTtm;
    private Double pbMrq;
    private Double totalMv;
    private Double close;
    private String boardCode;
    private String boardName;
    private String fetchedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getPeTtm() {
        return peTtm;
    }

    public void setPeTtm(Double peTtm) {
        this.peTtm = peTtm;
    }

    public Double getPbMrq() {
        return pbMrq;
    }

    public void setPbMrq(Double pbMrq) {
        this.pbMrq = pbMrq;
    }

    public Double getTotalMv() {
        return totalMv;
    }

    public void setTotalMv(Double totalMv) {
        this.totalMv = totalMv;
    }

    public Double getClose() {
        return close;
    }

    public void setClose(Double close) {
        this.close = close;
    }

    public String getBoardCode() {
        return boardCode;
    }

    public void setBoardCode(String boardCode) {
        this.boardCode = boardCode;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public String getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
