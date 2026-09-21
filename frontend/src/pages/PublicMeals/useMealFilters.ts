import { useMemo, useState } from 'react'
import { filterMealsBySlots } from '../../lib/mealFilters'
import { SLOT_DIMENSIONS, type MealResponse, type SlotDimension } from '../../types/meal'

export type SelectedTags = Record<SlotDimension, Set<string>>

function emptySelection(): SelectedTags {
  return SLOT_DIMENSIONS.reduce((acc, dimension) => {
    acc[dimension] = new Set<string>()
    return acc
  }, {} as SelectedTags)
}

export function useMealFilters(meals: MealResponse[]) {
  const [selected, setSelected] = useState<SelectedTags>(emptySelection)

  function toggleTag(dimension: SlotDimension, value: string) {
    setSelected((prev) => {
      const next = { ...prev, [dimension]: new Set(prev[dimension]) }
      if (next[dimension].has(value)) {
        next[dimension].delete(value)
      } else {
        next[dimension].add(value)
      }
      return next
    })
  }

  const filteredMeals = useMemo(() => filterMealsBySlots(meals, selected), [meals, selected])

  return { selected, toggleTag, filteredMeals }
}
