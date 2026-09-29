package com.mealmind.tools;

import com.mealmind.prompt.PromptManager;
import com.mealmind.prompt.PromptProperties;
import com.mealmind.prompt.RenderedPrompt;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-off manual comparison: same sample input, rendered with v2 (no
 * nutrition reference) vs v3 (with it), each sent to the LLM once so you can
 * read the two outputs side by side.
 *
 * NOT a unit test — makes a real, billed API call, so it is deliberately
 * named without a Test/Tests suffix (Surefire's default include patterns
 * won't pick it up, so `mvn test` skips it). Run main() directly from the
 * IDE, with the working directory set to backend/ (same as the app itself),
 * so the .env lookup below finds the same file Spring Boot loads via
 * spring-dotenv. This class does NOT go through Spring, so spring-dotenv's
 * own auto-loading never fires here — the .env read below is a plain,
 * hand-rolled substitute for it, just for this script.
 *
 * Reads OPENAI_API_KEY, OPENAI_MODEL, LLM_API_URL from backend/.env (falling
 * back to real environment variables if a key isn't in the file). Never
 * hardcode the key here.
 */
public class RagComparisonRunner {

    public static void main(String[] args) {
        Map<String, String> env = loadDotEnv();
        String apiKey = config(env, "OPENAI_API_KEY");
        String model = config(env, "OPENAI_MODEL");
        String apiUrl = config(env, "LLM_API_URL");

        // Same sample for both runs — only the prompt version differs.
        Map<String, String> sample = Map.of(
                "preferences", "high protein, muscle gain, budget under $15",
                "constraints", "no shellfish",
                "candidates", "[grilled chicken bowl - $12; salmon salad - $14; "
                        + "veggie stir fry - $10]"
        );

        RenderedPrompt withoutRag = promptManagerPinnedTo("v2").render("meal_recommendation", sample);
        RenderedPrompt withRag = promptManagerPinnedTo("v3").render("meal_recommendation", sample);

        System.out.println("=== WITHOUT nutrition reference (v2) ===");
        System.out.println(callLlm(apiUrl, apiKey, model, withoutRag.text()));

        System.out.println("\n=== WITH nutrition reference (v3) ===");
        System.out.println(callLlm(apiUrl, apiKey, model, withRag.text()));
    }

    // Same trick PromptManagerTest.java uses: build PromptProperties by hand
    // so we can force a specific version instead of reading prompts-config.yaml.
    private static PromptManager promptManagerPinnedTo(String version) {
        PromptProperties properties = new PromptProperties();
        properties.setVersions(Map.of("meal_recommendation", version));
        return new PromptManager(properties);
    }

    private static String callLlm(String apiUrl, String apiKey, String model, String promptText) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 300,
                "messages", List.of(Map.of("role", "user", "content", promptText))
        );

        Map<?, ?> response = RestClient.create()
                .post()
                .uri(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        List<?> choices = (List<?>) response.get("choices");
        Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
        return (String) message.get("content");
    }

    // Minimal stand-in for spring-dotenv, which only activates inside a
    // Spring Boot context. Parses simple KEY=VALUE lines; good enough for
    // this file's shape, not a general .env parser.
    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<>();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read .env at " + envFile.toAbsolutePath(), e);
            }
        } else {
            System.out.println("[RagComparisonRunner] No .env found at " + envFile.toAbsolutePath()
                    + " — falling back to system environment variables. If that's not backend/.env, "
                    + "fix the run configuration's working directory.");
        }
        return values;
    }

    private static String config(Map<String, String> dotEnv, String key) {
        String fromFile = dotEnv.get(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        String fromSystemEnv = System.getenv(key);
        if (fromSystemEnv != null && !fromSystemEnv.isBlank()) {
            return fromSystemEnv;
        }
        throw new IllegalStateException(
                "Missing config '" + key + "' — checked backend/.env and system environment variables. "
                        + "Make sure the run's working directory is backend/.");
    }
}
/**
 * result:
 * === WITHOUT nutrition reference (v2) ===
 * The best matching meal for the user would be the grilled chicken bowl for $12 because it is high in protein, fits within the budget, and does not contain any shellfish. [FROM_CANDIDATE]
 *
 * === WITH nutrition reference (v3) ===
 * The best matching meal for the user's preferences and constraints is the grilled chicken bowl priced at $12. This meal is high in protein from the grilled chicken [FROM_CANDIDATE], which is a nutrient-dense protein source recommended for muscle gain [FROM_GUIDELINE]. Additionally, it fits within the user's budget of under $15.
 */