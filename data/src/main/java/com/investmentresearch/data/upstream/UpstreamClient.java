package com.investmentresearch.data.upstream;

import java.nio.charset.Charset;
import java.util.Map;

/** 上游 HTTP 取数入口；失败统一抛 {@link UpstreamException}。 */
public interface UpstreamClient {
    /** 按 UTF-8 解码响应体。 */
    String get(String url, Map<String, String> headers);

    String get(String url, Charset charset, Map<String, String> headers);

    /** POST 一段 JSON；响应按 UTF-8 解码。 */
    default String post(String url, Map<String, String> headers, String body) {
        // 只读的测试替身不必实现；生产实现见 RestClientUpstreamClient
        throw new UnsupportedOperationException("POST 未实现");
    }
}
