package com.mealmind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
@RestController
public class MealMindApplication {

    private final RestClient openAiClient; // call OpenAI
    private final String model; // AI model
    private final List<Map<String, Object>> memory = new ArrayList<>(); // memory store

    public MealMindApplication(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model
    ) {
        this.model = model;
        // Store the model name for later LLM calls.
        this.openAiClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(MealMindApplication.class, args);
    }

    @PostMapping("/api/meal-plan")
    public MealPlanResponse generateMealPlan(@RequestBody MealPlanRequest request) {
        String sessionId = "sess_" + UUID.randomUUID();
        String traceId = "trace_" + UUID.randomUUID();

        // The prompt tells the model what meal plan to generate.
        String prompt = """
                Create one simple meal plan for this user.

                Meal time: %s
                Goal: %s
                Preferences: %s
                Budget: %s

                Return:
                1. Recommended meal
                2. Short reason
                3. Simple note for the user
                """.formatted(
                request.mealTime(),
                request.goal(),
                request.preferences(),
                request.budget()
        );

        // request body to OpenAI
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "developer", "content", "You are a practical meal planning assistant."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        Map responseBody = openAiClient.post()
                .uri("/chat/completions")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        List choices = (List) responseBody.get("choices");
        Map firstChoice = (Map) choices.get(0);
        Map message = (Map) firstChoice.get("message");
        String speechText = (String) message.get("content");

        Map<String, Object> savedItem = new LinkedHashMap<>();
        savedItem.put("sessionId", sessionId);
        savedItem.put("traceId", traceId);
        savedItem.put("request", request);
        savedItem.put("speechText", speechText);
        memory.add(savedItem);

        return new MealPlanResponse(sessionId, traceId, speechText);
    }

    @GetMapping("/api/memory")
    public List<Map<String, Object>> getMemory() {
        return memory;
    }

    public record MealPlanRequest(
            String mealTime,
            String goal,
            String preferences,
            String budget
    ) {
    }

    public record MealPlanResponse(
            String sessionId,
            String traceId,
            String speechText
    ) {
    }
}
