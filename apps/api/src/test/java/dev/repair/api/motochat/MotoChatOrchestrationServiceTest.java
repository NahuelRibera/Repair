package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final long VISITOR_ID = 501L;
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
        return maintenanceProposal("ENGINE_OIL_CHANGE", intent, odometerKm, false);
    }

    private String maintenanceProposal(String serviceType, String intent, Object odometerKm, boolean isCorrection) {
        return """
                "proposedMaintenanceEvents":[%s]
                """.formatted(maintenanceEvent(serviceType, odometerKm, intent, isCorrection));
    }

    private String maintenanceEvent(String serviceType, Object odometerKm, String intent, boolean isCorrection) {
        return """
                {"serviceType":"%s","odometerKm":%s,"performedAt":null,"notes":null,"intent":"%s","isCorrection":%s}
                """.formatted(serviceType, odometerKm, intent, isCorrection);
    }

    /** Multiple independently confirmed maintenance events proposed in a
     * single turn — see the "COMPOUND AND MULTIPLE MAINTENANCE STATEMENTS"
     * system-prompt section: proposedMaintenanceEvents is a list, and a
     * rider confirming several real services in one message must produce
     * one entry per service, never collapsed into one. */
    private String maintenanceProposals(String... events) {
        return """
                "proposedMaintenanceEvents":[%s]
                """.formatted(String.join(",", events));
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
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioF_modelCorrectlyTagsHypothetical_isReadOnly() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What would happen if I changed the oil at 20,000 km?");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void scenarioG_currentMileageStatement_updatesOdometer() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 23800)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0)).thenReturn(Optional.of(23800.0));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I'm at 23,800 km now.");

        verify(garageVehicleRepository).updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void informalKShorthandCurrentOdometerStatementIsGroundedAndPersisted() {
        // Reported bug: "my bike is 25k" is the same rider intent as
        // "my bike is 25,000 km" — the model correctly proposes
        // odometerKm=25000, but groundedInCurrentTurn's plain digit-string
        // match ("25000") never matched the literal text "25k", so the
        // proposal was rejected as "not grounded" and never persisted.
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 25000)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 25000.0)).thenReturn(Optional.of(25000.0));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "my bike is 25k");

        verify(garageVehicleRepository).updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 25000.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated") && a.odometerKm().equals(25000.0));
    }

    @Test
    void kShorthandNeverFuzzyMatchesADifferentNearbyNumber() {
        // The shorthand recognition must only match an EXACT round-
        // thousands value spelled out as "Xk" — never a substring inside
        // an unrelated larger number, and never through a different unit
        // written the same way ("25km"/"25kg").
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 25000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I towed it on a trailer for 125km today.");

        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void odometerUpdateWithZeroRowsAffectedIsTreatedAsFailure() {
        // Even when the model's intent is confirmed and the guard allows
        // it, a write that affects zero rows (wrong owner, vehicle
        // deleted, etc.) must never be reported as a successful update —
        // this is the "never claim success until it really succeeded"
        // requirement, tested directly against the read-back contract.
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 23800)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0)).thenReturn(Optional.empty());

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I'm at 23,800 km now.");

        verify(garageVehicleRepository).updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0);
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void unrelatedTrailingHedgeClauseDoesNotBlockAConfirmedOdometerStatementInTheSameMessage() {
        // A real, multi-clause rider message: a clearly confirmed odometer
        // reading, followed by an unrelated hedge about a completely
        // different topic. The old whole-message guard would have vetoed
        // this; the sentence-anchored guard must not.
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 23800)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0)).thenReturn(Optional.of(23800.0));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID,
                "The odometer now reads 23,800 km. I should get the chain looked at soon.");

        verify(garageVehicleRepository).updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 23800.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void whenIReachFutureConditionalMileageNeverUpdatesOdometer() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 42000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "When I reach 42,000 km, what should I service?");

        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
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
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
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

        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void odometerUpdateBelowCurrentWithTextConfirmationIsAccepted() {
        stubRetrieval();
        stubGeneration(answerJson(odometerProposal("CONFIRMED_COMPLETED", 15000)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 15000.0)).thenReturn(Optional.of(15000.0));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually the odometer reads 15000, it was reset by the previous owner.");

        verify(garageVehicleRepository).updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 15000.0);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void unknownServiceTypeProposalIsDroppedNotPersisted() {
        stubRetrieval();
        stubGeneration(answerJson("""
                "proposedMaintenanceEvents":[{"serviceType":"ENGINE_REBUILD","odometerKm":19000,"performedAt":null,"notes":null,"intent":"CONFIRMED_COMPLETED","isCorrection":false}]
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

    // ---- Section 2.1/2.2 regression: answerType reconciliation ----

    @Test
    void compoundStatement_confirmedOilChangeIsSavedEvenWhenModelMislabelsClarification() {
        // Reproduces the reported bug: "I changed the oil at 18,000 km" is a
        // clearly confirmed action, but the model asked about the filter
        // and (incorrectly) tagged the whole turn "clarification". The
        // event must still be saved, and the badge must be reconciled to
        // "guidance" since the main statement was, in fact, acted on.
        stubRetrieval();
        stubGeneration("""
                {"answerType":"clarification","summary":"Oil change at 18,000 km noted. Did you replace the filter too?",
                 "confirmedFacts":[],"contextUsed":[],"followUpQuestions":["Did you replace the oil filter as well?"],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42],
                 %s}
                """.formatted(maintenanceProposal("CONFIRMED_COMPLETED", 18000)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(18000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 18,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(18000.0), any(), any(), eq("chat"));
        assertThat(result.answer().answerType()).isEqualTo("guidance");
        assertThat(result.actionsTaken()).hasSize(1);
    }

    @Test
    void completeAnswerWithNoFollowUpsIsNeverLabeledClarification() {
        // "What are the road tire pressures?" style case: a complete
        // factual answer with zero follow-up questions cannot legitimately
        // be "clarification" — nothing is left to ask.
        stubRetrieval();
        stubGeneration("""
                {"answerType":"clarification","summary":"Front 250 kPa, rear 250 kPa.",
                 "confirmedFacts":["Front tire pressure: 250 kPa","Rear tire pressure: 250 kPa"],"contextUsed":[],
                 "followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[42]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What are the road tire pressures?");

        assertThat(result.answer().answerType()).isEqualTo("guidance");
    }

    @Test
    void genuineClarificationWithARealFollowUpAndNoActionStaysClarification() {
        // Negative case: a real "we need one more detail before we can
        // answer" turn (no action executed, a real follow-up question)
        // must NOT be reconciled away.
        stubRetrieval();
        stubGeneration(answerJson("")
                .replace("\"answerType\":\"guidance\"", "\"answerType\":\"clarification\"")
                .replace("\"followUpQuestions\":[]", "\"followUpQuestions\":[\"What is your current odometer reading?\"]"));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "When is my next oil change?");

        assertThat(result.answer().answerType()).isEqualTo("clarification");
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void insufficientEvidenceIsNeverReclassifiedAsGuidance() {
        stubRetrieval();
        stubGeneration("""
                {"answerType":"insufficient_evidence","summary":"Not verified for this bike.",
                 "confirmedFacts":[],"contextUsed":[],"followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[]}
                """);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
    }

    @Test
    void inconsistentHistoryIsFlaggedInThePromptNotSilentlyUsed() {
        // Vehicle's current odometer (from setUp) is 20,000 km; a stored
        // event at 25,000 km is impossible and must be flagged to the model.
        when(maintenanceRepository.recentEvents(eq(GARAGE_VEHICLE_ID), anyInt())).thenReturn(List.of(
                new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 25000.0, null, null, "manual", OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "What maintenance is due soon?");

        org.mockito.ArgumentCaptor<List<Map<String, String>>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(captor.capture(), any(), any(), anyInt());
        String allContent = captor.getValue().stream().map(m -> m.get("content")).reduce("", String::concat);
        assertThat(allContent).contains("INCONSISTENT");
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

    // ---- Maintenance-history / Garage-consistency pass ----

    @Test
    void correctionUpdatesTheExistingEventInsteadOfCreatingASecondOne() {
        // "I actually changed the oil at 19,000 km" correcting an earlier
        // 20,000 km statement in the same conversation — the "actually"
        // is deterministic correction evidence, and must update the prior
        // event in place, never insert a second row.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, true)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I actually changed the oil at 19,000 km.");

        verify(maintenanceRepository).correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null);
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a ->
                a.type().equals("maintenance_event_corrected") && a.odometerKm().equals(19000.0));
    }

    @Test
    void correctionWithNoPriorEventOfThatTypeFallsBackToCreatingOne() {
        // Deterministic correction evidence present ("Actually...") but
        // nothing exists yet to correct — must gracefully create the
        // record rather than silently doing nothing.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("SPARK_PLUG_CHANGE", "CONFIRMED_COMPLETED", 15000, true)));
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "SPARK_PLUG_CHANGE", 15000.0, null, null))
                .thenReturn(Optional.empty());
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("SPARK_PLUG_CHANGE"), eq(15000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, I replaced the spark plugs at 15,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("SPARK_PLUG_CHANGE"), eq(15000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void unflaggedRepeatOfTheSameServiceTypeIsNeverAutoTreatedAsACorrection() {
        // Reproduces the reported bug exactly: a rider recalling a real,
        // separate historical oil change (isCorrection left false, or the
        // model simply didn't set it) must NEVER be silently reinterpreted
        // as a correction of the previous one just because it's the same
        // service type, the same day, or a different mileage — a rider
        // can have multiple real oil changes in their history.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I also changed the oil at 19,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void unflaggedChainLubeRepeatIsNeverAutoMerged() {
        // Chain lube genuinely can happen twice in one day (after a ride,
        // after a wash) — must never be auto-collapsed into a single row.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CHAIN_LUBE", "CONFIRMED_COMPLETED", 19500, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_LUBE"), eq(19500.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I lubricated the chain again at 19,500 km after washing the bike.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_LUBE"), eq(19500.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void explicitCorrectionFlagStillUpdatesTheExistingEventInPlace() {
        // Deterministic correction evidence in the rider's own message
        // ("Actually... not...") drives the decision now, independent of
        // whatever the model's own isCorrection flag says — still must
        // work correctly end to end.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, true)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, it was 19,000 km, not 20,000.");

        verify(maintenanceRepository).correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null);
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected"));
    }

    @Test
    void modelSaysIsCorrectionTrueWithNoCurrentTurnEvidenceIsNormalizedToANewEvent() {
        // The exact live-QA finding this guard exists for: oil @ 20,000
        // already exists; the rider's current message is a plain new
        // statement with zero correction language, but the model itself
        // (incorrectly) sets isCorrection=true. The backend must not
        // trust that flag alone — this must CREATE a new 24,000 km event,
        // never overwrite/correct the 20,000 km one.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 24000, true)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the engine oil at 24,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void newLowerMileageStatementForAnExistingServiceTypeIsNeverTreatedAsCorrection() {
        // Section 1 of the request: "a lower mileage entered later is NOT
        // a correction." oil @ 24,000 already exists; the rider later
        // recalls oil @ 22,000 with no correction language — both must
        // persist as independent rows.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 22000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(22000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "At 22,000 km I changed the oil.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(22000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void newLowerMileageStatementForAirFilterIsNeverTreatedAsCorrection() {
        // Same as above for a different service type — air filter @
        // 23,000 already exists; a later, lower 22,000 km mention must
        // also persist as its own row rather than overwriting it.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("AIR_FILTER_CHANGE", "CONFIRMED_COMPLETED", 22000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(22000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the air filter at 22,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(22000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void shortContextualCorrectionAfterAnExplicitConfirmQuestionIsTreatedAsACorrection() {
        // Section 3: "No, 19,000." only means anything as a correction
        // because Repair's own immediately preceding message explicitly
        // asked the rider to confirm the 20,000 km value. The model's own
        // isCorrection flag is false here on purpose — the deterministic
        // context-aware evidence path must still force the correction.
        when(sessionRepository.recentMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                new MotoMessageDto(10L, "user", "I changed the oil at 20,000 km.", null, OffsetDateTime.now()),
                new MotoMessageDto(11L, "assistant", "You said the oil was changed at 20,000 km. Is that correct?", null, OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "No, 19,000.");

        verify(maintenanceRepository).correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null);
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected"));
    }

    @Test
    void explicitCorrectionLanguageForcesCorrectionEvenWhenTheModelFlagSaysFalse() {
        // The other direction of the guard: the model says
        // isCorrection=false, but the rider's own current message
        // contains unambiguous correction language ("Actually... not...").
        // Deterministic evidence must still drive the correction — the
        // backend owns this decision, not the model's flag either way.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, it was 19,000 km, not 20,000.");

        verify(maintenanceRepository).correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null);
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected"));
    }

    @Test
    void correctionNamingAnExplicitOldValueTargetsTheEventAtThatMileageNotTheMostRecentlyInsertedOne() {
        // Reproduces exactly the real-world data-corruption bug found in
        // live usage: oil events already exist at 20,000 (1st inserted)
        // and 22,000 (2nd/most-recently inserted, a genuinely separate,
        // later event). "Actually, the oil change at 20,000 km was at
        // 19,000 km" names 20,000 as the value being corrected — this
        // MUST update the 20,000 km event specifically via
        // correctEventAtMileage, never fall through to correctLatestEvent
        // (which would silently overwrite the unrelated 22,000 km event
        // instead, exactly as it did in production).
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 20000.0, 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, the oil change at 20,000 km was at 19,000 km.");

        verify(maintenanceRepository).correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 20000.0, 19000.0, null, null);
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected"));
    }

    @Test
    void correctionFallsBackToLatestInsertedEventWhenNoOldValueIsMatchedAtThatMileage() {
        // The rider names an old value, but no event actually sits at
        // that mileage (e.g. a typo, or the rider misremembered) —
        // correctEventAtMileage correctly finds nothing, and the code
        // must still fall back to correctLatestEvent rather than losing
        // the correction entirely.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 20000.0, 19000.0, null, null))
                .thenReturn(Optional.empty());
        when(maintenanceRepository.correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, the oil change at 20,000 km was at 19,000 km.");

        verify(maintenanceRepository).correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 20000.0, 19000.0, null, null);
        verify(maintenanceRepository).correctLatestEvent(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null);
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected"));
    }

    @Test
    void correctionResponseReflectsPostWriteCanonicalStateNotThePreWriteModelBelief() {
        // Reproduces the exact reported bug: existing history 19,000 and
        // 22,000; the rider corrects 22,000 -> 21,500. The model's own
        // contextUsed still claims the pre-write "19,000 km and 22,000
        // km" — the final response must be rebuilt from POST-write
        // canonical state (19,000 and 21,500), and must NOT retain
        // 22,000 as active history.
        GarageVehicleDto vehicleAt25kA = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25kA));
        when(maintenanceRepository.listForOwnedVehicle(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(
                List.of(
                        new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now()),
                        new dev.repair.api.garage.MaintenanceEventDto(2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat", OffsetDateTime.now())
                ),
                List.of(
                        new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now()),
                        new dev.repair.api.garage.MaintenanceEventDto(2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 21500.0, null, null, "chat", OffsetDateTime.now())
                )
        );
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"Corrected the previously recorded 22,000 km event to 21,500 km.",
                 "confirmedFacts":[],"contextUsed":["previously recorded engine oil changes at 19,000 km and 22,000 km"],
                 "followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[42],%s}
                """.formatted(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 21500, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 21500.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 22000.0, 21500.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, the oil change at 22,000 km was at 21,500 km.");

        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected") && a.odometerKm().equals(21500.0));
        assertThat(result.answer().contextUsed()).anyMatch(c -> c.contains("19,000 km") && c.contains("21,500 km"));
        assertThat(result.answer().contextUsed()).noneMatch(c -> c.contains("22,000"));
    }

    @Test
    void reversingACorrectionAgainReflectsTheNewPostWriteStateNotTheIntermediateValue() {
        // The second half of the same live QA report: correcting AGAIN
        // (21,500 -> 22,000) must show 19,000/22,000 in the final
        // response, with no trace of the now-superseded 21,500.
        GarageVehicleDto vehicleAt25kB = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25kB));
        when(maintenanceRepository.listForOwnedVehicle(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(
                List.of(
                        new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now()),
                        new dev.repair.api.garage.MaintenanceEventDto(2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 21500.0, null, null, "chat", OffsetDateTime.now())
                ),
                List.of(
                        new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now()),
                        new dev.repair.api.garage.MaintenanceEventDto(2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat", OffsetDateTime.now())
                )
        );
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"Corrected the previously recorded 21,500 km event to 22,000 km.",
                 "confirmedFacts":[],"contextUsed":["previously recorded engine oil changes at 19,000 km and 21,500 km"],
                 "followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[42],%s}
                """.formatted(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 22000, false)));
        var correctedEvent = new dev.repair.api.garage.MaintenanceEventDto(
                2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat", OffsetDateTime.now());
        when(maintenanceRepository.correctEventAtMileage(GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 21500.0, 22000.0, null, null))
                .thenReturn(Optional.of(correctedEvent));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "Actually, I meant 22,000 km, not 21,500 km.");

        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_corrected") && a.odometerKm().equals(22000.0));
        assertThat(result.answer().contextUsed()).anyMatch(c -> c.contains("19,000 km") && c.contains("22,000 km"));
        assertThat(result.answer().contextUsed()).noneMatch(c -> c.contains("21,500"));
    }

    @Test
    void successfulOdometerUpdateResponseReflectsPostWriteValueNotThePreWriteOne() {
        // Same principle for the odometer, not just maintenance events —
        // the fix must not be special-cased to engine oil.
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"Noted your current odometer.",
                 "confirmedFacts":[],"contextUsed":["Current odometer: 20,000 km"],
                 "followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[42],%s}
                """.formatted(odometerProposal("CONFIRMED_COMPLETED", 25000)));
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 25000.0)).thenReturn(Optional.of(25000.0));

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "my bike is 25,000 km now");

        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated") && a.odometerKm().equals(25000.0));
        assertThat(result.answer().contextUsed()).anyMatch(c -> c.contains("25,000 km"));
        assertThat(result.answer().contextUsed()).noneMatch(c -> c.contains("20,000"));
    }

    @Test
    void successfulCreateResponseAppendsCanonicalHistoryForThatServiceType() {
        // A plain create (no correction involved) must also end up with
        // an accurate canonical "Your bike" line reflecting everything
        // now on record for that service type, not just what the model
        // happened to mention pre-write.
        GarageVehicleDto vehicleAt25kC = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25kC));
        when(maintenanceRepository.listForOwnedVehicle(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(
                List.of(new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now())),
                List.of(
                        new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 19000.0, null, null, "chat", OffsetDateTime.now()),
                        new dev.repair.api.garage.MaintenanceEventDto(2L, GARAGE_VEHICLE_ID, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat", OffsetDateTime.now())
                )
        );
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 22000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(22000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 22,000 km.");

        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
        assertThat(result.answer().contextUsed()).anyMatch(c -> c.contains("19,000 km") && c.contains("22,000 km"));
    }

    @Test
    void rejectedMutationNeverAppearsInCanonicalYourBikeEvenWithPostWriteReconciliationActive() {
        // A rejected proposal must still never surface as canonical
        // state, now that successful writes also rebuild contextUsed —
        // the two code paths (reject-scrub vs. succeed-rebuild) must not
        // interfere with each other.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"You've changed the engine oil at 30,000 km. Noted.",
                 "confirmedFacts":[],"contextUsed":["You changed engine oil at 30,000 km"],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42],%s}
                """.formatted(maintenanceProposal("CONFIRMED_COMPLETED", 30000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 30,000 km.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        verify(maintenanceRepository, never()).correctEventAtMileage(anyLong(), any(), anyDouble(), any(), any(), any());
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.answer().contextUsed()).noneMatch(c -> c.contains("30,000"));
    }

    @Test
    void threeGenuineHistoricalEventsOfTheSameTypeAreAllRetained() {
        // Section 7/8/23-B of the QA pass: oil @ 20,000, then oil @
        // 24,000, then oil @ 22,000 (out of chronological order) must
        // all persist as three separate rows — none of them corrections.
        // Current odometer raised to 25,000 (matching the real QA
        // scenario) so none of these historical mileages is rejected as
        // an impossible future event.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 20000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(20000.0), any(), any(), eq("chat")))
                .thenReturn(1L);
        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 20,000 km.");

        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 24000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat")))
                .thenReturn(2L);
        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 24,000 km.");

        stubGeneration(answerJson(maintenanceProposal("ENGINE_OIL_CHANGE", "CONFIRMED_COMPLETED", 22000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(22000.0), any(), any(), eq("chat")))
                .thenReturn(3L);
        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "At 22,000 km I changed the oil.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(20000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(22000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void multipleConfirmedMaintenanceActionsInOneMessageCreateSeparateEvents() {
        // "I changed the oil and oil filter at 24,000 km" style compound
        // statement — proposedMaintenanceEvents is a list; two
        // independently confirmed services must become two persisted
        // events, never collapsed into one or silently dropped.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposals(
                maintenanceEvent("ENGINE_OIL_CHANGE", 15000, "CONFIRMED_COMPLETED", false),
                maintenanceEvent("OIL_FILTER_CHANGE", 15000, "CONFIRMED_COMPLETED", false)
        )));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(15000.0), any(), any(), eq("chat")))
                .thenReturn(1L);
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(15000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil and oil filter at 15,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(15000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(15000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).hasSize(2);
        assertThat(result.actionsTaken()).extracting("serviceType").containsExactlyInAnyOrder("ENGINE_OIL_CHANGE", "OIL_FILTER_CHANGE");
    }

    @Test
    void maintenanceEventMileageExceedingCurrentOdometerIsRejected() {
        // "I changed the oil at 30,000 km" while the bike is really at
        // 20,000 km (setUp's stub) — a service cannot have happened in
        // the rider's future. Must never be silently persisted.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CONFIRMED_COMPLETED", 30000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 30,000 km.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void rejectedFutureMileageEventNeverPollutesTheFinalAnswer() {
        // Section 1/2 of the QA pass: even though the model's own summary
        // and contextUsed claimed the (invalid) 30,000 km event as if it
        // were real, the FINAL answer returned to the rider — and, more
        // importantly, PERSISTED as this turn's message content, which is
        // what gets replayed into later turns' history — must never say
        // or imply the event happened. When this rejection is the only
        // thing the turn proposed, the whole answer is replaced with an
        // honest clarification instead of the model's persistence-claiming
        // prose.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"You've changed the engine oil at 30,000 km. Noted.",
                 "confirmedFacts":[],"contextUsed":["You changed engine oil at 30,000 km"],"followUpQuestions":[],
                 "safeChecks":[],"cautions":[],"sourceChunkIds":[42],%s}
                """.formatted(maintenanceProposal("CONFIRMED_COMPLETED", 30000)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 30,000 km.");

        assertThat(result.answer().answerType()).isEqualTo("clarification");
        assertThat(result.answer().summary()).doesNotContain("You've changed the engine oil at 30,000 km");
        assertThat(result.answer().summary()).contains("25,000 km").contains("30,000 km");
        assertThat(result.answer().contextUsed()).isEmpty();
        assertThat(result.answer().followUpQuestions()).isNotEmpty();
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void rejectedValueIsScrubbedFromContextUsedEvenWhenOtherActionsSucceedThisTurn() {
        // A mixed turn: one valid action executes, one proposal is
        // rejected (out of range) — the rejected value's context mention
        // must still be scrubbed, even though the summary itself isn't
        // replaced wholesale (something real did happen this turn).
        stubRetrieval();
        stubGeneration("""
                {"answerType":"guidance","summary":"Noted the oil change and the odometer.",
                 "confirmedFacts":[],"contextUsed":["Engine oil changed at 19,000 km","Odometer reads 999,999 km"],
                 "followUpQuestions":[],"safeChecks":[],"cautions":[],"sourceChunkIds":[42],
                 %s,%s}
                """.formatted(
                maintenanceProposal("CONFIRMED_COMPLETED", 19000),
                odometerProposal("CONFIRMED_COMPLETED", 999999)
        ));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 19,000 km. Also my odometer reads 999,999 km now.");

        assertThat(result.answer().contextUsed()).containsExactly("Engine oil changed at 19,000 km");
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void spuriousMaintenanceEventNotMentionedThisTurnIsRejected() {
        // Section 10 of the QA pass: the rider's current turn only
        // mentions the engine oil and oil filter — an air-filter proposal
        // whose mileage comes from OLDER stored context (never restated
        // this turn) must be rejected, not silently re-persisted/"corrected".
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposals(
                maintenanceEvent("ENGINE_OIL_CHANGE", 24000, "CONFIRMED_COMPLETED", false),
                maintenanceEvent("OIL_FILTER_CHANGE", 24000, "CONFIRMED_COMPLETED", false),
                maintenanceEvent("AIR_FILTER_CHANGE", 23000, "CONFIRMED_COMPLETED", false)
        )));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat")))
                .thenReturn(1L);
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(24000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the engine oil and oil filter at 24,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(24000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(24000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), any(), any(), any(), any());
        verify(maintenanceRepository, never()).correctLatestEvent(anyLong(), eq("AIR_FILTER_CHANGE"), any(), any(), any());
        assertThat(result.actionsTaken()).hasSize(2);
        assertThat(result.actionsTaken()).noneMatch(a -> "AIR_FILTER_CHANGE".equals(a.serviceType()));
    }

    @Test
    void spuriousOdometerUpdateNotStatedThisTurnIsRejected() {
        // Section 11: answering an oil-filter follow-up with just a
        // mileage must never ALSO re-propose the (unchanged, already
        // current) odometer value just because it's visible in context.
        stubRetrieval();
        stubGeneration(answerJson(
                maintenanceProposal("OIL_FILTER_CHANGE", "CONFIRMED_COMPLETED", 12000, false)
                        + "," + odometerProposal("CONFIRMED_COMPLETED", 20000)
        ));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(12000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "at 12,000 km");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(12000.0), any(), any(), eq("chat"));
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void sameValueOdometerUpdateIsRejectedEvenWhenThePreviousAssistantTurnMentionedTheValue() {
        // Live QA finding: a purely historical maintenance statement
        // ("I changed brake fluid at 17,500 km.") must never ALSO
        // surface an "Odometer updated · 25,000 km" badge just because
        // the odometer is unchanged and the PREVIOUS assistant turn
        // happened to mention "25,000 km" as background context (which
        // it does almost every turn). Unlike a real value change, a
        // same-value "update" gets no benefit of the doubt from
        // previousAssistantContext — only the rider's own current
        // message repeating the number counts.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        when(sessionRepository.recentMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                new MotoMessageDto(1L, "assistant", "Your current odometer is 25,000 km.", null, OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(
                maintenanceProposal("BRAKE_FLUID_CHANGE", "CONFIRMED_COMPLETED", 17500, false)
                        + "," + odometerProposal("CONFIRMED_COMPLETED", 25000)
        ));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(17500.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the brake fluid at 17,500 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(17500.0), any(), any(), eq("chat"));
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void duplicateMaintenanceEventAtTheExactSameMileageAsAnExistingOneIsNeverCreatedTwice() {
        // Live QA finding: a confusing intervening message caused the
        // model to re-propose an already-logged BRAKE_FLUID_CHANGE at
        // its exact existing mileage (17,500 km) — this must never
        // create a second identical row.
        when(maintenanceRepository.listForOwnedVehicle(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(
                List.of(new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "BRAKE_FLUID_CHANGE", 17500.0, null, null, "chat", OffsetDateTime.now()))
        );
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("BRAKE_FLUID_CHANGE", "CONFIRMED_COMPLETED", 17500, false)));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the brake fluid at 17,500 km.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).noneMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void sameServiceTypeAtADifferentMileageIsNeverTreatedAsADuplicate() {
        // The duplicate guard must only catch an EXACT mileage repeat —
        // a genuinely different mileage for the same service type is a
        // completely independent, real event and must still be created.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        when(maintenanceRepository.listForOwnedVehicle(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(
                List.of(new dev.repair.api.garage.MaintenanceEventDto(1L, GARAGE_VEHICLE_ID, "BRAKE_FLUID_CHANGE", 17500.0, null, null, "chat", OffsetDateTime.now()))
        );
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("BRAKE_FLUID_CHANGE", "CONFIRMED_COMPLETED", 21000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(21000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the brake fluid at 21,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(21000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void relativeMileageProposalRemainsGroundedEvenThoughTheAbsoluteValueIsntInTheMessage() {
        // The grounding check must not regress the relative-mileage
        // feature: "2,000 km ago" never contains "23000" verbatim, but is
        // still grounded via the currentOdometer-minus-delta arithmetic.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("AIR_FILTER_CHANGE", "CONFIRMED_COMPLETED", 18000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(18000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the air filter 2,000 km ago.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(18000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
    }

    @Test
    void duplicateIdenticalProposalsInOneModelResponseProduceOnlyOneEvent() {
        // Idempotency floor: if the model's own single response somehow
        // lists the exact same proposal twice, only one DB row results.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposals(
                maintenanceEvent("ENGINE_OIL_CHANGE", 19000, "CONFIRMED_COMPLETED", false),
                maintenanceEvent("ENGINE_OIL_CHANGE", 19000, "CONFIRMED_COMPLETED", false)
        )));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the oil at 19,000 km.");

        verify(maintenanceRepository, times(1)).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).hasSize(1);
    }

    @Test
    void maintenanceEventAtTheSameTurnsNewOdometerValueIsNotTreatedAsFutureInconsistent() {
        // "I'm now at 25,000, I just changed the oil at 25,000" — the
        // maintenance event's mileage matches a NEW current-odometer value
        // proposed in the SAME turn, not the stale pre-turn value (stub
        // currentOdometerKm is 20,000). Must be accepted, not rejected as
        // an impossible future event.
        stubRetrieval();
        stubGeneration(answerJson(
                maintenanceProposal("CONFIRMED_COMPLETED", 25000) + "," + odometerProposal("CONFIRMED_COMPLETED", 25000)
        ));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(25000.0), any(), any(), eq("chat")))
                .thenReturn(1L);
        when(garageVehicleRepository.updateOdometerIfOwned(VISITOR_ID, GARAGE_VEHICLE_ID, 25000.0)).thenReturn(Optional.of(25000.0));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I'm now at 25,000 km, I just changed the oil at 25,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("ENGINE_OIL_CHANGE"), eq(25000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("odometer_updated"));
    }

    @Test
    void neverDoneStatementProposesNoMaintenanceEvent() {
        // "I have never replaced the spark plugs" must not create a fake
        // replacement event — there is nothing to record.
        stubRetrieval();
        stubGeneration(answerJson(""));

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I have never replaced the spark plugs.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.actionsTaken()).isEmpty();
    }

    @Test
    void systemPromptIncludesRelativeMileageAndNegativeStatementGuidance() {
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "anything");

        org.mockito.ArgumentCaptor<List<Map<String, String>>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(captor.capture(), any(), any(), anyInt());
        String systemContent = captor.getValue().get(0).get("content").replaceAll("\\s+", " ");
        assertThat(systemContent).contains("RELATIVE MAINTENANCE MILEAGE");
        assertThat(systemContent).contains("eventMileage = currentOdometer - relativeDistance");
        assertThat(systemContent).contains("NEGATIVE / \"NEVER DONE\" STATEMENTS");
        assertThat(systemContent).contains("REUSING A JUST-ESTABLISHED MILEAGE");
        assertThat(systemContent).contains("permission-asking follow-up question");
        assertThat(systemContent).contains("IDENTICALLY to every service type in the taxonomy");
        assertThat(systemContent).contains("never infer or imply that an adjustment also happened");
    }

    @Test
    void everyServiceTypeInTheTaxonomyPersistsThroughTheSameGenericPath() {
        // Live QA found several less-common service types (spark plugs,
        // brake fluid, coolant, valve clearance, air filter, chain
        // adjustment) narrated in chat but never persisted, while others
        // (engine oil, oil filter, chain lube, tires, battery) worked
        // fine — even though the backend's create/correction path never
        // branches on service type. BRAKE_FLUID_CHANGE stands in here as
        // a representative previously-under-performing type, proving the
        // existing generic path (unchanged) persists it exactly like any
        // other CONFIRMED_COMPLETED proposal once the model proposes it.
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("BRAKE_FLUID_CHANGE", "CONFIRMED_COMPLETED", 12000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(12000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the brake fluid at 12,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(12000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("BRAKE_FLUID_CHANGE"));
    }

    private GarageVehicleDto vehicleAtOdometer(double odometerKm) {
        return new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, odometerKm,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void deterministicFallbackPersistsAirFilterWhenTheModelProposesNothing() {
        // Exact live-QA regression: the model proposed NOTHING at all
        // this turn (answerJson("")) for a plainly stated, explicit
        // completed action — the deterministic fallback must derive and
        // persist it through the same existing path.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(50000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(44000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I replaced the air filter at 44,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(44000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("AIR_FILTER_CHANGE"));
    }

    @Test
    void deterministicFallbackGenericallyCoversSparkPlugTooNotJustTheFourOriginallyReportedTypes() {
        // Proves the fallback is now generic across the taxonomy (derived
        // from the existing SERVICE_TYPE_LABELS alias map), not a
        // hardcoded allowlist of the 4 originally-reported types.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(40000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("SPARK_PLUG_CHANGE"), eq(30000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the spark plug at 30,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("SPARK_PLUG_CHANGE"), eq(30000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("SPARK_PLUG_CHANGE"));
    }

    @Test
    void deterministicFallbackPersistsBrakeFluidWhenTheModelProposesNothing() {
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(40000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(36000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the brake fluid at 36,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("BRAKE_FLUID_CHANGE"), eq(36000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("BRAKE_FLUID_CHANGE"));
    }

    @Test
    void deterministicFallbackPersistsCoolantWhenTheModelProposesNothing() {
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(40000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("COOLANT_CHANGE"), eq(33000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I changed the coolant at 33,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("COOLANT_CHANGE"), eq(33000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("COOLANT_CHANGE"));
    }

    @Test
    void deterministicFallbackPersistsValveClearanceCheckedNeverAnAdjustmentType() {
        // "checked" must map only to VALVE_CLEARANCE_CHECK — there is no
        // separate "adjusted" service type in the taxonomy to confuse it
        // with, so this also guards against ever inventing one.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(50000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("VALVE_CLEARANCE_CHECK"), eq(43000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I checked the valve clearance at 43,000 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("VALVE_CLEARANCE_CHECK"), eq(43000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("VALVE_CLEARANCE_CHECK"));
    }

    @Test
    void chainAdjustmentStillPersistsViaTheModelsOwnProposalUnaffectedByTheFallback() {
        // Live QA reported this ONE statement out of the five already
        // worked (the model proposes it directly) — a plain regression
        // guard proving the new fallback logic doesn't interfere with it.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(50000.0)));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CHAIN_ADJUSTMENT", "CONFIRMED_COMPLETED", 49700, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_ADJUSTMENT"), eq(49700.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I adjusted the chain at 49,700 km.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_ADJUSTMENT"), eq(49700.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("CHAIN_ADJUSTMENT"));
    }

    @Test
    void deterministicFallbackNeverFiresWhenTheModelAlreadyProposedThatType() {
        // If the model DID propose the type this turn, the fallback must
        // never add a second, duplicate proposal for it.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(50000.0)));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("AIR_FILTER_CHANGE", "CONFIRMED_COMPLETED", 44000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(44000.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I replaced the air filter at 44,000 km.");

        verify(maintenanceRepository, times(1)).createEvent(eq(GARAGE_VEHICLE_ID), eq("AIR_FILTER_CHANGE"), eq(44000.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).filteredOn(a -> a.serviceType() != null && a.serviceType().equals("AIR_FILTER_CHANGE")).hasSize(1);
    }

    @Test
    void deterministicFallbackNeverFiresForNegativeOrHedgedStatements() {
        // "never" + a mileage number in the same message must never be
        // fabricated into a confirmed event.
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAtOdometer(50000.0)));
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID,
                "I have never checked the valve clearance, even though I'm at 44,000 km now.");

        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void chainAdjustmentReusingTheJustEstablishedChainLubeMileageIsGroundedAndPersisted() {
        // "REUSING A JUST-ESTABLISHED MILEAGE" worked example: the rider
        // lubed the chain 300 km ago (current odometer 25,000 -> 24,700,
        // confirmed last turn), then says the adjustment happened at the
        // same time — no number in THIS turn's text, but the immediately
        // preceding assistant turn confirmed 24,700 km, so it's grounded.
        GarageVehicleDto vehicleAt25k = new GarageVehicleDto(
                GARAGE_VEHICLE_ID, MODEL_ID, "Yamaha", "MT-07", 2025, null, null, 25000.0,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(garageVehicleRepository.find(VISITOR_ID, GARAGE_VEHICLE_ID)).thenReturn(Optional.of(vehicleAt25k));
        when(sessionRepository.recentMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                new MotoMessageDto(1L, "user", "I lubed the chain 300 km ago.", null, OffsetDateTime.now()),
                new MotoMessageDto(2L, "assistant", "Noted chain lubrication at 24,700 km.", null, OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("CHAIN_ADJUSTMENT", "CONFIRMED_COMPLETED", 24700, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_ADJUSTMENT"), eq(24700.0), any(), any(), eq("chat")))
                .thenReturn(1L);

        MotoChatTurnResult result = service.handleUserMessage(
                VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "I adjusted the chain at the same time I last lubed it.");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("CHAIN_ADJUSTMENT"), eq(24700.0), any(), any(), eq("chat"));
        assertThat(result.actionsTaken()).anyMatch(a -> a.type().equals("maintenance_event_created") && a.serviceType().equals("CHAIN_ADJUSTMENT"));
    }

    @Test
    void assistantHistoryReconstructionIncludesThePreviousTurnsFollowUpQuestion() {
        // The root cause of "contextual yes doesn't reach Garage": the
        // stored assistant message content is only answer.summary(), which
        // never contained the follow-up question. Without restoring it
        // into the replayed history, the model has nothing unambiguous to
        // resolve a bare "yes" against.
        String priorAnswerJson = answerJson("")
                .replace("\"followUpQuestions\":[]", "\"followUpQuestions\":[\"Did you replace the oil filter at 19,000 km as well?\"]");
        when(sessionRepository.recentMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                new MotoMessageDto(10L, "user", "I actually changed the oil at 19,000 km.", null, OffsetDateTime.now()),
                new MotoMessageDto(11L, "assistant", "Noted, oil change corrected to 19,000 km.", priorAnswerJson, OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("OIL_FILTER_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "yes");

        org.mockito.ArgumentCaptor<List<Map<String, String>>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(captor.capture(), any(), any(), anyInt());
        String allContent = captor.getValue().stream().map(m -> m.get("content")).reduce("", String::concat);
        assertThat(allContent).contains("Did you replace the oil filter at 19,000 km as well?");
    }

    @Test
    void contextualYesAfterOilFilterFollowUpCreatesOilFilterEventNotAirFilter() {
        // Full-turn version of the above: given the restored follow-up
        // context, a model that correctly resolves "yes" into an
        // OIL_FILTER_CHANGE proposal must result in exactly that
        // persisted event — and AIR_FILTER_CHANGE must never be touched.
        String priorAnswerJson = answerJson("")
                .replace("\"followUpQuestions\":[]", "\"followUpQuestions\":[\"Did you replace the oil filter at 19,000 km as well?\"]");
        when(sessionRepository.recentMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                new MotoMessageDto(11L, "assistant", "Noted, oil change corrected to 19,000 km.", priorAnswerJson, OffsetDateTime.now())
        ));
        stubRetrieval();
        stubGeneration(answerJson(maintenanceProposal("OIL_FILTER_CHANGE", "CONFIRMED_COMPLETED", 19000, false)));
        when(maintenanceRepository.createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(19000.0), any(), any(), eq("chat")))
                .thenReturn(2L);

        MotoChatTurnResult result = service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "yes");

        verify(maintenanceRepository).createEvent(eq(GARAGE_VEHICLE_ID), eq("OIL_FILTER_CHANGE"), eq(19000.0), any(), any(), eq("chat"));
        verify(maintenanceRepository, never()).createEvent(anyLong(), eq("AIR_FILTER_CHANGE"), any(), any(), any(), any());
        assertThat(result.actionsTaken()).anyMatch(a ->
                a.type().equals("maintenance_event_created") && "OIL_FILTER_CHANGE".equals(a.serviceType()));
    }

    @Test
    void systemPromptForbidsInventingLastServiceFromASchedule() {
        // Reproduces the reported bug: a hypothetical-mileage question must
        // never surface a fabricated "last replaced at X km (implied from
        // schedule)" claim. This asserts the defensive instruction is
        // actually present in what's sent to the model (the LLM's own
        // output can't be asserted on in a mocked unit test).
        stubRetrieval();
        stubGeneration(answerJson(""));

        service.handleUserMessage(VISITOR_ID, SESSION_ID, GARAGE_VEHICLE_ID, "If I were at 30,000 km, what maintenance would be due?");

        org.mockito.ArgumentCaptor<List<Map<String, String>>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(captor.capture(), any(), any(), anyInt());
        String systemContent = captor.getValue().get(0).get("content").replaceAll("\\s+", " ");
        assertThat(systemContent).contains("SCHEDULE VS ACTUAL HISTORY");
        assertThat(systemContent).contains("Never synthesize, back-calculate, or guess a \"last service\" mileage");
    }
}
