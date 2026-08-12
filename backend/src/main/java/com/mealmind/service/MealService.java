package com.mealmind.service;

import com.mealmind.entity.MealItem;
import com.mealmind.mapper.MealMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MealService {

    private static final Long CURRENT_USER_ID = 1L;
    private final MealMapper mealMapper;

    public MealService(MealMapper mealMapper) {
        this.mealMapper = mealMapper;
    }

    public MealItem createMeal(MealItem mealItem) {
        mealItem.setSourceType("PERSONAL");
        mealItem.setOwnerUserId(CURRENT_USER_ID);
        mealMapper.insert(mealItem);
        return mealItem;
    }

    public List<MealItem> getMeals() {
        return mealMapper.findPersonalMeals(CURRENT_USER_ID);
    }
}
