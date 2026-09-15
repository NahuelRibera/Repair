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
 * Unit tests for the motorcycle orchestration's policy layer: short
 * circuits, citation validation, and — the largest and most important
 * section — the controlled-action confidence gate (section 2/4/28/29/30
 * of the QA pass: a hypothetical, uncertain, or planned-future statement
 * must NEVER write to the database, no matter what the model itself
 * claims, and a clearly confirmed statement must reliably persist).
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
        lenient().when(sessionRepository.countMessages(SESSION_ID)).thenReturn(2L); // not the first message, skip title generation

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
                {"answerType":"guidance","summary":"ok","confirmedFacts":[],"contextUsed":[],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42]%s}
                """.formatted(extraFields.isEmpty() ? "" : "," + extraFields);
    }

    private String maintenanceProposal(String intent, Object odometerKm) {
        return """
                "proposedMaintenanceEvent":{"serviceType":"ENGINE_OIL_CHANGE","odometerKm":%s,"performedAt":null,"notes":null,"intent":"%s"}
                """.formatted(odometerKm, intent);
    }

    private String odometerProposal(String intent, double odometerKm) {
        return """
                "proposedOdometerUpdate":{"odometerKm":%s,"intent":"%s"}
                """.formatted(odometerKm, intent);
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
        verify(openAiClient, never()).embed(any());
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
                {"answerType":"guidance","summary":"ok","confirmedFacts":[],"contextUsed":[],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42,999]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        assertThat(result.answer().sourceChunkIds()).containsExactly(42L);
    }

    @Test
    void moreThanTwoFollowUpQuestionsAreClampedServerSide() {
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"ok","confirmedFacts":[],"contextUsed":[],
                 "followUpQuestions":["a","b","c","d"],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        assertThat(result.answer().followUpQuestions()).hasSize(2);
    }

    // ---- Section 34 regression matrix: A/B (save), C/D/E/F (must not save) ----

    @Test
    void scenarioA_pastConfirmedOilChangeWithMileage_isSaved() {
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 19000)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 19,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).hasSize(1);
        assertThat(result.actionsTaken().get(0).type()).isEqualTo("maintenance_event_created");
    }

    @Test
    void scenarioB_pastConfirmedOilChangeYesterday_isSaved() {
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 18450)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(18450.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil myself yesterday at 18,450 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(18450.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).hasSize(1);
    }

    @Test
    void scenarioC_uncertainPastMileage_isNeverSaved() {
        stubRetrieval();
        // Even if the model mis-tags this as CONFIRMED_COMPLETED, the
        // deterministic guard on "I think... not sure" must still block it.
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 12000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID,
                "I think the previous owner changed it at 12,000 km, but I'm not sure.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioC_modelCorrectlyTagsUncertainPast_isNeverSaved() {
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("UNCERTAIN_PAST", 12000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID,
                "I think the previous owner changed it at 12,000 km, but I'm not sure.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioD_plannedFutureOilChange_isNeverSaved() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I should change the oil soon.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioE_plannedFutureTomorrow_isNeverSaved() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I might change it tomorrow.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioF_hypotheticalMileage_neverSavesEventOrUpdatesOdometer() {
        stubRetrieval();
        // Reproduces the reported bug exactly: even if the model wrongly
        // proposes both an event and an odometer update tagged
        // CONFIRMED_COMPLETED for a hypothetical question, the
        // deterministic "if I" guard must block both.
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 25000) + "," + odometerProposal("CONFIRMED_COMPLETED", 25000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "If I were at 25,000 km, what maintenance would be due?");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioF_modelCorrectlyTagsHypothetical_isReadOnly() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What would happen if I changed the oil at 20,000 km?");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioG_currentMileageStatement_updatesOdometer() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 23800)));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I'm at 23,800 km now.");

        verify(garageVehicleRepository).updateOdometer(GARAGE_VEHICLE_ID, 23800.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void scenarioH_pastMaintenanceMileageNeverOverwritesCurrentOdometer() {
        // Vehicle stub in setUp() has currentOdometerKm = 20,000. A past
        // maintenance event at a lower mileage must persist as history
        // without ever touching the stored current odometer, and the
        // model here proposes no odometer update at all (as it shouldn't,
        // since the rider only stated a past service point).
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 19000)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 19,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
    }

    @Test
    void scenarioI_missingExactSpecIsNeverInvented() {
        stubRetrieval();
        stubGeneration("""
                {"answerType":"insufficient_evidence",
                 "summary":"I don't have verified bike-specific information for the camshaft bearing cap bolt torque.",
                 "confirmedFacts":[],"contextUsed":[],"followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What is the exact torque for the camshaft bearing cap bolts?");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        assertThat(result.answer().sourceChunkIds()).isEmpty();
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void odometerUpdateBelowCurrentWithoutTextConfirmationIsRejected() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 15000)));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "how's my bike doing");

        verify(garageVehicleRepository, never()).updateOdometer(anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void odometerUpdateBelowCurrentWithTextConfirmationIsAccepted() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 15000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually the odometer reads 15000, it was reset by the previous owner.");

        verify(garageVehicleRepository).updateOdometer(GARAGE_VEHICLE_ID, 15000.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void unknownServiceTypeProposalIsDroppedNotPersisted() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedMaintenanceEvent":{"serviceType":"ENGINE_REBUILD","odometerKm":19000,"performedAt":null,"notes":null,"intent":"CONFIRMED_COMPLETED"}
                """));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void maintenanceEventWithNoOdometerAndNoDateIsRejected() {
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", "null")));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

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
    void firstMessageOfSessionGetsADeterministicTitle() {
        when(sessionRepository.countMessages(SESSION_ID)).thenReturn(1L);
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "When should I change the oil?");

        verify(sessionRepository).updateTitle(SESSION_ID, "Oil change");
    }

    @Test
    void secondMessageOfSessionDoesNotRetitle() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        verify(sessionRepository, never()).updateTitle(eq(SESSION_ID), any());
    }
}
