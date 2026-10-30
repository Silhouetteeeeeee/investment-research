package com.investmentresearch.data.upstream;

import java.nio.charset.Charset;
import java.util.Map;

/** 上游 HTTP 取数入口；失败统一抛 {@link UpstreamException}。 */
public interface UpstreamClient {
    /** 按 UTF-8 解码响应体。 */
    String get(String url, Map<String, String> headers);

    String get(String url, Charset charset, Map<String, String> headers);
}
