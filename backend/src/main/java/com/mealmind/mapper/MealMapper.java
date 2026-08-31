package com.mealmind.mapper;

import com.mealmind.entity.MealItemRow;
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

    // TODO (later step - Recommend/Orchestrator): JSON_OVERLAPS tag search
    // over meal_item returning up to N candidate rows for the ranking layer.
}