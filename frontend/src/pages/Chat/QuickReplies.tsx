import { Chip } from '../../components/Chip'

const QUICK_QUESTIONS = [
  'Something quick for breakfast',
  'Low-fat dinner idea',
  'What should I eat right now',
  'Something spicy for lunch',
  'Light snack before bed',
]

interface QuickRepliesProps {
  onPick: (text: string) => void
}

export function QuickReplies({ onPick }: QuickRepliesProps) {
  return (
    <div className="flex h-fit flex-col gap-2 rounded border border-border bg-surface p-4">
      <span className="text-[13px] font-medium text-text-secondary">Quick questions</span>
      <div className="flex flex-col items-start gap-2">
        {QUICK_QUESTIONS.map((question) => (
          <Chip key={question} label={question} onClick={() => onPick(question)} />
        ))}
      </div>
    </div>
  )
}
