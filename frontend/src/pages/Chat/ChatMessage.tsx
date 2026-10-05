import type { RecommendSource } from '../../api/session'
import { FeedbackButtons } from '../../components/FeedbackButtons'
import { MealCard } from '../../components/MealCard'
import type { MealResponse } from '../../types/meal'

export interface ChatMessageData {
  id: string
  role: 'user' | 'assistant'
  text: string
  meals?: MealResponse[]
  source?: RecommendSource
  fallbackReason?: string | null
  traceId?: string | null
}

interface ChatMessageProps {
  message: ChatMessageData
  sessionId: string | null
}

/** One line saying who produced the answer, so a fallback is never mistaken for the agent. */
export function sourceLabel(message: ChatMessageData): string | null {
  if (message.source === 'AGENT') {
    return message.traceId ? `AI agent · trace ${message.traceId}` : 'AI agent'
  }
  if (message.source === 'RULES') {
    if (message.fallbackReason === 'AGENT_DISABLED' || message.fallbackReason === 'DISABLED_BY_REQUEST') {
      return 'Rule-based answer (AI agent is off)'
    }
    return `Rule-based answer · the AI agent was unavailable (${message.fallbackReason ?? 'unknown reason'})`
  }
  return null
}

export function ChatMessage({ message, sessionId }: ChatMessageProps) {
  const isUser = message.role === 'user'
  const label = sourceLabel(message)

  return (
    <div className={`flex flex-col gap-3 ${isUser ? 'items-end' : 'items-start'}`}>
      <div
        className={[
          'max-w-[80%] rounded px-4 py-2 text-[14px]',
          isUser ? 'bg-accent text-white' : 'bg-accent-subtle text-text-primary',
        ].join(' ')}
      >
        {message.text}
      </div>

      {label && <div className="text-[12px] text-text-secondary">{label}</div>}

      {message.meals && message.meals.length > 0 && (
        <div className="grid w-full grid-cols-1 gap-4 sm:grid-cols-2">
          {message.meals.map((meal) => (
            <MealCard
              key={meal.id}
              meal={meal}
              actions={<FeedbackButtons mealId={meal.id} sessionId={sessionId ?? undefined} />}
            />
          ))}
        </div>
      )}
    </div>
  )
}
