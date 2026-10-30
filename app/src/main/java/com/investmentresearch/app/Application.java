package com.investmentresearch.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.investmentresearch")
public class Application {
    public static void main(String[] args) throws IOException {
        // sqlite-jdbc fails with SQLITE_CANTOPEN when the parent directory is missing
        Files.createDirectories(Path.of(System.getenv().getOrDefault("APP_DATA_DIR", "./var")));
        SpringApplication.run(Application.class, args);
    }
}
