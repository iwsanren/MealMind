import { apiFetch } from '../lib/apiClient'
import type { FeedbackRequest } from '../types/feedback'

export function submitFeedback(payload: FeedbackRequest): Promise<void> {
  return apiFetch<void>('/api/v1/feedback', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
