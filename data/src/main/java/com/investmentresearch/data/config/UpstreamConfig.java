package com.investmentresearch.data.config;

import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import com.investmentresearch.data.upstream.UpstreamClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpstreamConfig {
    @Bean
    public UpstreamClient upstreamClient() {
        return new RestClientUpstreamClient();
    }
}
