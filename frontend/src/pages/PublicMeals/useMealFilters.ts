import { useMemo, useState } from 'react'
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

  const filteredMeals = useMemo(() => {
    return meals.filter((meal) =>
      SLOT_DIMENSIONS.every((dimension) => {
        const chosen = selected[dimension]
        if (chosen.size === 0) return true
        return meal[dimension].some((value) => chosen.has(value))
      }),
    )
  }, [meals, selected])

  return { selected, toggleTag, filteredMeals }
}
