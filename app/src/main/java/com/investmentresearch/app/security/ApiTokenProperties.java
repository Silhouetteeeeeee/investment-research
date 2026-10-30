package com.investmentresearch.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ApiTokenProperties(String apiToken) {
    public ApiTokenProperties {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("APP_API_TOKEN 未配置");
        }
    }

    @Override
    public String toString() {
        return "ApiTokenProperties[apiToken=<redacted>]";
    }
}
