import type { EvaluationRun } from '../../types/evaluation'
import { percent } from './format'

function Breakdown({ run }: { run: EvaluationRun }) {
  const breakdown = run.breakdown
  if (!breakdown) return null
  return (
    <section className="flex flex-col gap-3" aria-labelledby="breakdown-title">
      <div>
        <h2 id="breakdown-title">By {breakdown.dimension}</h2>
        <p className="text-[12px] text-text-secondary">Unsafe runs and correct runs, out of the runs in that category.</p>
      </div>
      <div className="overflow-x-auto rounded border border-border bg-surface">
        <table className="w-full text-left text-[13px]">
          <thead>
            <tr className="border-b border-border text-text-secondary">
              <th className="px-3 py-2 font-medium">Category</th>
              {run.arms.map((arm) => (
                <th key={arm.id} className="px-3 py-2 font-medium">
                  {arm.id} · unsafe / correct
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {breakdown.groups.map((group) => (
              <tr key={group.key} className="border-b border-border last:border-b-0">
                <td className="px-3 py-2 font-mono text-[12px]">{group.key}</td>
                {run.arms.map((arm) => {
                  const counts = group.arms[arm.id]
                  if (!counts) return <td key={arm.id} className="px-3 py-2 text-text-secondary">—</td>
                  const unsafe = counts.unsafe ?? 0
                  return (
                    <td key={arm.id} className="px-3 py-2 tabular-nums">
                      <span className={unsafe > 0 ? 'font-semibold text-danger' : ''}>{unsafe}</span>
                      {' / '}
                      {counts.correct_outcome ?? 0}
                      <span className="text-text-secondary"> of {counts.runs}</span>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function Comparisons({ run }: { run: EvaluationRun }) {
  if (run.comparisons.length === 0) return null
  const label = (metricKey: string) => run.metrics.find((m) => m.key === metricKey)?.label ?? metricKey
  return (
    <details className="rounded border border-border bg-surface p-4">
      <summary className="cursor-pointer text-[14px] font-medium">Paired comparisons between arms</summary>
      <p className="mt-2 text-[12px] text-text-secondary">
        Per case, the average over its repeats is compared between two arms. The difference is left minus right; the
        interval is a bootstrap 95% interval over cases.
      </p>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-left text-[13px]">
          <thead>
            <tr className="border-b border-border text-text-secondary">
              <th className="px-3 py-2 font-medium">Comparison</th>
              <th className="px-3 py-2 font-medium">Metric</th>
              <th className="px-3 py-2 font-medium">Difference</th>
              <th className="px-3 py-2 font-medium">Cases where left / right is higher / equal</th>
            </tr>
          </thead>
          <tbody>
            {run.comparisons.map((c) => (
              <tr key={`${c.left}-${c.right}-${c.metric}`} className="border-b border-border last:border-b-0">
                <td className="px-3 py-2">
                  {c.left} vs {c.right}
                </td>
                <td className="px-3 py-2">{label(c.metric)}</td>
                <td className="px-3 py-2 tabular-nums">
                  {percent(c.meanDiff)}{' '}
                  <span className="text-text-secondary">
                    ({percent(c.ciLow)} to {percent(c.ciHigh)})
                  </span>
                </td>
                <td className="px-3 py-2 tabular-nums">
                  {c.leftHigher} / {c.rightHigher} / {c.equal} <span className="text-text-secondary">of {c.cases}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}

function GateSection({ run }: { run: EvaluationRun }) {
  const gate = run.gate
  if (!gate) return null
  return (
    <section className="rounded border border-border bg-surface p-4 text-[13px]">
      <h3 className="mb-1 text-[15px]">{gate.name}</h3>
      <p className="text-text-secondary">
        This check runs before any arm. Cases it stops are not compared, and are listed here instead.
      </p>
      <ul className="mt-2 flex flex-col gap-1">
        <li>
          Stopped {gate.blockedCaseIds.length} case(s): <span className="font-mono text-[12px]">{gate.blockedCaseIds.join(', ') || '—'}</span>
        </li>
        <li>
          Stopped although the message was harmless (false positives):{' '}
          <span className={gate.falsePositives.length > 0 ? 'font-semibold text-danger' : ''}>
            {gate.falsePositives.length}
          </span>{' '}
          <span className="font-mono text-[12px]">{gate.falsePositives.join(', ')}</span>
        </li>
        <li>
          Let through although it should have been stopped (false negatives): {gate.falseNegatives.length}{' '}
          <span className="font-mono text-[12px]">{gate.falseNegatives.join(', ')}</span>
        </li>
      </ul>
    </section>
  )
}

export function BreakdownSection({ run }: { run: EvaluationRun }) {
  return (
    <>
      <Breakdown run={run} />
      <Comparisons run={run} />
      <GateSection run={run} />
    </>
  )
}
