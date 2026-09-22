import { SLOT_DIMENSIONS, type MealResponse, type SlotDimension } from '../types/meal'

export function filterMealsBySlots(
  meals: MealResponse[],
  selected: Record<SlotDimension, Set<string>>,
): MealResponse[] {
  return meals.filter((meal) =>
    SLOT_DIMENSIONS.every((dimension) => {
      const chosen = selected[dimension]
      if (chosen.size === 0) return true
      return meal[dimension].some((value) => chosen.has(value))
    }),
  )
}
