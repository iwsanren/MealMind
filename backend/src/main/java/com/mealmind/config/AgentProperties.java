package com.mealmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the call to ai-service ("mealmind.agent.*", see application.yml).
 * The feature flag defaults to OFF: without explicit opt-in the chat uses the rule-based recommender only.
 */
@ConfigurationProperties(prefix = "mealmind.agent")
public record AgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("2000") int connectTimeoutMs,
        // Longer than ai-service's own 30 s deadline, so the agent normally answers (or reports a timeout) first.
        @DefaultValue("35000") int readTimeoutMs
) {
}
