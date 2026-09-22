import { DimensionChipGroup } from '../../components/DimensionChipGroup'
import { SLOT_DIMENSIONS, type SlotDimension, type SlotOptionsMap } from '../../types/meal'
import type { SelectedTags } from './useMealFilters'

interface FilterPanelProps {
  slotOptions: SlotOptionsMap
  selected: SelectedTags
  onToggle: (dimension: SlotDimension, value: string) => void
}

export function FilterPanel({ slotOptions, selected, onToggle }: FilterPanelProps) {
  return (
    <div className="flex h-fit flex-col gap-5 rounded border border-border bg-surface p-5">
      {SLOT_DIMENSIONS.map((dimension) => (
        <DimensionChipGroup
          key={dimension}
          dimension={dimension}
          options={slotOptions[dimension] ?? []}
          selected={selected[dimension]}
          onToggle={(value) => onToggle(dimension, value)}
        />
      ))}
    </div>
  )
}
