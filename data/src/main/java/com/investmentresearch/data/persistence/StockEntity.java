package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("stock")
public class StockEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String name;
    private String refreshedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRefreshedAt() {
        return refreshedAt;
    }

    public void setRefreshedAt(String refreshedAt) {
        this.refreshedAt = refreshedAt;
    }
}
