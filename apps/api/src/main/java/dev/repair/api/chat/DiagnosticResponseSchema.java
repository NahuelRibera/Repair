package dev.repair.api.chat;

import java.util.List;
import java.util.Map;

/** The JSON Schema sent to OpenAI's Responses API (strict structured
 * outputs) describing {@link DiagnosticAnswer}. */
final class DiagnosticResponseSchema {

    private DiagnosticResponseSchema() {
    }

    static final String NAME = "diagnostic_answer";

    static Map<String, Object> build() {
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> integerArray = Map.of("type", "array", "items", Map.of("type", "integer"));

        Map<String, Object> hypothesis = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("description", "reasoning", "evidenceChunkIds"),
                "properties", Map.of(
                        "description", Map.of("type", "string"),
                        "reasoning", Map.of("type", "string"),
                        "evidenceChunkIds", integerArray
                )
        );

        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("answerType", Map.of(
                "type", "string",
                "enum", List.of("clarification", "guidance", "insufficient_evidence", "safety_referral")
        ));
        properties.put("summary", Map.of("type", "string"));
        properties.put("confirmedSymptoms", stringArray);
        properties.put("followUpQuestions", stringArray);
        properties.put("hypotheses", Map.of("type", "array", "items", hypothesis));
        properties.put("safeChecks", stringArray);
        properties.put("cautions", stringArray);
        properties.put("missingInformation", stringArray);
        properties.put("sourceChunkIds", integerArray);

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("properties", properties);
        return schema;
    }
}
