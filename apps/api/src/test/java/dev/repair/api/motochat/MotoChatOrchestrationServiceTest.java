package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.chat.OpenAiClient;
import dev.repair.api.config.ChatProperties;
import dev.repair.api.config.OpenAiProperties;
import dev.repair.api.garage.GarageVehicleDto;
import dev.repair.api.garage.GarageVehicleRepository;
import dev.repair.api.garage.MaintenanceRepository;
import dev.repair.api.garage.VehiclePreferenceRepository;
import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the motorcycle orchestration's policy layer: the same
 * class of tests as dev.repair.api.chat.ChatOrchestrationServiceTest
 * (short-circuits, citation validation), plus the controlled-action
 * validation that has no car-side equivalent — a proposed write from the
 * model must survive real, independent checks before it is ever executed.
 * OpenAI is always mocked; no network call, no API key needed.
 */
@ExtendWith(MockitoExtension.class)
class MotoChatOrchestrationServiceTest {

    private static final UUID VISITOR_ID = UUID.randomUUID();
    private static final long SESSION_ID = 1L;
    private static final long GARAGE_VEHICLE_ID = 5L;
    private static final long MODEL_ID = 2L;

    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private MotoRetrievalService retrievalService;
    @Mock
    private MotoRagRunRepository ragRunRepository;
    @Mock
    private MotoChatSessionRepository sessionRepository;
    @Mock
    private GarageVehicleRepository garageVehicleRepository;
    @Mock
    private MaintenanceRepository maintenanceRepository;
    @Mock
    private VehiclePreferenceRepository preferenceRepository;
    @Mock
    private MotorcycleCatalogRepository catalogRepository;

    private ChatProperties chatProperties;
    private MotoChatOrchestrationService service;

