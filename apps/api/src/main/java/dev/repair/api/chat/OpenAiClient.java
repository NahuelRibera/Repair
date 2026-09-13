package dev.repair.api.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.repair.api.config.OpenAiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Minimal, inspectable OpenAI REST client using the JDK HttpClient — no
 * vendor SDK. Talks to the Responses API (POST /v1/responses) for
 * structured-output generation and the Embeddings API (POST
 * /v1/embeddings) for the knowledge-corpus query vector. Every call is
 * bounded by a timeout and a small number of retries on transient failure;
 * callers translate exceptions into the rag_runs.provider_status enum
 * rather than letting them bubble up as a generic 500.
 */
@Component
public class OpenAiClient {

    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final URI EMBEDDINGS_URI = URI.create("https://api.openai.com/v1/embeddings");

    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiClient(OpenAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .build();
    }

    public sealed interface EmbeddingResult permits EmbeddingSuccess, EmbeddingFailure {}
    public record EmbeddingSuccess(float[] vector) implements EmbeddingResult {}
    public record EmbeddingFailure(String status, String detail) implements EmbeddingResult {}

    public EmbeddingResult embed(String text) {
        Map<String, Object> body = Map.of(
                "model", properties.embeddingModel(),
                "input", text
        );
        try {
            JsonNode response = postWithRetry(EMBEDDINGS_URI, body);
            if (response == null) {
                return new EmbeddingFailure("error", "no response");
            }
            JsonNode embeddingNode = response.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray()) {
                return new EmbeddingFailure("invalid_output", "missing data[0].embedding");
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return new EmbeddingSuccess(vector);
        } catch (RateLimitException e) {
            return new EmbeddingFailure("rate_limited", e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return new EmbeddingFailure("timeout", e.getMessage());
        } catch (Exception e) {
            return new EmbeddingFailure("error", e.getMessage());
        }
    }

    public sealed interface GenerationResult permits GenerationSuccess, GenerationFailure {}
    public record GenerationSuccess(String jsonText, Integer inputTokens, Integer outputTokens) implements GenerationResult {}
    public record GenerationFailure(String status, String detail) implements GenerationResult {}

    /**
     * @param schema a JSON Schema object (as a Map, matching the OpenAI
     *               "text.format" json_schema wrapper) that the model's
     *               output must strictly conform to.
     */
    public GenerationResult generateStructured(
            List<Map<String, String>> input, Map<String, Object> schema, String schemaName, int maxOutputTokens
    ) {
        Map<String, Object> body = Map.of(
                "model", properties.generationModel(),
                "input", input,
                "max_output_tokens", maxOutputTokens,
                "text", Map.of(
                        "format", Map.of(
                                "type", "json_schema",
                                "name", schemaName,
                                "strict", true,
                                "schema", schema
                        )
                )
        );
        try {
            JsonNode response = postWithRetry(RESPONSES_URI, body);
            if (response == null) {
                return new GenerationFailure("error", "no response");
            }
            String status = response.path("status").asText("");
            if ("incomplete".equals(status)) {
                return new GenerationFailure("invalid_output", "response marked incomplete (likely truncated)");
            }
            String outputText = extractOutputText(response);
            if (outputText == null) {
                return new GenerationFailure("invalid_output", "no output_text message found in response");
            }
            Integer inTok = response.path("usage").path("input_tokens").isMissingNode()
                    ? null : response.path("usage").path("input_tokens").asInt();
            Integer outTok = response.path("usage").path("output_tokens").isMissingNode()
                    ? null : response.path("usage").path("output_tokens").asInt();
            return new GenerationSuccess(outputText, inTok, outTok);
        } catch (RateLimitException e) {
            return new GenerationFailure("rate_limited", e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return new GenerationFailure("timeout", e.getMessage());
        } catch (Exception e) {
            return new GenerationFailure("error", e.getMessage());
        }
    }

    private String extractOutputText(JsonNode response) {
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    return content.path("text").asText(null);
                }
            }
        }
        return null;
    }

    private static class RateLimitException extends RuntimeException {
        RateLimitException(String message) {
            super(message);
        }
    }

    private JsonNode postWithRetry(URI uri, Map<String, Object> body) throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(body);
        Exception lastError = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    lastError = new RateLimitException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
                    sleepBackoff(attempt);
                    continue;
                }
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + truncate(response.body()));
                }
                return objectMapper.readTree(response.body());
            } catch (java.net.http.HttpTimeoutException e) {
                throw e;
            } catch (IOException e) {
                lastError = e;
                sleepBackoff(attempt);
            }
        }
        if (lastError instanceof RateLimitException rle) {
            throw rle;
        }
        throw new IOException("Exhausted retries calling " + uri, lastError);
    }

    private void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(Duration.ofMillis(300L * (attempt + 1)));
    }

    private String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
