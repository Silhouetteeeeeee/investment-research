package com.investmentresearch.core.valuation;

/** 估值输入缺项、非数字或假设在数学上不成立；消息直接给模型看。 */
public final class ValuationInputError extends RuntimeException {
    public ValuationInputError(String message) {
        super(message);
    }
}
