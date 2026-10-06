import { useState, type FormEvent } from 'react'
import { Button } from '../../components/Button'
import { MEAL_CURRENCY_CODE } from '../../lib/formatMoney'
import { DimensionChipGroup } from '../../components/DimensionChipGroup'
import {
  ALLERGENS,
  SLOT_DIMENSIONS,
  type MealRequest,
  type MealResponse,
  type SlotDimension,
  type SlotOptionsMap,
} from '../../types/meal'

type Selection = Record<SlotDimension, Set<string>>

function buildSelection(meal: MealResponse | null): Selection {
  return SLOT_DIMENSIONS.reduce((acc, dimension) => {
    acc[dimension] = new Set(meal ? meal[dimension] : [])
    return acc
  }, {} as Selection)
}

const INPUT_CLASS =
  'rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none'

/** Empty input means "unknown" (null), not zero. */
function parseOptionalNumber(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : null
}

interface MealFormProps {
  slotOptions: SlotOptionsMap
  initialMeal: MealResponse | null
  onSubmit: (payload: MealRequest) => void
  onCancel: () => void
}

export function MealForm({ slotOptions, initialMeal, onSubmit, onCancel }: MealFormProps) {
  const [name, setName] = useState(initialMeal?.name ?? '')
  const [selection, setSelection] = useState<Selection>(() => buildSelection(initialMeal))
  const [mealTimeError, setMealTimeError] = useState<string | undefined>(undefined)
  const [price, setPrice] = useState(initialMeal?.price?.toString() ?? '')
  const [proteinG, setProteinG] = useState(initialMeal?.proteinG?.toString() ?? '')
  const [calories, setCalories] = useState(initialMeal?.calories?.toString() ?? '')
  // Allergens: null (unchecked) = unknown; checked + nothing selected = "contains none".
  const [allergensKnown, setAllergensKnown] = useState(initialMeal?.allergens != null)
  const [allergens, setAllergens] = useState<Set<string>>(new Set(initialMeal?.allergens ?? []))

  function toggle(dimension: SlotDimension, value: string) {
    setSelection((prev) => {
      const next = { ...prev, [dimension]: new Set(prev[dimension]) }
      if (next[dimension].has(value)) {
        next[dimension].delete(value)
      } else {
        next[dimension].add(value)
      }
      return next
    })
  }

  function toggleAllergen(value: string) {
    setAllergens((prev) => {
      const next = new Set(prev)
      if (next.has(value)) {
        next.delete(value)
      } else {
        next.add(value)
      }
      return next
    })
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()

    if (selection.mealTime.size === 0) {
      setMealTimeError('Select at least one meal time.')
      return
    }
    setMealTimeError(undefined)

    onSubmit({
      name,
      mealTime: [...selection.mealTime],
      mood: [...selection.mood],
      scene: [...selection.scene],
      healthGoal: [...selection.healthGoal],
      cuisine: [...selection.cuisine],
      taste: [...selection.taste],
      convenience: [...selection.convenience],
      price: parseOptionalNumber(price),
      proteinG: parseOptionalNumber(proteinG),
      calories: parseOptionalNumber(calories),
      allergens: allergensKnown ? [...allergens] : null,
    })
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="flex h-fit flex-col gap-5 rounded border border-border bg-surface p-5"
    >
      <h2 className="text-[16px] font-semibold">{initialMeal ? 'Edit meal' : 'Add meal'}</h2>

      <label className="flex flex-col gap-1">
        <span className="text-[13px] font-medium text-text-secondary">Name</span>
        <input
          type="text"
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          className={INPUT_CLASS}
        />
      </label>

      <div className="grid grid-cols-3 gap-3">
        <label className="flex flex-col gap-1">
          <span className="text-[13px] font-medium text-text-secondary">Price ({MEAL_CURRENCY_CODE})</span>
          <input type="number" min="0" step="0.01" value={price} onChange={(e) => setPrice(e.target.value)} className={INPUT_CLASS} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[13px] font-medium text-text-secondary">Protein (g)</span>
          <input type="number" min="0" step="0.1" value={proteinG} onChange={(e) => setProteinG(e.target.value)} className={INPUT_CLASS} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[13px] font-medium text-text-secondary">Calories (kcal)</span>
          <input type="number" min="0" step="1" value={calories} onChange={(e) => setCalories(e.target.value)} className={INPUT_CLASS} />
        </label>
      </div>
      <span className="-mt-3 text-[12px] text-text-secondary">Per serving. Leave blank if unknown.</span>

      <div className="flex flex-col gap-2">
        <label className="flex items-center gap-2 text-[13px] font-medium text-text-secondary">
          <input type="checkbox" checked={allergensKnown} onChange={(e) => setAllergensKnown(e.target.checked)} />
          I know this meal's allergens (leave unchecked if unknown)
        </label>
        {allergensKnown && (
          <div className="flex flex-wrap gap-3">
            {ALLERGENS.map((allergen) => (
              <label key={allergen} className="flex items-center gap-1 text-[13px]">
                <input type="checkbox" checked={allergens.has(allergen)} onChange={() => toggleAllergen(allergen)} />
                {allergen}
              </label>
            ))}
          </div>
        )}
        {allergensKnown && allergens.size === 0 && (
          <span className="text-[12px] text-text-secondary">Nothing selected = contains none of the listed allergens.</span>
        )}
      </div>

      {SLOT_DIMENSIONS.map((dimension) => (
        <DimensionChipGroup
          key={dimension}
          dimension={dimension}
          options={slotOptions[dimension] ?? []}
          selected={selection[dimension]}
          onToggle={(value) => toggle(dimension, value)}
          required={dimension === 'mealTime'}
          error={dimension === 'mealTime' ? mealTimeError : undefined}
        />
      ))}

      <div className="flex gap-3">
        <Button type="submit" variant="primary">
          {initialMeal ? 'Save changes' : 'Create meal'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
