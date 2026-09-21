export interface FeedbackRequest {
  sessionId: string
  itemId: number
  action: 'LIKE' | 'DISLIKE'
  rating: number
  reason: string
}
