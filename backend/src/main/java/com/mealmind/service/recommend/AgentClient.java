package com.mealmind.service.recommend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mealmind.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for ai-service's POST /v1/recommend, with hard connect/read timeouts. Every way the call can go wrong
 * (down, slow, 4xx/5xx, unreadable body) becomes one AgentCallException carrying a short machine-readable reason, so
 * the caller can fall back without caring which one it was. Only the exception class is logged, never the payload.
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    /** What the backend sends: the user's own words plus the context ai-service is deliberately not allowed to store. */
    public record AgentRequest(
            @JsonProperty("user_message") String userMessage,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("source_mode") String sourceMode,
            Map<String, List<String>> slots,
            @JsonProperty("exclude_meal_ids") List<Long> excludeMealIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRecommendation(
            @JsonProperty("meal_id") Long mealId,
            @JsonProperty("meal_name") String mealName,
            String reason,
            @JsonProperty("no_match_reason") String noMatchReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentOutcome(
            @JsonProperty("trace_id") String traceId,
            String status,
            AgentRecommendation recommendation,
            String error) {

        public boolean verifiedAnswer() {
            return "SUCCESS".equals(status) && recommendation != null;
        }
    }

    /** reason is one of AGENT_TIMEOUT, AGENT_UNREACHABLE, AGENT_HTTP_<status>, AGENT_BAD_RESPONSE. */
    public static class AgentCallException extends RuntimeException {
        private final String reason;

        public AgentCallException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private final RestClient restClient;

    public AgentClient(AgentProperties properties, RestClient.Builder builder) {
        // HttpURLConnection-based factory: plain HTTP/1.1, which uvicorn handles without surprises.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.readTimeoutMs());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    public AgentOutcome recommend(AgentRequest request) {
        AgentOutcome outcome;
        try {
            outcome = restClient.post()
                    .uri("/v1/recommend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AgentOutcome.class);
        } catch (RestClientResponseException e) {
            log.warn("ai-service call failed: HTTP {}", e.getStatusCode().value());
            throw new AgentCallException("AGENT_HTTP_" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            String reason = classify(e);
            log.warn("ai-service call failed: {}", reason);
            throw new AgentCallException(reason, e);
        }
        if (outcome == null || outcome.status() == null) {
            throw new AgentCallException("AGENT_BAD_RESPONSE", null);
        }
        return outcome;
    }

    /**
     * AGENT_TIMEOUT only when a connection was made and the answer was too slow (the read timeout). A connect timeout
     * means nobody is listening there (a stopped container shows up this way), so it counts as unreachable. A timeout
     * can surface directly or while the body is read (wrapped as a conversion error), hence the cause-chain walk.
     */
    static String classify(RestClientException error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SocketTimeoutException timeout) {
                boolean connecting = timeout.getMessage() != null && timeout.getMessage().toLowerCase().contains("connect");
                return connecting ? "AGENT_UNREACHABLE" : "AGENT_TIMEOUT";
            }
        }
        return error instanceof ResourceAccessException ? "AGENT_UNREACHABLE" : "AGENT_BAD_RESPONSE";
    }
}
