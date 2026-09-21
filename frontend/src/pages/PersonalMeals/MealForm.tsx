import { useState, type FormEvent } from 'react'
import { Button } from '../../components/Button'
import { DimensionChipGroup } from '../../components/DimensionChipGroup'
import {
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
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </label>

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
