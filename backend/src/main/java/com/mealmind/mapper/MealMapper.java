package com.mealmind.mapper;

import com.mealmind.entity.MealItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MealMapper {

    int insert(MealItem mealItem);

    List<MealItem> findPersonalMeals(@Param("ownerUserId") Long ownerUserId);
}
