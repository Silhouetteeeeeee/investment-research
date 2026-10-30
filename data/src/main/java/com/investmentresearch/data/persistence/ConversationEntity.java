package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String primaryCode;
    private String primaryName;
    private String companies;
    private String usageJson;
    private String createdAt;
    private String updatedAt;

    /** Derived from the message table, not a conversation column. */
    @TableField(exist = false)
    private Integer messageCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPrimaryCode() {
        return primaryCode;
    }

    public void setPrimaryCode(String primaryCode) {
        this.primaryCode = primaryCode;
    }

    public String getPrimaryName() {
        return primaryName;
    }

    public void setPrimaryName(String primaryName) {
        this.primaryName = primaryName;
    }

    public String getCompanies() {
        return companies;
    }

    public void setCompanies(String companies) {
        this.companies = companies;
    }

    public String getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(String usageJson) {
        this.usageJson = usageJson;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }
}
