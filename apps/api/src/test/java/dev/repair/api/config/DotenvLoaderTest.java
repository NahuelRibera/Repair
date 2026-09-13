package dev.repair.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DotenvLoader.loadIntoSystemProperties() searches upward from the process
 * working directory, which isn't practical to redirect in-process — so
 * this exercises the actual file-parsing behavior directly against a
 * real temp file via the package-private helpers, which is what matters
 * (comment/blank handling, quote stripping, never overriding a real env
 * var). The upward-search wiring itself is covered by manually verifying
 * `./mvnw spring-boot:run` from apps/api picks up a repo-root `.env` — see
 * docs/planning/local-development.md.
 */
class DotenvLoaderTest {

    private static final String TEST_KEY = "REPAIR_TEST_DOTENV_KEY_" + System.nanoTime();

    @AfterEach
    void cleanup() {
        System.clearProperty(TEST_KEY);
    }

    @Test
    void parsesSimpleKeyValueLine() throws IOException {
        DotenvLoader.applyLine(TEST_KEY + "=hello");
        assertThat(System.getProperty(TEST_KEY)).isEqualTo("hello");
    }

    @Test
    void stripsSurroundingDoubleQuotes() {
        DotenvLoader.applyLine(TEST_KEY + "=\"quoted value\"");
        assertThat(System.getProperty(TEST_KEY)).isEqualTo("quoted value");
    }

    @Test
    void stripsSurroundingSingleQuotes() {
        DotenvLoader.applyLine(TEST_KEY + "='quoted value'");
        assertThat(System.getProperty(TEST_KEY)).isEqualTo("quoted value");
    }

    @Test
    void ignoresBlankAndCommentLines() {
        DotenvLoader.applyLine("");
        DotenvLoader.applyLine("   ");
        DotenvLoader.applyLine("# " + TEST_KEY + "=should-not-apply");
        assertThat(System.getProperty(TEST_KEY)).isNull();
    }

    @Test
    void ignoresLineWithNoEqualsSign() {
        DotenvLoader.applyLine("NOT_A_VALID_LINE");
        assertThat(System.getProperty(TEST_KEY)).isNull();
    }

    @Test
    void neverOverridesAnAlreadySetSystemProperty() {
        System.setProperty(TEST_KEY, "already-set-by-real-env");
        DotenvLoader.applyLine(TEST_KEY + "=from-dotenv-file");
        assertThat(System.getProperty(TEST_KEY)).isEqualTo("already-set-by-real-env");
    }

    @Test
    void readsAWholeFileEndToEnd(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                # a comment
                %s=value-from-file

                ANOTHER_UNRELATED_KEY=ignored-by-assertion
                """.formatted(TEST_KEY));

        DotenvLoader.applyEnvFile(envFile);

        assertThat(System.getProperty(TEST_KEY)).isEqualTo("value-from-file");
    }
}
