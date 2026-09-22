const API_BASE_URL = ''
const USER_ID_KEY = 'mealmind.userId'
export const USER_ID_CHANGE_EVENT = 'mealmind:userid-changed'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export function getUserId(): string {
  return localStorage.getItem(USER_ID_KEY) || '1'
}

export function setUserId(value: string): string {
  const normalized = value.trim() || '1'
  localStorage.setItem(USER_ID_KEY, normalized)
  window.dispatchEvent(new Event(USER_ID_CHANGE_EVENT))
  return normalized
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': getUserId(),
      ...init?.headers,
    },
  })

  if (!res.ok) {
    throw new ApiError(res.status, `Request to ${path} failed with status ${res.status}`)
  }

  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
