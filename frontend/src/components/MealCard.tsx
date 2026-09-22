import type { ReactNode } from 'react'
import { Chip } from './Chip'
import { DIMENSION_LABELS, SLOT_DIMENSIONS, type MealResponse } from '../types/meal'

interface MealCardProps {
  meal: MealResponse
  actions?: ReactNode
  className?: string
}

export function MealCard({ meal, actions, className = '' }: MealCardProps) {
  const dimensionsWithTags = SLOT_DIMENSIONS.filter((dimension) => meal[dimension].length > 0)

  return (
    <div className={['flex flex-col gap-3 rounded border border-border bg-surface p-4', className].join(' ')}>
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-[16px] font-semibold">{meal.name}</h3>
        {actions && <div className="flex shrink-0 gap-2">{actions}</div>}
      </div>

      {dimensionsWithTags.length === 0 ? (
        <span className="text-[13px] text-text-secondary">No tags</span>
      ) : (
        <div className="flex flex-col gap-2">
          {dimensionsWithTags.map((dimension) => (
            <div key={dimension} className="flex flex-col gap-1">
              <span className="text-[12px] font-medium text-text-secondary">
                {DIMENSION_LABELS[dimension]}
              </span>
              <div className="flex flex-wrap gap-1.5">
                {meal[dimension].map((tag) => (
                  <Chip key={tag} label={tag} />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
