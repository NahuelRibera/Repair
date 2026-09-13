package dev.repair.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.catalogue.CatalogueRepository;
import dev.repair.api.catalogue.SpecsDto;
import dev.repair.api.catalogue.VariantDetailDto;
import dev.repair.api.catalogue.VariantDto;
import dev.repair.api.config.ChatProperties;
import dev.repair.api.config.OpenAiProperties;
import dev.repair.api.conversation.MessageDto;
import dev.repair.api.conversation.SessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the RAG orchestration logic. No real OpenAI call is ever
 * made: OpenAiClient is mocked, so these run without OPENAI_API_KEY and
 * without network access. What's actually under test is the *policy*
 * around OpenAI, not OpenAI itself — the missing-key short-circuit, the
 * empty-retrieval short-circuit, failure-to-fallback-answer mapping, and
 * server-side citation validation that drops chunk ids OpenAI hallucinated
 * outside the retrieved evidence set.
 */
@ExtendWith(MockitoExtension.class)
class ChatOrchestrationServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long VARIANT_ID = 23079L;
    private static final long MODEL_ID = 2243L;

    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private RagRunRepository ragRunRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private CatalogueRepository catalogueRepository;

    private ChatOrchestrationService service;
    private ChatProperties chatProperties;

    @BeforeEach
    void setUp() {
        chatProperties = new ChatProperties(20, 2000, 6, 900, 1200);
        VariantDetailDto variant = new VariantDetailDto(
                new VariantDto(VARIANT_ID, MODEL_ID, "BMW 3 Series (E90) 320d 6MT RWD (177 HP)", "slug",
                        2008, 2011, "Diesel", "Rear Wheel Drive", "6-speed manual", "unreviewed"),
                "BMW", "BMW 3 Series Sedan",
                new SpecsDto(null, null, null, null, null, null, null, null, null, null, null, null, null),
                true
        );
        when(catalogueRepository.findVariantDetail(VARIANT_ID)).thenReturn(Optional.of(variant));
        when(ragRunRepository.start(any(), eq(SESSION_ID), eq(VARIANT_ID), any())).thenReturn(100L);
        when(ragRunRepository.findDebugByRequestId(any())).thenAnswer(inv -> new RagRunDebugDto(
                inv.getArgument(0).toString(), VARIANT_ID, null, null, null, null, null, null, "ok", null
        ));
        when(sessionRepository.insertMessage(eq(SESSION_ID), any(), any(), any())).thenReturn(1L);

        service = new ChatOrchestrationService(
                chatProperties,
                new OpenAiProperties("", "gpt-4.1-mini", "text-embedding-3-small", 1536, 30, 2),
                openAiClient, retrievalService, ragRunRepository, sessionRepository, catalogueRepository,
                JsonMapper.builder().build()
        );
    }

    @Test
    void missingApiKeyShortCircuitsWithoutCallingOpenAi() {
        ChatTurnResult result = service.handleUserMessage(SESSION_ID, VARIANT_ID, "window won't go up");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        assertThat(result.evidence()).isEmpty();
        verify(openAiClient, never()).embed(any());
        verify(openAiClient, never()).generateStructured(any(), any(), any(), anyInt());
        verify(ragRunRepository).complete(
                eq(100L), any(), any(), any(), any(), any(), any(), any(), any(), eq("missing_key"), any()
        );
    }

    @Test
    void emptyRetrievalSkipsGenerationAndReturnsInsufficientEvidence() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f, 0.2f}));
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of());

        ChatTurnResult result = configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        verify(openAiClient, never()).generateStructured(any(), any(), any(), anyInt());
        verify(ragRunRepository).complete(
                eq(100L), any(), any(), any(), any(), any(), any(), any(), any(), eq("empty_retrieval"), any()
        );
    }

    @Test
    void embeddingFailureProducesGracefulFallbackNotAnException() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingFailure("timeout", "boom"));

        ChatTurnResult result = configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        verify(retrievalService, never()).hybridSearch(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void generationFailureIsRecordedWithItsProviderStatus() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of(sampleChunk(42L)));
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationFailure("rate_limited", "429"));

        ChatTurnResult result = configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        verify(ragRunRepository).complete(
                eq(100L), any(), any(), any(), any(), any(), any(), any(), any(), eq("rate_limited"), eq("429")
        );
    }

    @Test
    void hallucinatedCitationOutsideRetrievedEvidenceIsDroppedNotTrusted() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of(sampleChunk(42L)));
        // The model cites chunk 42 (real) and chunk 999 (never retrieved — hallucinated).
        String modelJson = """
                {"answerType":"guidance","summary":"Check the fuse first.",
                 "confirmedSymptoms":[],"followUpQuestions":[],
                 "hypotheses":[{"description":"Blown fuse","reasoning":"Matches evidence",
                     "evidenceChunkIds":[42,999]}],
                 "safeChecks":[],"cautions":[],"missingInformation":[],"sourceChunkIds":[42,999]}
                """;
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationSuccess(modelJson, 100, 50));

        ChatTurnResult result = configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        assertThat(result.answer().sourceChunkIds()).containsExactly(42L);
        assertThat(result.answer().hypotheses()).hasSize(1);
        assertThat(result.answer().hypotheses().get(0).evidenceChunkIds()).containsExactly(42L);
        verify(ragRunRepository).complete(
                eq(100L), any(), any(), any(), any(), any(), any(), eq(100), eq(50), eq("ok"), any()
        );
    }

    @Test
    void malformedModelOutputIsReportedAsInvalidOutputNotAServerError() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of(sampleChunk(1L)));
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationSuccess("not valid json {{{", 10, 5));

        ChatTurnResult result = configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        assertThat(result.answer().answerType()).isEqualTo("insufficient_evidence");
        verify(ragRunRepository).complete(
                eq(100L), any(), any(), any(), any(), any(), any(), any(), any(), eq("invalid_output"), any()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateContentAcrossChunkIdsIsSentOnlyOnce() {
        var configuredService = serviceWithConfiguredKey();
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        RetrievedChunk original = sampleChunk(1L);
        RetrievedChunk exactDuplicateContent = new RetrievedChunk(
                2L, original.documentId(), original.documentTitle(), original.documentProvenance(),
                original.documentSourceUrl(), original.heading(), original.sectionPath(),
                original.pageNumber(), original.content(), 0.8, 0.4, 0.7
        );
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of(original, exactDuplicateContent));
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationSuccess(minimalValidAnswerJson(), 10, 5));

        configuredService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        ArgumentCaptor<List<Map<String, String>>> inputCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(inputCaptor.capture(), any(), any(), anyInt());
        String evidenceMessage = findEvidenceMessage(inputCaptor.getValue());
        int occurrences = evidenceMessage.split("chunk_id=", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evidenceWordBudgetStopsAddingFurtherChunksOnceExceeded() {
        ChatOrchestrationService tightBudgetService = new ChatOrchestrationService(
                new ChatProperties(20, 2000, 6, 900, 10), // 10-word evidence budget
                new OpenAiProperties("sk-test-key", "gpt-4.1-mini", "text-embedding-3-small", 1536, 30, 2),
                openAiClient, retrievalService, ragRunRepository, sessionRepository, catalogueRepository,
                JsonMapper.builder().build()
        );
        when(openAiClient.embed(any())).thenReturn(new OpenAiClient.EmbeddingSuccess(new float[]{0.1f}));
        String longContent = "word ".repeat(50).strip();
        RetrievedChunk big1 = new RetrievedChunk(1L, 7L, "Doc A", "synthetic_demo", null, "H1", "Doc A > H1", null, longContent, 0.9, 0.5, 0.9);
        RetrievedChunk big2 = new RetrievedChunk(2L, 7L, "Doc B", "synthetic_demo", null, "H2", "Doc B > H2", null, longContent, 0.8, 0.4, 0.8);
        when(retrievalService.hybridSearch(eq(VARIANT_ID), eq(MODEL_ID), any(), any(), anyInt()))
                .thenReturn(List.of(big1, big2));
        when(openAiClient.generateStructured(any(), any(), any(), anyInt()))
                .thenReturn(new OpenAiClient.GenerationSuccess(minimalValidAnswerJson(), 10, 5));

        tightBudgetService.handleUserMessage(SESSION_ID, VARIANT_ID, "anything");

        ArgumentCaptor<List<Map<String, String>>> inputCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).generateStructured(inputCaptor.capture(), any(), any(), anyInt());
        String evidenceMessage = findEvidenceMessage(inputCaptor.getValue());
        // The first (always included) chunk fits; the second would exceed the tiny budget.
        assertThat(evidenceMessage).contains("chunk_id=1");
        assertThat(evidenceMessage).doesNotContain("chunk_id=2");
    }

    private String findEvidenceMessage(List<Map<String, String>> input) {
        return input.stream()
                .map(m -> m.get("content"))
                .filter(content -> content.startsWith("Evidence excerpts"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no evidence message found in model input"));
    }

    private String minimalValidAnswerJson() {
        return """
                {"answerType":"guidance","summary":"ok","confirmedSymptoms":[],
                 "followUpQuestions":[],"hypotheses":[],"safeChecks":[],"cautions":[],
                 "missingInformation":[],"sourceChunkIds":[]}
                """;
    }

    private ChatOrchestrationService serviceWithConfiguredKey() {
        return new ChatOrchestrationService(
                chatProperties,
                new OpenAiProperties("sk-test-key", "gpt-4.1-mini", "text-embedding-3-small", 1536, 30, 2),
                openAiClient, retrievalService, ragRunRepository, sessionRepository, catalogueRepository,
                JsonMapper.builder().build()
        );
    }

    private RetrievedChunk sampleChunk(long chunkId) {
        return new RetrievedChunk(
                chunkId, 7L, "Window troubleshooting", "synthetic_demo", null,
                "Step 1", "Doc > Step 1", null, "Check the fuse first.", 0.9, 0.5, 0.8
        );
    }
}
