import { apiFetch } from '../lib/apiClient'

interface CreateSessionResponse {
  sessionId: string
}

export function createSession(): Promise<CreateSessionResponse> {
  return apiFetch<CreateSessionResponse>('/api/v1/sessions', { method: 'POST' })
}
