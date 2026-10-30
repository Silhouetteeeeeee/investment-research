package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnnouncementMapper extends BaseMapper<AnnouncementEntity> {
    /** Python {@code insert or replace}：同一 {@code (code, art_code)} 重抓时覆盖。 */
    @Insert(
            "insert or replace into announcement"
                    + " (code, art_code, notice_date, title, categories, url, fetched_at)"
                    + " values (#{code}, #{artCode}, #{noticeDate}, #{title},"
                    + " #{categories}, #{url}, #{fetchedAt})")
    int upsert(AnnouncementEntity row);
}
