import { apiFetch } from '../lib/apiClient'
import type { MealResponse } from '../types/meal'

interface CreateSessionResponse {
  sessionId: string
}

export function createSession(sourceMode?: 'PERSONAL' | 'PUBLIC'): Promise<CreateSessionResponse> {
  const query = sourceMode ? `?sourceMode=${sourceMode}` : ''
  return apiFetch<CreateSessionResponse>(`/api/v1/sessions${query}`, { method: 'POST' })
}

/** AGENT = verified answer from the AI agent; RULES = rule-based recommender; RISK_GUARD = fixed safety reply. */
export type RecommendSource = 'AGENT' | 'RULES' | 'RISK_GUARD'

export interface RecommendResponse {
  sessionId: string
  source: RecommendSource
  /** Why RULES was used instead of the agent (null for AGENT and RISK_GUARD). */
  fallbackReason: string | null
  text: string
  meals: MealResponse[]
  /** Id of the stored agent trace (see the Trace page); null unless source is AGENT. */
  traceId: string | null
}

export function recommend(sessionId: string, message: string): Promise<RecommendResponse> {
  return apiFetch<RecommendResponse>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/recommend`, {
    method: 'POST',
    body: JSON.stringify({ message }),
  })
}
