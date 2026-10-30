package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("company_profile")
public class CompanyProfileEntity {
    @TableId(type = IdType.INPUT)
    private String code;

    private String name;
    private String industry;
    private String profile;
    private String scope;
    private Integer employees;
    private String fetchedAt;

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

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Integer getEmployees() {
        return employees;
    }

    public void setEmployees(Integer employees) {
        this.employees = employees;
    }

    public String getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
