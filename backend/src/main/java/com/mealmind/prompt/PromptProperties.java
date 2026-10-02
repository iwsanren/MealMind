package com.mealmind.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Type-safe binding for prompts-config.yaml's "prompts:" section.
 * Maps a scenario name (e.g. "meal_recommendation") to the version string
 * that is currently active for it (e.g. "v2").
 *
 * This is the ONLY place that says which version is "current" — there is
 * no separate current.txt file to keep in sync with this.
 */
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {

    /** scenario name -> active version, e.g. {"meal_recommendation": "v2"} */
    private Map<String, String> versions = Map.of();

    public Map<String, String> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, String> versions) {
        this.versions = versions;
    }
}
