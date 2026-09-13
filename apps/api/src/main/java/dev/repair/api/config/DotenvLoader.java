package dev.repair.api.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a local `.env` file into system properties before Spring reads
 * configuration, mirroring what python-dotenv already does for the
 * pipelines package (see pipelines/ingest/config.py). Spring Boot has no
 * built-in `.env` support — without this, OPENAI_API_KEY and friends would
 * only ever be picked up as real exported shell/CI environment variables.
 *
 * Searches upward from the current working directory (e.g. apps/api when
 * run via `./mvnw spring-boot:run`) so the same repo-root `.env` used by
 * the Python pipeline and docker-compose.yml is found regardless of which
 * subdirectory the JVM was launched from. Never overrides a value that is
 * already set as a real environment variable or system property — a real
 * deployment env var always wins over a local dev file. Never logs or
 * otherwise exposes the values it loads.
 */
public final class DotenvLoader {

    private static final String ENV_FILENAME = ".env";
    private static final int MAX_PARENT_LEVELS = 6;

    private DotenvLoader() {
    }

    public static void loadIntoSystemProperties() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < MAX_PARENT_LEVELS && dir != null; i++, dir = dir.getParent()) {
            Path envFile = dir.resolve(ENV_FILENAME);
            if (Files.isRegularFile(envFile)) {
                applyEnvFile(envFile);
                return;
            }
        }
    }

    static void applyEnvFile(Path envFile) {
        try {
            for (String line : Files.readAllLines(envFile)) {
                applyLine(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + envFile, e);
        }
    }

    static void applyLine(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
            return;
        }
        String key = trimmed.substring(0, separator).strip();
        String value = unquote(trimmed.substring(separator + 1).strip());
        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