    @BeforeEach
    void setUp() {
        chatProperties = new ChatProperties(20, 2000, 6, 900, 1200);
        GarageVehicleDto vehicle = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 20000.0,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicle));
        when(ragRunRepository.start(any(), eq(SESSION_ID), eq(GARAGE_VEHICLE_ID), any())).thenReturn(100L);
        when(ragRunRepository.findDebugByRequestId(any())).thenAnswer(inv -> new MotoRagRunDebugDto(
                inv.getArgument(0).toString(), GARAGE_VEHICLE_ID, "Yamaha", "MT-07", 2025, null,
                null, null, null, null, null, null, "ok", null, "[]"
        ));
        when(sessionRepository.insertMessage(eq(SESSION_ID), any(), any(), any())).thenReturn(1L);
        // Not every test reaches the point where these are consulted (e.g. the
        // missing-key and empty-retrieval short-circuits return earlier) —
        // lenient() avoids Mockito's strict-stubbing failure on those tests.
        lenient().when(catalogRepository.findFacts(MODEL_ID, 2025)).thenReturn(Map.of());
        lenient().when(maintenanceRepository.recentEvents(eq(GARAGE_VEHICLE_ID), anyInt())).thenReturn(List.of());

        service = new MotoChatOrchestrationService(
                chatProperties,
                new OpenAiProperties("sk-test-key", "gpt-4.1-mini", "text-embedding-3-small", 1536, 30, 2),
                openAiClient, retrievalService, ragRunRepository, sessionRepository, garageVehicleRepository,
                maintenanceRepository, preferenceRepository, catalogRepository, JsonMapper.builder().build()
        );
    }

    private MotoRetrievedChunk sampleChunk(long chunkId) {
        return new MotoRetrievedChunk(
                chunkId, 7L, "Periodic maintenance", "Engine oil", "maintenance",
                "Engine oil", "Doc > Engine oil", "Change every 6,000 km.", 0.9, 0.5, 0.8
        );
    }

    private void stubRetrieval() {
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        when(retrievalService.hybridSearch(eq(MODEL_ID), eq(2025), any(), any(), anyInt()))
                .thenReturn(List.of(sampleChunk(42L)));
    }

    private void stubGeneration(String json) {
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationSuccess(json, 100, 50));
    }

    private String answerJson(String extraFields) {
        return """
                {"answerType":"guidance","summary":"ok","confirmedFacts":[],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42]%s}
                """.formatted(extraFields.isEmpty() ? "" : "," + extraFields);
    }

    @Test
    void missingApiKeyShortCircuitsWithoutCallingOpenAi() {
        var unconfigured = new MotoChatOrchestrationService(
                chatProperties, new OpenAiProperties("", "gpt-4.1-mini", "text-embedding-3-small", 1536, 30, 2),
                openAiClient, retrievalService, ragRunRepository, sessionRepository, garageVehicleRepository,
                maintenanceRepository, preferenceRepository, catalogRepository, JsonMapper.builder().build()
        );

        MotoChatTurnResult result = unconfigured.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "hello");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        org.mockito.Mockito.verify(openAiClient, never()).embed(any());
    }

    @Test
    void emptyRetrievalNeverCallsGeneration() {
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        when(retrievalService.hybridSearch(eq(MODEL_ID), eq(2025), any(), any(), anyInt())).thenReturn(List.of());

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        verify(openAiClient, never()).generateStructured(any(), any(), any(), anyInt());
    }

    @Test
    void hallucinatedCitationIsDropped() {
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"ok","confirmedFacts":[],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42,999]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        assertThat(result.answer().sourceChunkIds()).containsExactly(42L);
    }

    @Test
    void validMaintenanceProposalIsExecuted() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedMaintenanceEvent":{"serviceType":"ENGINE_OIL_CHANGE","odometerKm":19000,"performedAt":null,"notes":null}
                """));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 19000 km");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).hasSize(1);
        assertThat(result.actionsTaken().get(0).type()).isEqualTo("maintenance_event_created");
    }

    @Test
    void unknownServiceTypeProposalIsDroppedNotPersisted() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedMaintenanceEvent":{"serviceType":"ENGINE_REBUILD","odometerKm":19000,"performedAt":null,"notes":null}
                """));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void odometerUpdateAboveCurrentIsAccepted() {
        stubRetrieval();
        stubGeneration(answerJson("\"proposedOdometerUpdate\":{\"odometerKm\":23800}"));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I'm at 23,800 km now");

        verify(garageVehicleRepository).updateOdometer(GARAGE_VEHICLE_ID, 23800.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void odometerUpdateBelowCurrentWithoutTextConfirmationIsRejected() {
        // Current odometer (mocked in setUp) is 20,000 km; the model proposes
        // dropping it to 15,000 with no supporting number in the user's own text.
        stubRetrieval();
        stubGeneration(answerJson("\"proposedOdometerUpdate\":{\"odometerKm\":15000}"));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "how's my bike doing");

        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void odometerUpdateBelowCurrentWithTextConfirmationIsAccepted() {
        stubRetrieval();
        stubGeneration(answerJson("\"proposedOdometerUpdate\":{\"odometerKm\":15000}"));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually the odometer reads 15000, I think it was reset");

        verify(garageVehicleRepository).updateOdometer(GARAGE_VEHICLE_ID, 15000.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void hypotheticalQuestionProposesNoAction() {
        stubRetrieval();
        // The model itself is expected (per system prompt) never to populate a
        // proposal for a hypothetical — this test proves the plumbing doesn't
        // execute anything when no proposal is present in the JSON at all.
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What maintenance would I need if I were at 30000 km?");

        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void validPreferenceProposalIsSaved() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedPreference":{"preferenceType":"TIRE_PRESSURE","context":"OFF_ROAD","frontKpa":190,"rearKpa":200}
                """));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "For off-road I run 1.9 bar front and 2.0 bar rear");

        verify(preferenceRepository).upsert(eq(GARAGE_VEHICLE_ID), eq("TIRE_PRESSURE"), eq("OFF_ROAD"), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("preference_saved"));
    }

    @Test
    void maintenanceEventWithNoOdometerAndNoDateIsRejected() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedMaintenanceEvent":{"serviceType":"CHAIN_LUBE","odometerKm":null,"performedAt":null,"notes":null}
                """));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }
}
