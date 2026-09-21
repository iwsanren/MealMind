import { FeedbackButtons } from '../../components/FeedbackButtons'
import { MealCard } from '../../components/MealCard'
import type { MealResponse } from '../../types/meal'

export interface ChatMessageData {
  id: string
  role: 'user' | 'assistant'
  text: string
  meals?: MealResponse[]
}

interface ChatMessageProps {
  message: ChatMessageData
  sessionId: string | null
}

export function ChatMessage({ message, sessionId }: ChatMessageProps) {
  const isUser = message.role === 'user'

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
