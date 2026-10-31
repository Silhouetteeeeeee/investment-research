package com.investmentresearch.data.config;

import com.investmentresearch.core.port.SearchPort;
import com.investmentresearch.data.adapter.search.WebSearchAdapter;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 联网搜索：密钥缺失不影响启动，调用时由工具返回可操作的失败。 */
@Configuration
public class SearchConfig {
    @Bean
    public SearchPort searchPort(UpstreamClient client, Environment environment) {
        return new WebSearchAdapter(
                client,
                environment.getProperty("BAIDU_API_KEY"),
                environment.getProperty("DEEPSEEK_API_KEY"),
                Clock.systemDefaultZone());
    }
}
