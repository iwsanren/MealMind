import { apiFetch } from '../lib/apiClient'
import type { CaseDetail, CasePage, EvaluationRun, EvaluationRunSummary } from '../types/evaluation'

export function listEvaluationRuns(): Promise<EvaluationRunSummary[]> {
  return apiFetch<EvaluationRunSummary[]>('/api/v1/evaluations')
}

export function getEvaluationRun(runId: string): Promise<EvaluationRun> {
  return apiFetch<EvaluationRun>(`/api/v1/evaluations/${encodeURIComponent(runId)}`)
}

export function getEvaluationCases(runId: string, category?: string, limit = 100): Promise<CasePage> {
  const search = new URLSearchParams({ limit: String(limit) })
  if (category) search.set('category', category)
  return apiFetch<CasePage>(`/api/v1/evaluations/${encodeURIComponent(runId)}/cases?${search.toString()}`)
}

export function getEvaluationCase(runId: string, caseId: string): Promise<CaseDetail> {
  return apiFetch<CaseDetail>(
    `/api/v1/evaluations/${encodeURIComponent(runId)}/cases/${encodeURIComponent(caseId)}`,
  )
}
