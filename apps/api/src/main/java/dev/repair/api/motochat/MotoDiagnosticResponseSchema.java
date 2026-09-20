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

    /**
     * The rider-intent classification every proposed action must carry.
     * Only CONFIRMED_COMPLETED may ever be executed as a write — see
     * MotoDiagnosticAnswer.ProposedMaintenanceEvent and
     * MotoChatOrchestrationService.validateAndApplyProposedActions.
     */
    static final List<String> INTENT_VALUES = List.of(
            "CONFIRMED_COMPLETED", "UNCERTAIN_PAST", "PLANNED_FUTURE",
            "HYPOTHETICAL", "QUESTION", "RECOMMENDATION", "UNKNOWN"
    );

    static Map<String, Object> build() {
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));
        // At most 2 follow-up questions per turn (see docs/repair-v2-architecture.md
        // "follow-up question discipline") — the model is instructed to prefer 0-1,
        // this is a hard ceiling so the UI can never turn into a five-question form.
        Map<String, Object> followUpArray = Map.of(
                "type", "array", "items", Map.of("type", "string"), "maxItems", 2
        );
        Map<String, Object> integerArray = Map.of("type", "array", "items", Map.of("type", "integer"));
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> nullableNumber = Map.of("type", List.of("number", "null"));
        Map<String, Object> intentEnum = Map.of("type", "string", "enum", INTENT_VALUES);

        Map<String, Object> proposedMaintenanceEventProps = new LinkedHashMap<>();
        proposedMaintenanceEventProps.put("serviceType", Map.of("type", "string", "enum", List.copyOf(ServiceType.VALID)));
        proposedMaintenanceEventProps.put("odometerKm", nullableNumber);
        proposedMaintenanceEventProps.put("performedAt", nullableString);
        proposedMaintenanceEventProps.put("notes", nullableString);
        proposedMaintenanceEventProps.put("intent", intentEnum);
        // True only when this proposal corrects a value already stated earlier in
        // this same conversation for the same service type — never true for a
        // genuinely new, separate occurrence. See MotoDiagnosticAnswer.ProposedMaintenanceEvent.
        proposedMaintenanceEventProps.put("isCorrection", Map.of("type", "boolean"));
        Map<String, Object> proposedMaintenanceEventItem = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.copyOf(proposedMaintenanceEventProps.keySet()),
                "properties", proposedMaintenanceEventProps
        );
        // A LIST, never a single nullable object: a rider can confirm more
        // than one distinct maintenance action in one message ("I changed
        // the oil and oil filter at 24,000 km") and every independently
        // confirmed action needs its own entry. An empty array means
        // nothing was proposed this turn. maxItems is a sane ceiling, not
        // a realistic expectation — nothing in this app asks for more.
        Map<String, Object> proposedMaintenanceEvents = Map.of(
                "type", "array",
                "items", proposedMaintenanceEventItem,
                "maxItems", 6
        );

        Map<String, Object> proposedOdometerUpdateProps = new LinkedHashMap<>();
        proposedOdometerUpdateProps.put("odometerKm", Map.of("type", "number"));
        proposedOdometerUpdateProps.put("intent", intentEnum);
        Map<String, Object> proposedOdometerUpdate = Map.of(
                "type", List.of("object", "null"),
                "additionalProperties", false,
                "required", List.copyOf(proposedOdometerUpdateProps.keySet()),
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
        properties.put("contextUsed", stringArray);
        properties.put("followUpQuestions", followUpArray);
        properties.put("safeChecks", stringArray);
        properties.put("cautions", stringArray);
        properties.put("sourceChunkIds", integerArray);
        properties.put("proposedMaintenanceEvents", proposedMaintenanceEvents);
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
