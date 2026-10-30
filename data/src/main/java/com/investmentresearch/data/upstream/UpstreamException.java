package com.investmentresearch.data.upstream;

/**
 * 上游请求失败。只携带状态码与 URL：请求头与令牌绝不进入异常信息，避免日志泄露。
 */
public class UpstreamException extends RuntimeException {
    private final int status;
    private final String url;

    public UpstreamException(int status, String url) {
        this(status, url, null);
    }

    public UpstreamException(int status, String url, Throwable cause) {
        super(describe(status, url), cause);
        this.status = status;
        this.url = url;
    }

    /** HTTP 状态码；网络层失败（无响应）为 0。 */
    public int status() {
        return status;
    }

    public String url() {
        return url;
    }

    private static String describe(int status, String url) {
        return status > 0 ? "上游请求失败：HTTP " + status + " " + url : "上游请求失败：" + url;
    }
}
