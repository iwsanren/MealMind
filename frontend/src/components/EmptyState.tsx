interface EmptyStateProps {
  message: string
}

export function EmptyState({ message }: EmptyStateProps) {
  return (
    <div className="flex items-center justify-center rounded border border-border bg-surface py-16 text-text-secondary">
      {message}
    </div>
  )
}
