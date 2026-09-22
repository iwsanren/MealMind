interface ChipProps {
  label: string
  selected?: boolean
  onClick?: () => void
}

export function Chip({ label, selected = false, onClick }: ChipProps) {
  const interactive = onClick !== undefined

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!interactive}
      className={[
        'rounded-sm border px-3 py-1 text-[13px] font-medium transition-colors',
        selected
          ? 'border-accent bg-accent-subtle text-accent'
          : 'border-border bg-surface text-text-secondary',
        interactive ? 'cursor-pointer hover:border-accent' : 'cursor-default',
      ].join(' ')}
    >
      {label}
    </button>
  )
}
