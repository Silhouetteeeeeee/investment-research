package com.investmentresearch.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ApiTokenProperties(String apiToken) {
    public ApiTokenProperties {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("APP_API_TOKEN 未配置");
        }
        // Tomcat decodes headers as ISO-8859-1, so a non-ASCII secret could never match.
        if (!apiToken.chars().allMatch(c -> c < 128)) {
            throw new IllegalStateException("APP_API_TOKEN 只能包含 ASCII 字符");
        }
    }

    @Override
    public String toString() {
        return "ApiTokenProperties[apiToken=<redacted>]";
    }
}
