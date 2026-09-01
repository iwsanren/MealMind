package com.mealmind.mapper;

import com.mealmind.entity.MealItemRow;
import com.mealmind.enums.SourceMode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MealMapper {

    int insert(MealItemRow row);                                           // useGeneratedKeys -> row.id

    int updatePersonal(MealItemRow row);                                   // returns affected rows

    int deletePersonal(@Param("id") Long id, @Param("userId") Long userId);

    MealItemRow findPersonalById(@Param("id") Long id, @Param("userId") Long userId);

    List<MealItemRow> findPersonalMeals(@Param("userId") Long userId);

    List<MealItemRow> findPublicMeals();

    int countPersonalMeals(@Param("userId") Long userId);                  // wired now, used by Orchestrator later


    // over meal_item returning up to N candidate rows for the ranking layer.
    List<MealItemRow> search(
            @Param("personal") boolean personal,          // true = PERSONAL library, false = PUBLIC
            @Param("userId") Long userId,                  // only used when personal = true
            @Param("mealTimeJson") String mealTimeJson,
            @Param("moodJson") String moodJson,
            @Param("sceneJson") String sceneJson,
            @Param("healthGoalJson") String healthGoalJson,
            @Param("cuisineJson") String cuisineJson,
            @Param("tasteJson") String tasteJson,
            @Param("convenienceJson") String convenienceJson,
            @Param("limit") int limit
    );
}