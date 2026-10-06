import { useEffect, useState } from 'react'
import { getEvaluationCase, getEvaluationCases } from '../../api/evaluations'
import { EmptyState } from '../../components/EmptyState'
import type { ArmResult, CaseConstraints, CaseDetail, CasePage, CaseRepeat } from '../../types/evaluation'
import { formatMoney } from '../../lib/formatMoney'
import { armColor } from './format'

function constraintChips(c: CaseConstraints | null, feasible: boolean | null): string[] {
  const chips: string[] = []
  if (c) {
    if (c.maxPrice !== null) chips.push(`budget ≤ ${formatMoney(c.maxPrice)}`)
    c.excludeAllergens.forEach((a) => chips.push(`no ${a}`))
    if (c.requiresHighProtein) chips.push('high protein')
    if (c.minProteinG !== null) chips.push(`≥ ${c.minProteinG} g protein`)
    if (c.mealTime) chips.push(c.mealTime.toLowerCase())
  }
  if (feasible === true) chips.push('a valid meal exists')
  if (feasible === false) chips.push('no meal can satisfy these limits')
  return chips
}

function Badge({ text, tone }: { text: string; tone: 'bad' | 'good' | 'neutral' }) {
  const classes =
    tone === 'bad'
      ? 'bg-danger-subtle text-danger'
      : tone === 'good'
        ? 'bg-accent-subtle text-accent'
        : 'border border-border bg-surface text-text-secondary'
  return <span className={`inline-flex rounded-sm px-2 py-0.5 text-[12px] font-medium ${classes}`}>{text}</span>
}

function RepeatView({ repeat }: { repeat: CaseRepeat }) {
  const rec = repeat.recommendation
  return (
    <div className="flex flex-col gap-2 rounded border border-border p-3 text-[13px]">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">Repeat {repeat.repeat + 1}</span>
        <Badge text={repeat.status} tone="neutral" />
        {repeat.unsafe && <Badge text="Unsafe" tone="bad" />}
        {repeat.correctOutcome && <Badge text="Correct outcome" tone="good" />}
        {repeat.falseNoMatch && <Badge text="Refused although a meal existed" tone="bad" />}
        {!repeat.shown && <Badge text="Answer withheld" tone="neutral" />}
        {repeat.issues.map((issue) => (
          <Badge key={issue} text={issue} tone="bad" />
        ))}
        <span className="ml-auto text-[12px] text-text-secondary">
          {repeat.llmCalls} model call(s) · ${repeat.costUsd.toFixed(5)}
        </span>
      </div>
      {rec ? (
        <div>
          <div className="font-medium">{rec.mealName ?? 'No meal recommended'}</div>
          {rec.reason && <p className="text-text-secondary">{rec.reason}</p>}
          {rec.noMatchReason && <p className="text-text-secondary">Why nothing fits: {rec.noMatchReason}</p>}
        </div>
      ) : (
        <p className="text-text-secondary">No answer was shown to the user for this run.</p>
      )}
      {repeat.timeline.length > 0 && (
        <details>
          <summary className="cursor-pointer text-[12px] text-text-secondary">What the agent did ({repeat.timeline.length} steps)</summary>
          <ol className="mt-2 flex flex-col gap-0.5 font-mono text-[12px]">
            {repeat.timeline.map((step, index) => (
              <li key={index}>
                <span className="text-text-secondary">{step.round !== null ? `R${step.round}` : '  '}</span> {step.label}
              </li>
            ))}
          </ol>
        </details>
      )}
    </div>
  )
}

