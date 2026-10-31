package com.investmentresearch.core.port;

import java.util.List;
import java.util.Map;

/** 按公司持久化的研究档案；写操作只到记录级，变化不可改删。 */
public interface ResearchMemoryPort {
    /** Python {@code MEMORY.companies(query)}：按代码/简称模糊匹配。 */
    List<Map<String, Object>> companies(String query);

    /** Python {@code MEMORY.company(code)}：精确取一家公司的索引，没有返回 null。 */
    Map<String, Object> company(String code);

    /** 档案页用；{@code limit} 为 null 表示不分页。没有档案返回 null。 */
    Map<String, Object> detail(String code, String status, Integer limit);

    Map<String, Object> read(
            String code, String section, String recordId, String status, int limit, int offset);

    Map<String, Object> append(String code, String section, Map<String, Object> record, String name);

    Map<String, Object> update(
            String code, String section, String recordId, Map<String, Object> patch);

    Map<String, Object> change(String code, Map<String, Object> payload);
}
