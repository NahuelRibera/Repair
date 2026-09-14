package dev.repair.api.motochat;

import dev.repair.api.garage.ServiceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON Schema sent to OpenAI's Responses API (strict structured
 * outputs) describing {@link MotoDiagnosticAnswer}. Optional/nullable
 * fields use a ["object","null"] (or ["number","null"]) type union rather
 * than being omitted from "required" — OpenAI's strict mode requires
 * every property key to be present in "required", but the value itself
 * can still be null, which is how a field is meant to be "not proposed
 * this turn". */
final class MotoDiagnosticResponseSchema {

    private MotoDiagnosticResponseSchema() {
    }

    static final String NAME = "moto_diagnostic_answer";

    static Map<String, Object> build() {
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> integerArray = Map.of("type", "array", "items", Map.of("type", "integer"));
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> nullableNumber = Map.of("type", List.of("number", "null"));

        Map<String, Object> proposedMaintenanceEventProps = new LinkedHashMap<>();
        proposedMaintenanceEventProps.put("serviceType", Map.of("type", "string", "enum", List.copyOf(ServiceType.VALID)));
        proposedMaintenanceEventProps.put("odometerKm", nullableNumber);
        proposedMaintenanceEventProps.put("performedAt", nullableString);
        proposedMaintenanceEventProps.put("notes", nullableString);
        Map<String, Object> proposedMaintenanceEvent = Map.of(
                "type", List.of("object", "null"),
                "additionalProperties", false,
                "required", List.copyOf(proposedMaintenanceEventProps.keySet()),
                "properties", proposedMaintenanceEventProps
        );

        Map<String, Object> proposedOdometerUpdateProps = Map.of("odometerKm", Map.of("type", "number"));
        Map<String, Object> proposedOdometerUpdate = Map.of(
                "type", List.of("object", "null"),
                "additionalProperties", false,
                "required", List.of("odometerKm"),
                "properties", proposedOdometerUpdateProps
        );

        Map<String, Object> proposedPreferenceProps = new LinkedHashMap<>();
        proposedPreferenceProps.put("preferenceType", Map.of("type", "string", "enum", List.of("TIRE_PRESSURE")));
        proposedPreferenceProps.put("context", Map.of("type", "string", "enum", List.of("ROAD", "OFF_ROAD", "WET", "TRACK")));
        proposedPreferenceProps.put("frontKpa", nullableNumber);
        proposedPreferenceProps.put("rearKpa", nullableNumber);
        Map<String, Object> proposedPreference = Map.of(
                "type", List.of("object", "null"),
                "additionalProperties", false,
                "required", List.copyOf(proposedPreferenceProps.keySet()),
                "properties", proposedPreferenceProps
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answerType", Map.of(
                "type", "string",
                "enum", List.of("clarification", "guidance", "insufficient_evidence", "safety_referral")
        ));
        properties.put("summary", Map.of("type", "string"));
        properties.put("confirmedFacts", stringArray);
        properties.put("followUpQuestions", stringArray);
        properties.put("safeChecks", stringArray);
        properties.put("cautions", stringArray);
        properties.put("sourceChunkIds", integerArray);
        properties.put("proposedMaintenanceEvent", proposedMaintenanceEvent);
        properties.put("proposedOdometerUpdate", proposedOdometerUpdate);
        properties.put("proposedPreference", proposedPreference);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("properties", properties);
        return schema;
    }
}
