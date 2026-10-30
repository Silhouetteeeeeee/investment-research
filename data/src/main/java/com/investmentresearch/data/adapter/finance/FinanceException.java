package com.investmentresearch.data.adapter.finance;

/** 财报抓取/解析失败；消息可直接展示给用户，和 Python {@code FinanceError} 一致。 */
class FinanceException extends RuntimeException {
    FinanceException(String message) {
        super(message);
    }
}
