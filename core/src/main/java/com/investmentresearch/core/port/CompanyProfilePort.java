package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Company profile data (F10). */
public interface CompanyProfilePort {
    ToolResult companyProfile(String code);
}
