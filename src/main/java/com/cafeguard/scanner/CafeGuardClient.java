package com.cafeguard.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Outbound HTTPS client to CafeGuard:
 * login → enqueue fiscal URL (CafeGuard worker parses + creates order).
 */
public final class CafeGuardClient {

    private static final Logger log =
            LoggerFactory.getLogger(CafeGuardClient.class);

    private final GatewayConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public CafeGuardClient(GatewayConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public void submitBarcode(String barcode) throws Exception {
        String payload = barcode.trim();
        ensureToken();

        ObjectNode body = mapper.createObjectNode();
        body.put("text", payload);

        JsonNode queued = postJson(
                "/api/receipts/fiscal-scans",
                body,
                true
        );
        log.info(
                "CafeGuard queued fiscal scan id={} status={}",
                textOr(queued.get("id"), "?"),
                textOr(queued.get("status"), "QUEUED")
        );
    }

    private void ensureToken() throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("email", config.email);
        body.put("password", config.password);
        JsonNode response = postJson("/api/auth/login", body, false);
        String next = textOr(response.get("token"), null);
        if (next == null || next.isBlank()) {
            throw new IllegalStateException(
                    "Login succeeded but no session token returned."
            );
        }
        token = next;
        tokenExpiresAt = Instant.now().plus(Duration.ofMinutes(35));
        log.info("Signed in to CafeGuard as {}", config.email);
    }

    private JsonNode postJson(
            String path,
            ObjectNode body,
            boolean auth
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body)
                        )
                );
        if (auth) {
            ensureToken();
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = http.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        int code = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();

        if (code == 401 && auth) {
            token = null;
            tokenExpiresAt = Instant.EPOCH;
            ensureToken();
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)
                            )
                    );
            response = http.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            code = response.statusCode();
            responseBody = response.body() == null ? "" : response.body();
        }

        if (code < 200 || code >= 300) {
            String message = extractMessage(responseBody);
            throw new IllegalStateException(
                    "HTTP " + code + " " + path + ": " + message
            );
        }

        if (responseBody.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(responseBody);
    }

    private String extractMessage(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber() || node.isTextual() || node.isBoolean()) {
            String text = node.asText();
            return text == null || text.isBlank() ? fallback : text;
        }
        return fallback;
    }
}
