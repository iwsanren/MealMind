package com.mealmind;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@SpringBootApplication
@RestController
public class MealMindApplication {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MealMindApplication(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        SpringApplication.run(MealMindApplication.class, args);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>MealMind</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            max-width: 680px;
                            margin: 40px auto;
                            padding: 0 20px;
                        }

                        label {
                            display: block;
                            margin-top: 16px;
                        }

                        input, button {
                            box-sizing: border-box;
                            width: 100%;
                            padding: 10px;
                            margin-top: 6px;
                            font-size: 16px;
                        }

                        button {
                            margin-top: 20px;
                            cursor: pointer;
                        }

                        li {
                            margin: 10px 0;
                        }
                    </style>
                </head>
                <body>
                    <h1>My Meals</h1>

                    <form id="meal-form">
                        <label>
                            Meal Name
                            <input id="name" placeholder="e.g. Chicken Salad">
                        </label>

                        <label>
                            Price
                            <input id="price" type="number" placeholder="e.g. 22">
                        </label>

                        <label>
                            Tags
                            <input id="tags" placeholder="e.g. Lunch, Light, High Protein">
                        </label>

                        <button type="submit">Add Meal</button>
                    </form>

                    <h2>Current Meals</h2>
                    <ul id="meal-list"></ul>

                    <script>
                        async function loadMeals() {
                            const response = await fetch("/api/v1/meals");
                            const meals = await response.json();
                            const list = document.getElementById("meal-list");

                            list.innerHTML = "";

                            meals.forEach((meal) => {
                                const item = document.createElement("li");
                                item.textContent =
                                    "#" + meal.id +
                                    " " + meal.name +
                                    " | $" + meal.price +
                                    " | " + (meal.tags || []).join(", ");
                                list.appendChild(item);
                            });
                        }

                        document.getElementById("meal-form").addEventListener("submit", async (event) => {
                            event.preventDefault();

                            const payload = {
                                name: document.getElementById("name").value,
                                price: Number(document.getElementById("price").value),
                                tags: document.getElementById("tags").value
                                    .split(",")
                                    .map((tag) => tag.trim())
                                    .filter((tag) => tag.length > 0)
                            };

                            await fetch("/api/v1/meals", {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json"
                                },
                                body: JSON.stringify(payload)
                            });

                            document.getElementById("meal-form").reset();
                            await loadMeals();
                        });

                        loadMeals();
                    </script>
                </body>
                </html>
                """;
    }

    @PostMapping("/api/v1/meals")
    public Meal createMeal(@RequestBody CreateMealRequest request) {
        String sql = """
                INSERT INTO meal_item (source_type, owner_user_id, name, price, tags)
                VALUES ('PERSONAL', 1, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.name());
            statement.setDouble(2, request.price());
            statement.setString(3, toJson(request.tags()));
            return statement;
        }, keyHolder);

        return new Meal(
                keyHolder.getKey().longValue(),
                request.name(),
                request.price(),
                request.tags()
        );
    }

    @GetMapping("/api/v1/meals")
    public List<Meal> getMeals() {
        String sql = """
                SELECT id, name, price, tags
                FROM meal_item
                WHERE source_type = 'PERSONAL' AND owner_user_id = 1
                ORDER BY id
                """;

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new Meal(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getDouble("price"),
                fromJson(resultSet.getString("tags"))
        ));
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> fromJson(String tagsJson) {
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record CreateMealRequest(
            String name,
            Double price,
            List<String> tags
    ) {
    }

    public record Meal(
            long id,
            String name,
            Double price,
            List<String> tags
    ) {
    }
}
