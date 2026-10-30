package com.investmentresearch.data.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinDisclosureMapper {
    /** 照抄 {@code stock/disclosure_service.py:latest_disclosed_period}。 */
    @Select(
            "select max(report_period) from fin_disclosure "
                    + "where code = #{code} and actual_date is not null")
    String latestDisclosedPeriod(@Param("code") String code);
}
