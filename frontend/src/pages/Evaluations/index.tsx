import { useEffect, useState } from 'react'
import { getEvaluationRun, listEvaluationRuns } from '../../api/evaluations'
import { EmptyState } from '../../components/EmptyState'
import { ApiError } from '../../lib/apiClient'
import type { EvaluationRun, EvaluationRunSummary } from '../../types/evaluation'
import { BreakdownSection } from './BreakdownSection'
import { CasesPanel } from './CasesPanel'
import { formatDate } from './format'
import { HeadlineChart } from './HeadlineChart'
import { MetricsTable } from './MetricsTable'

/** Shown for anything that is not explicitly real data: the numbers must never be read as real-world accuracy. */
function DataBanner({ run }: { run: EvaluationRun }) {
  if (run.dataKind === 'real') return null
  return (
    <div role="note" className="rounded border border-danger border-l-4 bg-danger-subtle p-4 text-[13px]">
      <div className="mb-1 font-semibold text-danger">
        {run.dataKind === 'synthetic' ? 'Synthetic data' : 'Origin of the data not stated'}
      </div>
      <p>{run.dataNote}</p>
    </div>
  )
}

export function EvaluationsPage() {
  const [runs, setRuns] = useState<EvaluationRunSummary[] | null>(null)
  const [runId, setRunId] = useState<string | null>(null)
  const [run, setRun] = useState<EvaluationRun | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listEvaluationRuns()
      .then((list) => {
        setRuns(list)
        setRunId(list[0]?.runId ?? null)
      })
      .catch((err: unknown) => {
        setRuns([])
        setError(err instanceof ApiError ? err.message : 'Failed to load the evaluation runs.')
      })
  }, [])

  useEffect(() => {
    if (!runId) return
    let cancelled = false
    setRun(null)
    getEvaluationRun(runId)
      .then((r) => {
        if (!cancelled) {
          setRun(r)
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Failed to load this run.')
      })
    return () => {
      cancelled = true
    }
  }, [runId])

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-8 px-6 py-10">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1>Evaluations</h1>
          <p className="text-text-secondary">How a single model call, a verified call and the agent compare on a fixed set of cases.</p>
        </div>
        {runs && runs.length > 0 && (
          <label className="flex flex-col gap-1 text-[12px] text-text-secondary">
            Run
            <select
              value={runId ?? ''}
              onChange={(e) => setRunId(e.target.value)}
              className="rounded-sm border border-border bg-surface px-3 py-2 text-[13px] text-text-primary focus:border-accent focus:outline-none"
            >
              {runs.map((r) => (
                <option key={r.runId} value={r.runId}>
                  {r.title} · {formatDate(r.createdAt)}
                </option>
              ))}
            </select>
          </label>
        )}
      </header>

      {error && <div className="rounded border border-danger bg-danger-subtle p-4 text-danger">{error}</div>}

      {runs && runs.length === 0 && !error && (
        <EmptyState message="No evaluation runs found. Run evaluation/run_eval.py to create one." />
      )}
      {runs === null && <p className="text-text-secondary">Loading…</p>}

      {run && (
        <>
          <DataBanner run={run} />

          <div className="flex flex-wrap gap-x-6 gap-y-1 text-[13px] text-text-secondary">
            <span>
              Model: <span className="text-text-primary">{run.meta.model ?? '—'}</span>
            </span>
            <span>
              Cases: <span className="text-text-primary">{run.meta.cases ?? '—'}</span> × {run.meta.repeats ?? '—'} repeats
            </span>
            {Object.entries(run.meta.promptVersions).map(([name, version]) => (
              <span key={name}>
                {name} prompt: <span className="text-text-primary">{version}</span>
              </span>
            ))}
            <span>
              Spent: <span className="text-text-primary">{run.meta.spentUsd === null ? '—' : `$${run.meta.spentUsd.toFixed(4)}`}</span>
            </span>
            <span>
              Run: <span className="font-mono text-[12px] text-text-primary">{run.runId}</span>
            </span>
          </div>

          <HeadlineChart run={run} />
          <MetricsTable run={run} />
          <BreakdownSection run={run} />

          {run.caveats.length > 0 && (
            <section className="flex flex-col gap-2 text-[13px]" aria-labelledby="caveats-title">
              <h2 id="caveats-title">Limits of this run</h2>
              <ul className="list-disc pl-5 text-text-secondary">
                {run.caveats.map((c) => (
                  <li key={c}>{c}</li>
                ))}
              </ul>
            </section>
          )}

          <CasesPanel key={run.runId} runId={run.runId} arms={run.arms} />
        </>
      )}
    </div>
  )
}

export default EvaluationsPage
