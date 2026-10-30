package com.investmentresearch.core.domain;

/** A citable data slice; repeated ids are idempotent. */
public record Source(String id, String label, String detail, String asOf, String fetchedAt, String url) {

    public Source(String id, String label, String detail, String asOf, String fetchedAt) {
        this(id, label, detail, asOf, fetchedAt, "");
    }
}
