import { apiFetch } from '../lib/apiClient'
import type { RequestTraceRow, TraceLabelRequest } from '../types/trace'

export function getTraceById(traceId: string): Promise<RequestTraceRow> {
  return apiFetch<RequestTraceRow>(`/api/v1/debug/traces/${encodeURIComponent(traceId)}`)
}

export function getTracesBySession(sessionId: string, limit?: number): Promise<RequestTraceRow[]> {
  const search = new URLSearchParams()
  if (limit) search.set('limit', String(limit))
  const query = search.toString() ? `?${search.toString()}` : ''
  return apiFetch<RequestTraceRow[]>(
    `/api/v1/debug/sessions/${encodeURIComponent(sessionId)}/traces${query}`,
  )
}

interface TimeRangeParams {
  startAt: string
  endAt: string
  onlyUnlabeled?: boolean
  limit?: number
}

export function getTracesByTimeRange(params: TimeRangeParams): Promise<RequestTraceRow[]> {
  const search = new URLSearchParams({ startAt: params.startAt, endAt: params.endAt })
  if (params.onlyUnlabeled) search.set('onlyUnlabeled', 'true')
  if (params.limit) search.set('limit', String(params.limit))
  return apiFetch<RequestTraceRow[]>(`/api/v1/debug/traces?${search.toString()}`)
}

export function labelTrace(traceId: string, payload: TraceLabelRequest): Promise<void> {
  return apiFetch<void>(`/api/v1/debug/traces/${encodeURIComponent(traceId)}/label`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
