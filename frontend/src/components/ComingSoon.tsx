interface ComingSoonProps {
  title: string
  description: string
}

export function ComingSoon({ title, description }: ComingSoonProps) {
  return (
    <div className="mx-auto flex max-w-5xl flex-col px-6 py-10">
      <div className="flex flex-col items-center gap-2 rounded border border-dashed border-border bg-surface px-6 py-16 text-center">
        <h1 className="text-[20px] font-semibold">{title}</h1>
        <p className="text-text-secondary">{description}</p>
      </div>
    </div>
  )
}
