package com.investmentresearch.app.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
        // The management port inherits this chain; without it the container
        // HEALTHCHECK (and the bare-metal health probe) would get a 401.
        "/actuator/health"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, ApiTokenProperties properties) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // SSE responses complete on an ASYNC dispatch, which the chain re-authorizes
                // with no SecurityContext; without this every streamed answer would 401.
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // No sessions: a 401 otherwise saves the request into a new JSESSIONID.
                .sessionManagement(
                        sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        (request, response, exception) -> {
                                            response.setStatus(
                                                    HttpServletResponse.SC_UNAUTHORIZED);
                                            response.setCharacterEncoding("UTF-8");
                                            response.setContentType(
                                                    MediaType.APPLICATION_JSON_VALUE);
                                            response.getWriter().write("{\"error\":\"unauthorized\"}");
                                        }))
                .addFilterBefore(
                        new TokenAuthFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
