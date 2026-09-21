import { useState } from 'react'
import { submitFeedback } from '../api/feedback'
import { ApiError } from '../lib/apiClient'
import { getOrCreateSessionId } from '../lib/session'
import type { FeedbackRequest } from '../types/feedback'
import { Button } from './Button'

interface FeedbackButtonsProps {
  mealId: number
  sessionId?: string
}

export function FeedbackButtons({ mealId, sessionId }: FeedbackButtonsProps) {
  const [feedbackGiven, setFeedbackGiven] = useState<FeedbackRequest['action'] | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleFeedback(action: FeedbackRequest['action']) {
    setSubmitting(true)
    setError(null)
    try {
      const resolvedSessionId = sessionId ?? (await getOrCreateSessionId())
      await submitFeedback({
        sessionId: resolvedSessionId,
        itemId: mealId,
        action,
        rating: action === 'LIKE' ? 5 : 2,
        reason: '',
      })
      setFeedbackGiven(action)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to submit feedback.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex gap-2">
        <Button
          variant={feedbackGiven === 'LIKE' ? 'secondary' : 'ghost'}
          disabled={submitting}
          onClick={() => handleFeedback('LIKE')}
        >
          Like
        </Button>
        <Button
          variant={feedbackGiven === 'DISLIKE' ? 'secondary' : 'ghost'}
          disabled={submitting}
          onClick={() => handleFeedback('DISLIKE')}
        >
          Dislike
        </Button>
      </div>
      {error && <span className="text-[12px] text-danger">{error}</span>}
    </div>
  )
}
