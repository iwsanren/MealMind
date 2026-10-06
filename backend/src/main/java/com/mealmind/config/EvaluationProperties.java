package com.mealmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the offline evaluation output lives ("mealmind.evaluation.*", see application.yml). Paths are relative to the
 * directory the backend is started from (backend/ for mvn spring-boot:run). A missing directory is not an error: the
 * evaluation page then just shows that there are no runs.
 */
@ConfigurationProperties(prefix = "mealmind.evaluation")
public record EvaluationProperties(
        @DefaultValue("../evaluation/results") String resultsDir,
        @DefaultValue("../evaluation/cases.json") String casesFile
) {
}
