package com.investmentresearch.data.adapter.f10;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.CompanyProfilePort;
import com.investmentresearch.data.persistence.CompanyProfileEntity;
import com.investmentresearch.data.persistence.CompanyProfileMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 东财 F10 公司概况：上游 → 解析 → 覆盖写入 → 输出。 */
@Repository
public class CompanyProfileAdapter implements CompanyProfilePort {
    private static final Logger LOG = LoggerFactory.getLogger(CompanyProfileAdapter.class);
    static final int SCOPE_MAX_CHARS = 200;
    // 30 天是任务规格；Python 侧 7 天常量是缓存 TTL，这里有意偏离
    static final long MAX_AGE_DAYS = 30;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CompanyProfileMapper mapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public CompanyProfileAdapter(
            CompanyProfileMapper mapper,
            UpstreamClient upstreamClient,
            @Value(
                            "${app.f10-profile-url:"
                                    + "https://emweb.securities.eastmoney.com"
                                    + "/PC_HSF10/CompanySurvey/PageAjax}")
                    String endpoint,
            PlatformTransactionManager transactionManager) {
        this(mapper, upstreamClient, endpoint, Clock.systemDefaultZone(), transactionManager);
    }

    CompanyProfileAdapter(
            CompanyProfileMapper mapper,
            UpstreamClient upstreamClient,
            String endpoint,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ToolResult companyProfile(String rawCode) {
        String code = F10Support.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        String prefix = F10Support.prefix(code);
        if ("UNKNOWN".equals(prefix)) {
            return ToolResult.failure(code, code + "：无法判断交易所");
        }

        try {
            CompanyProfileEntity cached = mapper.selectById(code);
            if (cached != null && fresh(cached)) {
                return toResult(cached);
            }
            try {
                CompanyProfileEntity row = fetch(code, prefix);
                replace(row);
                return toResult(row);
            } catch (RuntimeException error) {
                // 抓取失败但有旧缓存时按 Python 的做法继续用旧数据
                if (cached != null) {
                    LOG.warn(
                            "公司概况上游取数失败，回退本地缓存：code={} reason={}",
                            code,
                            error.getMessage());
                    return toResult(cached);
                }
                LOG.warn(
                        "公司概况上游取数失败且无缓存：code={} reason={}",
                        code,
                        error.getMessage());
                return ToolResult.failure(code, error.getMessage());
            }
        } catch (RuntimeException error) {
            // 端口不抛异常：读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    /**
     * 与 Python 的 insert or replace 等价：删后插，上游消失的字段会变回 null。
     * insertOrUpdate 走 updateById，默认跳过 null，会把旧值留在库里。
     */
    private void replace(CompanyProfileEntity row) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    mapper.deleteById(row.getCode());
                    mapper.insert(row);
                });
    }

    private CompanyProfileEntity fetch(String code, String prefix) {
        String url = endpoint + "?code=" + prefix + code;
        JsonNode payload;
        try {
            payload = MAPPER.readTree(upstreamClient.get(url, Map.of()));
        } catch (JsonProcessingException error) {
            throw new F10Exception("获取公司概况失败：" + error.getMessage());
        } catch (RuntimeException error) {
            throw new F10Exception("获取公司概况失败：" + error.getMessage());
        }
        JsonNode rows = payload == null ? null : payload.path("jbzl");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new F10Exception(code + "：没有取到公司概况");
        }

        JsonNode raw = rows.get(0);
        CompanyProfileEntity row = new CompanyProfileEntity();
        row.setCode(code);
        row.setName(F10Support.text(raw.get("SECURITY_NAME_ABBR")));
        row.setIndustry(F10Support.text(raw.get("EM2016")));
        row.setProfile(F10Support.text(raw.get("ORG_PROFILE")));
        row.setScope(F10Support.clip(F10Support.text(raw.get("BUSINESS_SCOPE")), SCOPE_MAX_CHARS));
        row.setEmployees(F10Support.integer(raw.get("EMP_NUM")));
        row.setFetchedAt(LocalDateTime.now(clock).format(TIMESTAMP));
        return row;
    }

    private boolean fresh(CompanyProfileEntity row) {
        if (row.getFetchedAt() == null) {
            return false;
        }
        try {
            LocalDateTime fetched = LocalDateTime.parse(row.getFetchedAt(), TIMESTAMP);
            return Duration.between(fetched, LocalDateTime.now(clock)).toDays() < MAX_AGE_DAYS;
        } catch (RuntimeException error) {
            LOG.debug("公司概况缓存时间无法解析，按过期处理：code={}", row.getCode());
            return false;
        }
    }

    private ToolResult toResult(CompanyProfileEntity row) {
        String asOf = F10Support.datePart(row.getFetchedAt());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", row.getName());
        data.put("industry", row.getIndustry());
        data.put("profile", row.getProfile());
        data.put("scope", row.getScope());
        data.put("employees", row.getEmployees());
        data.put("as_of", asOf);
        Source source =
                new Source(
                        "profile:" + row.getCode(),
                        "公司概况",
                        F10Support.py(row.getName()) + " · " + F10Support.py(row.getIndustry()),
                        asOf,
                        row.getFetchedAt() == null ? "" : row.getFetchedAt());
        return ToolResult.ok(row.getCode(), asOf, List.of(source), data);
    }

    /** 解析失败也要带中文原因走到 ToolResult.failure，不能逃出端口。 */
    private static final class F10Exception extends RuntimeException {
        F10Exception(String message) {
            super(message);
        }
    }
}
