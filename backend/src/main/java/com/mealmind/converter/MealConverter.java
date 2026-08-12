package com.mealmind.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.dto.meal.CreateMealRequest;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.entity.MealItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MealConverter {

    private final ObjectMapper objectMapper;

    public MealConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MealItem toMealItem(CreateMealRequest request) {
        MealItem mealItem = new MealItem();
        mealItem.setName(request.name());
        mealItem.setPrice(request.price());
        mealItem.setTagsJson(toJson(request.tags()));
        return mealItem;
    }

    public MealResponse toResponse(MealItem mealItem) {
        return new MealResponse(
                mealItem.getId(),
                mealItem.getName(),
                mealItem.getPrice(),
                fromJson(mealItem.getTagsJson())
        );
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
}