function DetailView({ detail, arms }: { detail: CaseDetail; arms: ArmResult[] }) {
  const chips = constraintChips(detail.constraints, detail.feasibleExists)
  return (
    <div className="flex flex-col gap-4 rounded border border-border bg-surface p-5">
      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <h3 className="font-mono text-[15px]">{detail.caseId}</h3>
          <Badge text={detail.category} tone="neutral" />
        </div>
        {detail.userMessage && <p className="text-[14px]">“{detail.userMessage}”</p>}
        <div className="flex flex-wrap gap-2">
          {chips.map((chip) => (
            <Badge key={chip} text={chip} tone="neutral" />
          ))}
        </div>
      </div>
      {detail.arms.map((arm) => {
        const index = arms.findIndex((a) => a.id === arm.id)
        return (
          <div key={arm.id} className="flex flex-col gap-2">
            <div className="flex items-center gap-2 text-[13px]">
              <span className="h-2.5 w-2.5 rounded-[3px]" style={{ backgroundColor: armColor(arm.id, Math.max(index, 0)) }} />
              <span className="font-medium">{arm.id}</span>
              <span className="text-text-secondary">{arm.label}</span>
            </div>
            {arm.repeats.map((repeat) => (
              <RepeatView key={repeat.repeat} repeat={repeat} />
            ))}
          </div>
        )
      })}
    </div>
  )
}

export function CasesPanel({ runId, arms }: { runId: string; arms: ArmResult[] }) {
  const [category, setCategory] = useState('')
  const [page, setPage] = useState<CasePage | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<CaseDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setPage(null)
    setSelected(null)
    setDetail(null)
    getEvaluationCases(runId, category || undefined)
      .then((p) => {
        if (!cancelled) {
          setPage(p)
          setError(null)
        }
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load the cases.')
      })
    return () => {
      cancelled = true
    }
  }, [runId, category])

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    setDetail(null)
    getEvaluationCase(runId, selected)
      .then((d) => {
        if (!cancelled) setDetail(d)
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load the case.')
      })
    return () => {
      cancelled = true
    }
  }, [runId, selected])

  return (
    <section className="flex flex-col gap-3" aria-labelledby="cases-title">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="cases-title">Cases</h2>
          <p className="text-[12px] text-text-secondary">
            Each case with how often each arm was unsafe (out of its repeats). Select one to read every answer.
          </p>
        </div>
        <label className="flex flex-col gap-1 text-[12px] text-text-secondary">
          Category
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            className="rounded-sm border border-border bg-surface px-3 py-2 text-[13px] text-text-primary focus:border-accent focus:outline-none"
          >
            <option value="">All categories</option>
            {(page?.categories ?? []).map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
      </div>

      {error && <div className="rounded border border-danger bg-danger-subtle p-3 text-[13px] text-danger">{error}</div>}

      {page && page.items.length === 0 && <EmptyState message="No cases in this category." />}
      {page && page.items.length > 0 && (
        <div className="overflow-x-auto rounded border border-border bg-surface">
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-border text-text-secondary">
                <th className="px-3 py-2 font-medium">Case</th>
                <th className="px-3 py-2 font-medium">Category</th>
                <th className="px-3 py-2 font-medium">Request</th>
                {arms.map((arm) => (
                  <th key={arm.id} className="px-3 py-2 font-medium">
                    {arm.id} unsafe
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {page.items.map((row) => (
                <tr
                  key={row.caseId}
                  tabIndex={0}
                  onClick={() => setSelected(row.caseId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') setSelected(row.caseId)
                  }}
                  className={[
                    'cursor-pointer border-b border-border last:border-b-0 hover:bg-bg',
                    selected === row.caseId ? 'bg-accent-subtle' : '',
                  ].join(' ')}
                >
                  <td className="px-3 py-2 font-mono text-[12px]">{row.caseId}</td>
                  <td className="px-3 py-2">{row.category}</td>
                  <td className="max-w-[320px] truncate px-3 py-2 text-text-secondary" title={row.userMessage ?? ''}>
                    {row.userMessage ?? '—'}
                  </td>
                  {arms.map((arm) => {
                    const counts = row.arms[arm.id]
                    return (
                      <td key={arm.id} className="px-3 py-2 tabular-nums">
                        {counts ? (
                          <span className={counts.unsafe > 0 ? 'font-semibold text-danger' : ''}>
                            {counts.unsafe}/{counts.runs}
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {page && <p className="text-[12px] text-text-secondary">{page.total} case(s) in this view.</p>}

      {selected && !detail && !error && <p className="text-text-secondary">Loading case…</p>}
      {detail && <DetailView detail={detail} arms={arms} />}
    </section>
  )
}
