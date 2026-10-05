package com.mealmind.service.recommend;

import com.mealmind.config.AgentProperties;
import com.mealmind.service.recommend.AgentClient.AgentCallException;
import com.mealmind.service.recommend.AgentClient.AgentOutcome;
import com.mealmind.service.recommend.AgentClient.AgentRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a throw-away local HTTP server standing in for ai-service: real sockets, real timeouts, no mocks. */
class AgentClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AgentClient clientFor(int status, String body, long delayMs, int readTimeoutMs, AtomicReference<String> seenBody)
            throws IOException {
        stop();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/recommend", exchange -> {
            if (seenBody != null) {
                seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // the client may already have given up (timeout test)
            } finally {
                exchange.close();
            }
        });
        server.start();
        return clientAt("http://127.0.0.1:" + server.getAddress().getPort(), readTimeoutMs);
    }

    private static AgentClient clientAt(String baseUrl, int readTimeoutMs) {
        return new AgentClient(new AgentProperties(true, baseUrl, 500, readTimeoutMs), RestClient.builder());
    }

    private static AgentRequest request() {
        return new AgentRequest("dinner under $15", "sess_1", 7L, "PUBLIC", Map.of("mealTime", List.of("Dinner")), List.of(3L));
    }

    @Test
    void parsesASuccessfulAnswerAndIgnoresFieldsItDoesNotUse() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        AgentClient client = clientFor(200, """
                {"trace_id":"run_abc","status":"SUCCESS","trace_written":true,"cost_usd":0.002,
                 "recommendation":{"meal_id":4,"meal_name":"Bowl","reason":"Fits.","claims":[{"text":"x"}],"no_match_reason":null}}
                """, 0, 2000, seen);

        AgentOutcome outcome = client.recommend(request());

        assertThat(outcome.verifiedAnswer()).isTrue();
        assertThat(outcome.traceId()).isEqualTo("run_abc");
        assertThat(outcome.recommendation().mealId()).isEqualTo(4L);
        assertThat(outcome.recommendation().reason()).isEqualTo("Fits.");
        // the wire format ai-service expects: snake_case names, the user's own words, the exclusion list
        assertThat(seen.get()).contains("\"user_message\":\"dinner under $15\"", "\"session_id\":\"sess_1\"",
                "\"user_id\":7", "\"source_mode\":\"PUBLIC\"", "\"exclude_meal_ids\":[3]", "\"mealTime\":[\"Dinner\"]");
    }

    @Test
    void aNonSuccessStatusIsReturnedAsIsForTheCallerToDecide() throws IOException {
        AgentOutcome outcome = clientFor(200, "{\"trace_id\":\"r\",\"status\":\"UNVERIFIED\",\"recommendation\":null}", 0, 2000, null)
                .recommend(request());
        assertThat(outcome.verifiedAnswer()).isFalse();
        assertThat(outcome.status()).isEqualTo("UNVERIFIED");
    }

    @Test
    void aServerErrorBecomesAnHttpReason() throws IOException {
        assertThatThrownBy(() -> clientFor(503, "{\"detail\":\"OPENAI_API_KEY is not configured\"}", 0, 2000, null).recommend(request()))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.reason()).isEqualTo("AGENT_HTTP_503"));
    }

    @Test
    void aSlowServerTimesOut() throws IOException {
        assertThatThrownBy(() -> clientFor(200, "{}", 1500, 300, null).recommend(request()))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.reason()).isEqualTo("AGENT_TIMEOUT"));
    }

    @Test
    void aServerThatIsNotThereIsUnreachable() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> clientAt("http://127.0.0.1:" + freePort, 500).recommend(request()))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.reason()).isEqualTo("AGENT_UNREACHABLE"));
    }

    @Test
    void anUnreadableBodyIsABadResponse() throws IOException {
        assertThatThrownBy(() -> clientFor(200, "this is not json", 0, 2000, null).recommend(request()))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.reason()).isEqualTo("AGENT_BAD_RESPONSE"));
        assertThatThrownBy(() -> clientFor(200, "{\"no_status\":true}", 0, 2000, null).recommend(request()))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.reason()).isEqualTo("AGENT_BAD_RESPONSE"));
    }

    @Test
    void classifiesTimeoutsByWhetherAConnectionWasMade() {
        var readTimeout = new org.springframework.web.client.ResourceAccessException("I/O error",
                new java.net.SocketTimeoutException("Read timed out"));
        var connectTimeout = new org.springframework.web.client.ResourceAccessException("I/O error",
                new java.net.SocketTimeoutException("Connect timed out"));
        var wrappedWhileReadingBody = new org.springframework.web.client.RestClientException("Error while extracting response",
                new RuntimeException("I/O error while reading input message", new java.net.SocketTimeoutException("Read timed out")));

        assertThat(AgentClient.classify(readTimeout)).isEqualTo("AGENT_TIMEOUT");
        assertThat(AgentClient.classify(wrappedWhileReadingBody)).isEqualTo("AGENT_TIMEOUT");
        assertThat(AgentClient.classify(connectTimeout)).isEqualTo("AGENT_UNREACHABLE");
    }
}
