package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StockMapper extends BaseMapper<StockEntity> {
    /** 只取名字：快照里的 stock 表没有 refreshed_at，不能用整实体映射。 */
    @Select("SELECT name FROM stock WHERE code = #{code}")
    String selectNameByCode(@Param("code") String code);
}
