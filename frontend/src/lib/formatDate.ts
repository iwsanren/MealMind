/**
 * Jackson may serialize LocalDateTime as an ISO string or as a
 * [year, month, day, hour, minute, second] array depending on config we
 * don't control here, so this accepts either shape without crashing.
 */
export function formatTraceDateTime(value: unknown): string {
  if (value == null) return '—'

  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0, second = 0] = value as number[]
    const date = new Date(year, month - 1, day, hour, minute, second)
    return Number.isNaN(date.getTime()) ? JSON.stringify(value) : date.toLocaleString()
  }

  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

/** Formats a Date as "YYYY-MM-DDTHH:mm:00", matching the backend's LocalDateTime parsing. */
export function toLocalDateTimeParam(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

/** Formats a Date as "YYYY-MM-DDTHH:mm" for an <input type="datetime-local"> value. */
export function toDateTimeInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
