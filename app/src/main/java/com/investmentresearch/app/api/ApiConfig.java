package com.investmentresearch.app.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiConfig {
    // A streamed answer can wait minutes on tools and the model without pinning a Tomcat
    // worker; Phase 1 injects this bean by name. Close() would block shutdown until every
    // in-flight stream ends (up to 10 minutes); shutdown() stops accepting work and lets
    // the JVM exit, cutting any stream still running when the container stops.
    @Bean(destroyMethod = "shutdown")
    public ExecutorService askExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
