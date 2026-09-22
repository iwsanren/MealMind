import { Chip } from './Chip'
import { DIMENSION_LABELS, type SlotDimension } from '../types/meal'

interface DimensionChipGroupProps {
  dimension: SlotDimension
  options: string[]
  selected: Set<string>
  onToggle: (value: string) => void
  required?: boolean
  error?: string
}

export function DimensionChipGroup({
  dimension,
  options,
  selected,
  onToggle,
  required = false,
  error,
}: DimensionChipGroupProps) {
  if (options.length === 0) return null

  return (
    <div className="flex flex-col gap-2">
      <span className="text-[13px] font-medium text-text-secondary">
        {DIMENSION_LABELS[dimension]}
        {required && <span className="text-danger"> *</span>}
      </span>
      <div className="flex flex-wrap gap-2">
        {options.map((value) => (
          <Chip
            key={value}
            label={value}
            selected={selected.has(value)}
            onClick={() => onToggle(value)}
          />
        ))}
      </div>
      {error && <span className="text-[12px] text-danger">{error}</span>}
    </div>
  )
}
