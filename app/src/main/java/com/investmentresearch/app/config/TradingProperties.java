package com.investmentresearch.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 交易调度配置；缺省开启，只有显式 false 才关。 */
@ConfigurationProperties("app.trading")
public record TradingProperties(Boolean enabled, Integer pollSeconds, Integer phaseTimeoutMinutes) {
    public TradingProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        pollSeconds = pollSeconds == null || pollSeconds <= 0 ? 10 : pollSeconds;
        phaseTimeoutMinutes =
                phaseTimeoutMinutes == null || phaseTimeoutMinutes <= 0
                        ? 15
                        : phaseTimeoutMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
