package com.mealmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// ConfigurationPropertiesScan picks up @ConfigurationProperties classes
// (e.g. com.mealmind.prompt.PromptProperties) without needing each of them
// individually declared via @EnableConfigurationProperties.
@SpringBootApplication
@ConfigurationPropertiesScan
public class MealMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(MealMindApplication.class, args);
    }
}
