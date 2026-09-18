import { Chip } from '../../components/Chip'
import { SLOT_DIMENSIONS, type SlotDimension, type SlotOptionsMap } from '../../types/meal'
import type { SelectedTags } from './useMealFilters'

interface FilterPanelProps {
  slotOptions: SlotOptionsMap
  selected: SelectedTags
  onToggle: (dimension: SlotDimension, value: string) => void
}

const DIMENSION_LABELS: Record<SlotDimension, string> = {
  mealTime: 'Meal Time',
  mood: 'Mood',
  scene: 'Scene',
  healthGoal: 'Health Goal',
  cuisine: 'Cuisine',
  taste: 'Taste',
  convenience: 'Convenience',
}

export function FilterPanel({ slotOptions, selected, onToggle }: FilterPanelProps) {
  return (
    <div className="flex h-fit flex-col gap-5 rounded border border-border bg-surface p-5">
      {SLOT_DIMENSIONS.map((dimension) => {
        const options = slotOptions[dimension] ?? []
        if (options.length === 0) return null

        return (
          <div key={dimension} className="flex flex-col gap-2">
            <span className="text-[13px] font-medium text-text-secondary">
              {DIMENSION_LABELS[dimension]}
            </span>
            <div className="flex flex-wrap gap-2">
              {options.map((value) => (
                <Chip
                  key={value}
                  label={value}
                  selected={selected[dimension].has(value)}
                  onClick={() => onToggle(dimension, value)}
                />
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}
