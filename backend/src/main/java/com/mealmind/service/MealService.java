package com.mealmind.service;

import com.mealmind.dto.meal.MealRequest;
import com.mealmind.model.MealItem;
import com.mealmind.entity.MealItemRow;
import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.MealMapper;
import com.mealmind.model.SlotBundle;
import com.mealmind.util.JsonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MealService {

    private final MealMapper mealMapper;
    private final JsonService jsonService;

    public MealService(MealMapper mealMapper, JsonService jsonService) {
        this.mealMapper = mealMapper;
        this.jsonService = jsonService;
    }

    // ---- reads ----
    public List<MealItem> findPersonalMeals(Long userId) {
        return mealMapper.findPersonalMeals(userId).stream().map(this::toMealItem).toList();
    }

    public List<MealItem> findPublicMeals() {
        return mealMapper.findPublicMeals().stream().map(this::toMealItem).toList();
    }

    // ---- writes (PERSONAL only) ----
    @Transactional
    public MealItem createPersonalMeal(Long userId, MealRequest request) {
        SlotBundle slots = request.toSlots();
        validateMealRequest(request, slots);
        MealItemRow row = toRow(null, SourceMode.PERSONAL, userId, request.name(), slots);
        mealMapper.insert(row);                     // useGeneratedKeys fills row.id
        return toMealItem(row);
    }

    @Transactional
    public MealItem updatePersonalMeal(Long userId, Long mealId, MealRequest request) {
        SlotBundle slots = request.toSlots();
        validateMealRequest(request, slots);
        MealItemRow row = toRow(mealId, SourceMode.PERSONAL, userId, request.name(), slots);
        if (mealMapper.updatePersonal(row) == 0) {
            // 0 rows => not found OR not owned by this user (indistinguishable on purpose)
            throw new MealException("Personal meal not found or not editable");
        }
        return toMealItem(mealMapper.findPersonalById(mealId, userId));
    }

    @Transactional
    public void deletePersonalMeal(Long userId, Long mealId) {
        if (mealMapper.deletePersonal(mealId, userId) == 0) {
            throw new MealException("Personal meal not found or not deletable");
        }
    }

    // ---- row <-> domain ----
    private MealItemRow toRow(Long id, SourceMode sourceMode, Long ownerUserId, String name, SlotBundle slots) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setSourceType(sourceMode.name());
        row.setOwnerUserId(ownerUserId);
        row.setName(name.trim());
        row.setMealTime(jsonService.toJsonArray(slots.mealTime()));
        row.setMood(jsonService.toJsonArray(slots.mood()));
        row.setScene(jsonService.toJsonArray(slots.scene()));
        row.setHealthGoal(jsonService.toJsonArray(slots.healthGoal()));
        row.setCuisine(jsonService.toJsonArray(slots.cuisine()));
        row.setTaste(jsonService.toJsonArray(slots.taste()));
        row.setConvenience(jsonService.toJsonArray(slots.convenience()));
        return row;
    }

    /**
     * Shared precondition check for create/update. Runs before any DB write, so
     * bad input fails fast as a 400 (MealException -> GlobalExceptionHandler).
     */
    private void validateMealRequest(MealRequest request, SlotBundle slots) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MealException("Meal name must not be blank");
        }
        // mealTime is the one mandatory dimension; checked AFTER toSlots() so that
        // blank / duplicate entries have already been stripped by SlotBundle.
        if (slots.mealTime().isEmpty()) {
            throw new MealException("mealTime must contain at least one tag");
        }
        // TODO (next prompt): call SlotOptionService.validate(slots) to reject any
        // tag not present in the SlotOption dictionary across all seven dimensions.
    }

    private MealItem toMealItem(MealItemRow row) {
        if (row == null) {
            return null;
        }
        SlotBundle slots = new SlotBundle(
                jsonService.fromJsonArray(row.getMealTime()),
                jsonService.fromJsonArray(row.getMood()),
                jsonService.fromJsonArray(row.getScene()),
                jsonService.fromJsonArray(row.getHealthGoal()),
                jsonService.fromJsonArray(row.getCuisine()),
                jsonService.fromJsonArray(row.getTaste()),
                jsonService.fromJsonArray(row.getConvenience())
        );
        return new MealItem(
                row.getId(),
                SourceMode.valueOf(row.getSourceType()),
                row.getOwnerUserId(),
                row.getName(),
                slots,
                0d                                 // matchScore: filled by the ranking step later
        );
    }
}