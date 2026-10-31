package com.investmentresearch.core.research;

/** 研究档案校验错误；消息直接展示给模型，文案与 Python MemoryError 一致。 */
public class MemoryException extends RuntimeException {
    public MemoryException(String message) {
        super(message);
    }
}
