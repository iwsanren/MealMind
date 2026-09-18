import { Chip } from '../../components/Chip'
import { SLOT_DIMENSIONS, type MealResponse } from '../../types/meal'

interface MealCardProps {
  meal: MealResponse
}

export function MealCard({ meal }: MealCardProps) {
  const tags = SLOT_DIMENSIONS.flatMap((dimension) => meal[dimension])

  return (
    <div className="flex flex-col gap-3 rounded border border-border bg-surface p-4">
      <h3 className="text-[16px] font-semibold">{meal.name}</h3>
      <div className="flex flex-wrap gap-2">
        {tags.length === 0 ? (
          <span className="text-[13px] text-text-secondary">No tags</span>
        ) : (
          tags.map((tag, index) => <Chip key={`${tag}-${index}`} label={tag} />)
        )}
      </div>
    </div>
  )
}
