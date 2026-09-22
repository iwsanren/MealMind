import { apiFetch } from '../lib/apiClient'

interface CreateSessionResponse {
  sessionId: string
}

export function createSession(sourceMode?: 'PERSONAL' | 'PUBLIC'): Promise<CreateSessionResponse> {
  const query = sourceMode ? `?sourceMode=${sourceMode}` : ''
  return apiFetch<CreateSessionResponse>(`/api/v1/sessions${query}`, { method: 'POST' })
}
