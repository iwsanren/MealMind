package com.mealmind.dto.internal;

/** Body of POST /internal/v1/risk/check. text is required and must not be blank. */
public record RiskCheckRequest(String text) {
}
