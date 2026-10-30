package com.investmentresearch.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Excluding the default user stops Boot from creating an unreachable account and logging its
// generated password; the only credential this app accepts is APP_API_TOKEN.
@EnableScheduling
@SpringBootApplication(
        scanBasePackages = "com.investmentresearch",
        exclude = UserDetailsServiceAutoConfiguration.class)
public class Application {
    public static void main(String[] args) throws IOException {
        // sqlite-jdbc fails with SQLITE_CANTOPEN when the parent directory is missing
        Files.createDirectories(Path.of(System.getenv().getOrDefault("APP_DATA_DIR", "./var")));
        SpringApplication.run(Application.class, args);
    }
}
