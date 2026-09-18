import { useEffect, useState } from 'react'
import { getPublicMeals, getSlotOptions } from '../../api/meals'
import { EmptyState } from '../../components/EmptyState'
import { ApiError } from '../../lib/apiClient'
import type { MealResponse, SlotOptionsMap } from '../../types/meal'
import { FilterPanel } from './FilterPanel'
import { MealCard } from './MealCard'
import { useMealFilters } from './useMealFilters'

export function PublicMealsPage() {
  const [meals, setMeals] = useState<MealResponse[]>([])
  const [slotOptions, setSlotOptions] = useState<SlotOptionsMap | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    Promise.all([getSlotOptions(), getPublicMeals()])
      .then(([options, publicMeals]) => {
        if (cancelled) return
        setSlotOptions(options)
        setMeals(publicMeals)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof ApiError ? err.message : 'Failed to load meals.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  const { selected, toggleTag, filteredMeals } = useMealFilters(meals)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-8 px-6 py-10">
      <header className="flex flex-col gap-1">
        <h1>Public Meal Library</h1>
        <p className="text-text-secondary">Browse shared meals and filter by tag.</p>
      </header>

      {loading && <p className="text-text-secondary">Loading...</p>}

      {error && (
        <div className="rounded border border-border bg-surface p-4 text-text-secondary">
          {error}
        </div>
      )}

      {!loading && !error && slotOptions && (
        <div className="grid grid-cols-1 gap-8 md:grid-cols-[280px_1fr] md:items-start">
          <FilterPanel slotOptions={slotOptions} selected={selected} onToggle={toggleTag} />

          {filteredMeals.length === 0 ? (
            <EmptyState message="No meals match the selected filters." />
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              {filteredMeals.map((meal) => (
                <MealCard key={meal.id} meal={meal} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default PublicMealsPage
