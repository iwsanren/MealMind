import { createSession } from '../api/session'
import { USER_ID_CHANGE_EVENT } from './apiClient'

const SESSION_ID_KEY = 'mealmind.sessionId'

let inFlight: Promise<string> | null = null

export async function getOrCreateSessionId(): Promise<string> {
  const cached = localStorage.getItem(SESSION_ID_KEY)
  if (cached) return cached

  if (!inFlight) {
    inFlight = createSession()
      .then(({ sessionId }) => {
        localStorage.setItem(SESSION_ID_KEY, sessionId)
        return sessionId
      })
      .finally(() => {
        inFlight = null
      })
  }

  return inFlight
}

window.addEventListener(USER_ID_CHANGE_EVENT, () => {
  localStorage.removeItem(SESSION_ID_KEY)
  inFlight = null
})
