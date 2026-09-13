package dev.repair.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JSON Schema handed to OpenAI's strict structured-output mode has
 * hard requirements: every property must be listed in "required" and
 * every object needs additionalProperties=false, or the API rejects the
 * request outright. This is cheap to get wrong when the schema is edited
 * by hand, so it is verified directly rather than only implicitly via a
 * live call.
 */
class DiagnosticResponseSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyTopLevelPropertyIsMarkedRequired() {
        Map<String, Object> schema = DiagnosticResponseSchema.build();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<Object> required = (List<Object>) schema.get("required");

        assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hypothesisItemsAlsoDisallowAdditionalProperties() {
        Map<String, Object> schema = DiagnosticResponseSchema.build();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> hypotheses = (Map<String, Object>) properties.get("hypotheses");
        Map<String, Object> item = (Map<String, Object>) hypotheses.get("items");

        assertThat(item.get("additionalProperties")).isEqualTo(false);
        assertThat((List<Object>) item.get("required"))
                .containsExactlyInAnyOrder("description", "reasoning", "evidenceChunkIds");
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerTypeEnumMatchesTheContractUsedInDiagnosticAnswer() {
        Map<String, Object> schema = DiagnosticResponseSchema.build();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> answerType = (Map<String, Object>) properties.get("answerType");
        List<String> enumValues = (List<String>) answerType.get("enum");

        assertThat(enumValues).containsExactly("clarification", "guidance", "insufficient_evidence", "safety_referral");
    }
}
