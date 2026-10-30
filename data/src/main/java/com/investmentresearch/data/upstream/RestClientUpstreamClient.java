package com.investmentresearch.data.upstream;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * RestClient + JDK HttpClient 实现：直连、60s 超时、最多两次尝试（IOException 与 5xx 重试，4xx 不重试），
 * 全局最多 4 个并发请求。信号量只包住单次尝试，退避前已释放，重试路径不会自锁。
 */
public class RestClientUpstreamClient implements UpstreamClient {
    static final int MAX_ATTEMPTS = 2;
    static final int MAX_CONCURRENT = 4;
    static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final long RETRY_DELAY_MS = 500;
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static final Semaphore PERMITS = new Semaphore(MAX_CONCURRENT);

    private final RestClient restClient;

    public RestClientUpstreamClient() {
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        // 不走系统代理：本机 http.proxyHost 会拦掉东财接口
                        .proxy(ProxySelector.of(null))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String get(String url, Map<String, String> headers) {
        return get(url, StandardCharsets.UTF_8, headers);
    }

    @Override
    public String get(String url, Charset charset, Map<String, String> headers) {
        for (int attempt = 1; ; attempt++) {
            RawResponse response;
            try {
                response = attempt(url, headers);
            } catch (RestClientException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(url);
                    continue;
                }
                throw new UpstreamException(0, url, e);
            }
            if (response.status() >= 200 && response.status() < 300) {
                return new String(response.body(), charset);
            }
            if (response.status() < 500 || attempt >= MAX_ATTEMPTS) {
                throw new UpstreamException(response.status(), url);
            }
            sleepBeforeRetry(url);
        }
    }

    /** 单次尝试；信号量在返回或抛错前释放。 */
    private RawResponse attempt(String url, Map<String, String> headers) {
        try {
            PERMITS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(0, url, e);
        }
        try {
            Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new UpstreamException(0, url, e);
            }
            return restClient
                    .get()
                    // URI 重载不再二次编码；字符串重载会把 %28 变成 %2528
                    .uri(uri)
                    .headers(
                            h -> {
                                h.set("User-Agent", USER_AGENT);
                                safeHeaders.forEach(h::set);
                            })
                    .exchange(
                            (request, response) ->
                                    new RawResponse(
                                            response.getStatusCode().value(),
                                            StreamUtils.copyToByteArray(response.getBody())));
        } finally {
            PERMITS.release();
        }
    }

    private static void sleepBeforeRetry(String url) {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(0, url, e);
        }
    }

    private record RawResponse(int status, byte[] body) {}
}
