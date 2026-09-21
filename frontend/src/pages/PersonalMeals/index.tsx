import { useEffect, useState } from 'react'
import {
  createPersonalMeal,
  deletePersonalMeal,
  getPersonalMeals,
  getSlotOptions,
  updatePersonalMeal,
} from '../../api/meals'
import { Button } from '../../components/Button'
import { EmptyState } from '../../components/EmptyState'
import { MealCard } from '../../components/MealCard'
import { ApiError } from '../../lib/apiClient'
import type { MealRequest, MealResponse, SlotOptionsMap } from '../../types/meal'
import { MealForm } from './MealForm'

export function PersonalMealsPage() {
  const [meals, setMeals] = useState<MealResponse[]>([])
  const [slotOptions, setSlotOptions] = useState<SlotOptionsMap | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mutationError, setMutationError] = useState<string | null>(null)
  const [editingMeal, setEditingMeal] = useState<MealResponse | null>(null)
  const [formKey, setFormKey] = useState(0)

  function loadMeals() {
    return getPersonalMeals().then(setMeals)
  }

  useEffect(() => {
    let cancelled = false

    Promise.all([getSlotOptions(), getPersonalMeals()])
      .then(([options, personalMeals]) => {
        if (cancelled) return
        setSlotOptions(options)
        setMeals(personalMeals)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(err instanceof ApiError ? err.message : 'Failed to load personal meals.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  function resetForm() {
    setEditingMeal(null)
    setFormKey((k) => k + 1)
  }

  async function handleSubmit(payload: MealRequest) {
    setMutationError(null)
    try {
      if (editingMeal) {
        await updatePersonalMeal(editingMeal.id, payload)
      } else {
        await createPersonalMeal(payload)
      }
      await loadMeals()
      resetForm()
    } catch (err) {
      setMutationError(err instanceof ApiError ? err.message : 'Failed to save meal.')
    }
  }

  async function handleDelete(meal: MealResponse) {
    setMutationError(null)
    try {
      await deletePersonalMeal(meal.id)
      await loadMeals()
      if (editingMeal?.id === meal.id) {
        resetForm()
      }
    } catch (err) {
      setMutationError(err instanceof ApiError ? err.message : 'Failed to delete meal.')
    }
  }

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-8 px-6 py-10">
      <header className="flex flex-col gap-1">
        <h1>Personal Meals</h1>
        <p className="text-text-secondary">Manage the meals you actually eat.</p>
      </header>

      {loading && <p className="text-text-secondary">Loading...</p>}

      {loadError && (
        <div className="rounded border border-border bg-surface p-4 text-text-secondary">{loadError}</div>
      )}

      {mutationError && (
        <div className="rounded border border-danger bg-danger-subtle p-4 text-danger">{mutationError}</div>
      )}

      {!loading && !loadError && slotOptions && (
        <div className="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_360px] lg:items-start">
          <div className="flex flex-col gap-4">
            <h2 className="text-[16px] font-semibold">My Meals</h2>
            {meals.length === 0 ? (
              <EmptyState message="No personal meals yet. Add your first one on the right." />
            ) : (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                {meals.map((meal) => (
                  <MealCard
                    key={meal.id}
                    meal={meal}
                    className={editingMeal?.id === meal.id ? 'border-accent' : ''}
                    actions={
                      <>
                        <Button variant="secondary" onClick={() => setEditingMeal(meal)}>
                          Edit
                        </Button>
                        <Button variant="danger" onClick={() => handleDelete(meal)}>
                          Delete
                        </Button>
                      </>
                    }
                  />
                ))}
              </div>
            )}
          </div>

          <MealForm
            key={editingMeal ? `edit-${editingMeal.id}` : `new-${formKey}`}
            slotOptions={slotOptions}
            initialMeal={editingMeal}
            onSubmit={handleSubmit}
            onCancel={resetForm}
          />
        </div>
      )}
    </div>
  )
}

export default PersonalMealsPage
