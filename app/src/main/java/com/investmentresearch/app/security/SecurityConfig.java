package com.investmentresearch.app.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiTokenProperties.class)
public class SecurityConfig {
    private static final String[] PUBLIC_PATHS = {
        "/health",
        "/",
        "/index.html",
        "/app.js",
        "/styles.css",
        "/boot.js",
        "/assets/**",
        "/favicon.ico",
        "/error",
        "/actuator/health"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, ApiTokenProperties properties) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        (request, response, exception) -> {
                                            response.setStatus(
                                                    HttpServletResponse.SC_UNAUTHORIZED);
                                            response.setContentType(
                                                    MediaType.APPLICATION_JSON_VALUE);
                                            response.getWriter().write("{\"error\":\"unauthorized\"}");
                                        }))
                .addFilterBefore(
                        new TokenAuthFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
