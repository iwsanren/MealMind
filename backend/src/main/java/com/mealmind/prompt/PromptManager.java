package com.mealmind.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads prompt templates from classpath files under resources/prompts/,
 * renders {{variable}} placeholders, and reports back which version was
 * actually used.
 *
 * Design notes (why it looks like this):
 * - Uses ClassPathResource, not java.io.File — resources/prompts/ gets
 *   packaged inside the JAR, and File-based paths break once that happens
 * - The "current version" comes from ONE place: PromptProperties, which is
 *   bound from prompts-config.yaml.
 */
@Component
public class PromptManager {

    private final PromptProperties promptProperties;

    public PromptManager(PromptProperties promptProperties) {
        this.promptProperties = promptProperties;
    }

    /**
     * Loads the currently configured version for {@code scenario}, renders
     * the given variables into it, and returns both the text and the
     * version number that produced it.
     *
     * @param scenario  e.g. "meal_recommendation" — must have an entry in
     *                  prompts-config.yaml
     * @param variables values to substitute for {{key}} placeholders in the
     *                  template; a placeholder with no matching key is left
     *                  as-is (fails loudly by being visible in the output,
     *                  rather than silently disappearing)
     */
    public RenderedPrompt render(String scenario, Map<String, String> variables) {
        String version = currentVersion(scenario);
        String template = load(scenario, version);
        String text = renderTemplate(template, variables);
        return new RenderedPrompt(scenario, version, text);
    }

    /** Which version is configured as "current" for this scenario. */
    public String currentVersion(String scenario) {
        String version = promptProperties.getVersions().get(scenario);
        if (version == null || version.isBlank()) {
            throw new PromptNotFoundException(
                    "No prompt version configured for scenario '" + scenario
                            + "' — add it to prompts-config.yaml");
        }
        return version;
    }

    /** Reads a specific version's raw template, bypassing the "current" config. */
    public String load(String scenario, String version) {
        String path = "prompts/" + scenario + "/" + version + ".txt";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PromptNotFoundException("Failed to load prompt file: " + path, e);
        }
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
