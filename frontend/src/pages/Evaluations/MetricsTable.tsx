import type { EvaluationRun } from '../../types/evaluation'
import { armColor, formatDetail, formatMetric, goalHint } from './format'

/** The table view of the chart: every metric of every arm, with its counts and interval. */
export function MetricsTable({ run }: { run: EvaluationRun }) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="metrics-title">
      <h2 id="metrics-title">All metrics</h2>
      <div className="overflow-x-auto rounded border border-border bg-surface">
        <table className="w-full text-left text-[13px]">
          <thead>
            <tr className="border-b border-border text-text-secondary">
              <th className="px-3 py-2 font-medium">Metric</th>
              {run.arms.map((arm, index) => (
                <th key={arm.id} className="px-3 py-2 font-medium">
                  <span className="mr-2 inline-block h-2.5 w-2.5 rounded-[3px]" style={{ backgroundColor: armColor(arm.id, index) }} />
                  <span className="text-text-primary">{arm.id}</span>
                  <span className="block text-[12px] font-normal">{arm.label}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {run.metrics.map((definition) => (
              <tr key={definition.key} className="border-b border-border last:border-b-0 align-top">
                <td className="px-3 py-2">
                  <div className={definition.headline ? 'font-semibold' : 'font-medium'}>{definition.label}</div>
                  <div className="text-[12px] text-text-secondary">{goalHint(definition.goal)}</div>
                </td>
                {run.arms.map((arm) => (
                  <td key={arm.id} className="px-3 py-2 tabular-nums">
                    <div className={definition.headline ? 'font-semibold' : ''}>{formatMetric(definition, arm.metrics[definition.key])}</div>
                    <div className="text-[12px] text-text-secondary">{formatDetail(arm.metrics[definition.key])}</div>
                  </td>
                ))}
              </tr>
            ))}
            <tr className="border-t border-border text-text-secondary">
              <td className="px-3 py-2">Runs</td>
              {run.arms.map((arm) => (
                <td key={arm.id} className="px-3 py-2 tabular-nums">
                  {arm.runs}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  )
}
