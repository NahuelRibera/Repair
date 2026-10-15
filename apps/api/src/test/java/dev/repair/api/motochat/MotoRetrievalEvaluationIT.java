package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval evaluation over the real curated corpus in knowledge/motorcycles/.
 * The corpus is chunked the same way the ingestion pipeline chunks it
 * (heading-v1, see pipelines/embeddings/chunker.py) and loaded into a
 * Testcontainers Postgres without embeddings, so the run needs no OpenAI
 * call and exercises the lexical half of MotoRetrievalService.hybridSearch
 * (the vector pool is empty for chunks without an embedding).
 *
 * Dataset: src/test/resources/retrieval/retrieval-eval-v1.json. Metrics and
 * thresholds are documented in docs/retrieval-evaluation.md.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MotoRetrievalEvaluationIT {

    /** Cutoff for the hit-rate metric. */
    private static final int K = 5;
    /** Result size used for the isolation check, larger than K on purpose. */
    private static final int ISOLATION_LIMIT = 20;

    /** No retrieved chunk may come from another model or outside the selected year. */
    private static final double MIN_ISOLATION = 1.0;
    /** Share of questions whose expected section appears in the top K chunks. */
    private static final double MIN_HIT_RATE_AT_K = 0.80;

    private static final int MAX_WORDS_PER_CHUNK = 220;
    private static final int OVERLAP_WORDS = 30;
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern PART_SUFFIX = Pattern.compile(" \\(part \\d+\\)$");

    private record DocMeta(String modelSlug, int yearFrom, int yearTo, String path) {
    }

    private record EvalCase(String id, String model, int year, String question, String query, String expectedSection) {
    }

    @Autowired
    private MotoRetrievalService retrievalService;
    @Autowired
    private JdbcClient jdbcClient;

    private static boolean loaded;
    private static final Map<String, Long> modelIdBySlug = new HashMap<>();
    private static final Map<Long, DocMeta> docMetaById = new HashMap<>();
    private static final Map<String, Long> docIdByModelAndYear = new HashMap<>();
    private static final Map<Long, Set<String>> sectionsByDocId = new HashMap<>();

    @BeforeAll
    static void resetState() {
        loaded = false;
    }

    private synchronized void ensureCorpusLoaded() throws IOException {
        if (loaded) {
            return;
        }
        modelIdBySlug.clear();
        docMetaById.clear();
        docIdByModelAndYear.clear();
        sectionsByDocId.clear();

        long manufacturer = jdbcClient.sql(
                        "INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "Eval-" + UUID.randomUUID()).param("s", "eval-" + UUID.randomUUID())
                .query(Long.class).single();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(findCorpusRoot())) {
            files = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        for (Path file : files) {
            loadDocument(manufacturer, file);
        }
        loaded = true;
    }

    private void loadDocument(long manufacturer, Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher fm = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL).matcher(text);
        assertThat(fm.find()).as("front matter in %s", file).isTrue();
        String body = text.substring(fm.end());
        int yearFrom = Integer.parseInt(frontMatterValue(fm.group(1), "year_from"));
        int yearTo = Integer.parseInt(frontMatterValue(fm.group(1), "year_to"));
        String modelSlug = file.getParent().getFileName().toString();

        Long modelId = modelIdBySlug.get(modelSlug);
        if (modelId == null) {
            modelId = jdbcClient.sql(
                            "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (:mf, :n, :s) RETURNING id")
                    .param("mf", manufacturer).param("n", frontMatterValue(fm.group(1), "model")).param("s", modelSlug)
                    .query(Long.class).single();
            modelIdBySlug.put(modelSlug, modelId);
        }
        String path = "eval/" + modelSlug + "/" + file.getFileName();
        long docId = jdbcClient.sql(
                        """
                        INSERT INTO motorcycle_knowledge_documents
                            (model_id, year_from, year_to, source_relative_path, content_hash)
                        VALUES (:m, :yf, :yt, :p, :h) RETURNING id
                        """)
                .param("m", modelId).param("yf", yearFrom).param("yt", yearTo)
                .param("p", path).param("h", sha256(text))
                .query(Long.class).single();
        docMetaById.put(docId, new DocMeta(modelSlug, yearFrom, yearTo, path));
        for (int y = yearFrom; y <= yearTo; y++) {
            docIdByModelAndYear.put(modelSlug + "/" + y, docId);
        }

        Set<String> sections = new HashSet<>();
        for (String[] chunk : chunk(body)) {
            String sectionPath = chunk[1];
            String[] parts = sectionPath.isEmpty() ? new String[0] : sectionPath.split(" > ");
            String section = parts.length > 1 ? parts[1] : (parts.length > 0 ? parts[0] : null);
            String subsection = parts.length > 2 ? parts[2] : null;
            if (section != null) {
                sections.add(PART_SUFFIX.matcher(section).replaceFirst(""));
            }
            jdbcClient.sql(
                            """
                            INSERT INTO motorcycle_knowledge_chunks
                                (document_id, section, subsection, category, heading, section_path, content, content_hash)
                            VALUES (:d, :sec, :sub, 'other', :h, :sp, :c, :hash)
                            ON CONFLICT (document_id, content_hash) DO NOTHING
                            """)
                    .param("d", docId).param("sec", section).param("sub", subsection)
                    .param("h", chunk[0]).param("sp", sectionPath).param("c", chunk[2])
                    .param("hash", sha256(chunk[2]))
                    .update();
        }
        sectionsByDocId.put(docId, sections);
    }

    @Test
    void datasetIsWellFormedAndExpectedSectionsExistInTheCorpus() throws IOException {
        ensureCorpusLoaded();
        List<EvalCase> cases = loadCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases.stream().map(EvalCase::model).distinct()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(cases.stream().map(EvalCase::year).distinct()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(cases.stream().map(EvalCase::id).distinct()).hasSameSizeAs(cases);

        for (EvalCase c : cases) {
            Long docId = docIdByModelAndYear.get(c.model() + "/" + c.year());
            assertThat(docId).as("corpus document for case %s (%s %d)", c.id(), c.model(), c.year()).isNotNull();
            assertThat(sectionsByDocId.get(docId))
                    .as("section '%s' of case %s", c.expectedSection(), c.id())
                    .contains(c.expectedSection());
        }
    }

    @Test
    void retrievalKeepsModelAndYearIsolationAndFindsTheExpectedSection() throws IOException {
        ensureCorpusLoaded();
        List<EvalCase> cases = loadCases();

        int isolatedQuestions = 0;
        int hits = 0;
        double reciprocalRankSum = 0;
        List<String> violations = new ArrayList<>();
        List<String> misses = new ArrayList<>();

        for (EvalCase c : cases) {
            long modelId = modelIdBySlug.get(c.model());
            List<MotoRetrievedChunk> results =
                    retrievalService.hybridSearch(modelId, c.year(), c.query(), new float[1536], ISOLATION_LIMIT);

            boolean isolated = true;
            for (MotoRetrievedChunk chunk : results) {
                DocMeta meta = docMetaById.get(chunk.documentId());
                if (meta == null || !meta.modelSlug().equals(c.model())
                        || c.year() < meta.yearFrom() || c.year() > meta.yearTo()) {
                    isolated = false;
                    violations.add(c.id() + " got chunk " + chunk.chunkId() + " from "
                            + (meta == null ? "unknown document" : meta.path()));
                }
            }
            if (isolated) {
                isolatedQuestions++;
            }

            int rank = 0;
            for (int i = 0; i < results.size(); i++) {
                String section = results.get(i).section();
                if (section != null && PART_SUFFIX.matcher(section).replaceFirst("").equals(c.expectedSection())) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0 && rank <= K) {
                hits++;
            } else {
                misses.add(c.id() + " (expected '" + c.expectedSection() + "', rank " + (rank == 0 ? "none" : rank) + ")");
            }
            if (rank > 0) {
                reciprocalRankSum += 1.0 / rank;
            }
        }

        double isolation = (double) isolatedQuestions / cases.size();
        double hitRate = (double) hits / cases.size();
        double mrr = reciprocalRankSum / cases.size();
        System.out.printf("RETRIEVAL-EVAL questions=%d isolation=%.3f hit@%d=%.3f mrr=%.3f%n",
                cases.size(), isolation, K, hitRate, mrr);
        misses.forEach(m -> System.out.println("RETRIEVAL-EVAL miss: " + m));

        assertThat(violations).as("cross-model or cross-year chunks").isEmpty();
        assertThat(isolation).as("isolation").isGreaterThanOrEqualTo(MIN_ISOLATION);
        assertThat(hitRate).as("hit rate at %d, misses: %s", K, misses).isGreaterThanOrEqualTo(MIN_HIT_RATE_AT_K);
    }

    private List<EvalCase> loadCases() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/retrieval/retrieval-eval-v1.json")) {
            assertThat(in).as("retrieval-eval-v1.json on the test classpath").isNotNull();
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<EvalCase> cases = new ArrayList<>();
            for (JsonNode n : root.get("cases")) {
                cases.add(new EvalCase(n.get("id").asString(), n.get("model").asString(), n.get("year").asInt(),
                        n.get("question").asString(), n.get("query").asString(), n.get("expectedSection").asString()));
            }
            return cases;
        }
    }

    private static Path findCorpusRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("knowledge").resolve("motorcycles");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("knowledge/motorcycles not found above " + Path.of("").toAbsolutePath());
    }

    private static String frontMatterValue(String frontMatter, String key) {
        Matcher m = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE).matcher(frontMatter);
        assertThat(m.find()).as("front matter key %s", key).isTrue();
        return m.group(1).trim();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Java port of embeddings.chunker.chunk_markdown_body: {heading, sectionPath, content} triples. */
    private static List<String[]> chunk(String body) {
        List<String[]> out = new ArrayList<>();
        Matcher m = HEADING.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            levels.add(m.group(1).length());
            titles.add(m.group(2).strip());
        }
        if (spans.isEmpty()) {
            addSection(out, List.of(), body);
            return out;
        }
        String preamble = body.substring(0, spans.get(0)[0]).strip();
        if (!preamble.isEmpty()) {
            addSection(out, List.of(), preamble);
        }
        List<Integer> stackLevels = new ArrayList<>();
        List<String> stackTitles = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            while (!stackLevels.isEmpty() && stackLevels.get(stackLevels.size() - 1) >= levels.get(i)) {
                stackLevels.remove(stackLevels.size() - 1);
                stackTitles.remove(stackTitles.size() - 1);
            }
            stackLevels.add(levels.get(i));
            stackTitles.add(titles.get(i));
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            addSection(out, new ArrayList<>(stackTitles), body.substring(spans.get(i)[1], end));
        }
        return out;
    }

    private static void addSection(List<String[]> out, List<String> path, String rawText) {
        String text = rawText.strip();
        if (text.isEmpty()) {
            return;
        }
        String heading = path.isEmpty() ? null : path.get(path.size() - 1);
        String joined = String.join(" > ", path);
        String[] words = text.split("\\s+");
        if (words.length <= MAX_WORDS_PER_CHUNK) {
            out.add(new String[] {heading, joined, text});
            return;
        }
        int start = 0;
        int part = 1;
        while (start < words.length) {
            int end = Math.min(start + MAX_WORDS_PER_CHUNK, words.length);
            String piece = String.join(" ", java.util.Arrays.copyOfRange(words, start, end));
            String sectionPath = path.isEmpty() ? "(part " + part + ")" : joined + " (part " + part + ")";
            out.add(new String[] {heading, sectionPath, piece});
            if (end == words.length) {
                break;
            }
            start = end - OVERLAP_WORDS;
            part++;
        }
    }
}
