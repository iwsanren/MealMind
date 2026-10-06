import type { ArmResult, EvaluationRun, MetricDefinition } from '../../types/evaluation'
import { armColor, formatDetail, goalHint, percent } from './format'

const TICKS = [0, 0.25, 0.5, 0.75, 1]
const LABEL_ROOM = 'mr-[130px]' // space to the right of the 100% line, so a value label never overflows

function Legend({ arms }: { arms: ArmResult[] }) {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-1 text-[13px] text-text-secondary">
      {arms.map((arm, index) => (
        <li key={arm.id} className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-[3px]" style={{ backgroundColor: armColor(arm.id, index) }} />
          <span className="font-medium text-text-primary">{arm.id}</span> {arm.label}
        </li>
      ))}
    </ul>
  )
}

function Gridlines() {
  return (
    <>
      {TICKS.map((tick) => (
        <span key={tick} className="absolute bottom-0 top-0 w-px bg-border" style={{ left: `${tick * 100}%` }} />
      ))}
    </>
  )
}

function BarRow({ arm, index, definition }: { arm: ArmResult; index: number; definition: MetricDefinition }) {
  const metric = arm.metrics[definition.key]
  if (!metric || metric.value === null) return null
  const share = Math.max(0, Math.min(1, metric.value))
  // a non-zero value always gets a visible bar, even when it is under one pixel's worth of the scale
  const width = share === 0 ? '0%' : `max(${share * 100}%, 3px)`

  return (
    <div
      tabIndex={0}
      className="group relative flex items-center gap-3 rounded-sm py-[1px] outline-none focus-visible:ring-2 focus-visible:ring-accent"
      aria-label={`${arm.label}: ${definition.label} ${percent(metric.value)} (${formatDetail(metric)})`}
    >
      <span className="w-40 shrink-0 truncate text-[12px] text-text-secondary" title={arm.label}>
        <span className="font-medium text-text-primary">{arm.id}</span> {arm.label}
      </span>
      <div className={`relative h-[18px] flex-1 ${LABEL_ROOM}`}>
        <Gridlines />
        <span
          className="absolute left-0 top-[2px] h-[14px] rounded-r-[4px]"
          style={{ width, backgroundColor: armColor(arm.id, index) }}
        />
        <span
          className="absolute top-0 flex h-[18px] items-center gap-1.5 whitespace-nowrap bg-surface px-1 text-[12px]"
          style={{ left: `calc(${share * 100}% + 8px)` }}
        >
          <span className="font-semibold text-text-primary">{percent(metric.value)}</span>
          <span className="text-text-secondary">
            {metric.k}/{metric.n}
          </span>
        </span>
      </div>
      <div className="pointer-events-none absolute left-44 top-full z-10 mt-1 hidden whitespace-nowrap rounded border border-border bg-surface px-3 py-2 text-[12px] text-text-primary group-hover:block group-focus-visible:block">
        <div className="font-medium">
          {arm.id} · {definition.label}
        </div>
        <div>
          {percent(metric.value)} ({formatDetail(metric)})
        </div>
      </div>
    </div>
  )
}

export function HeadlineChart({ run }: { run: EvaluationRun }) {
  const headline = run.metrics.filter((m) => m.headline)
  if (headline.length === 0) return null

  return (
    <section className="flex flex-col gap-6 rounded border border-border bg-surface p-5" aria-labelledby="headline-title">
      <div className="flex flex-col gap-3">
        <h2 id="headline-title">Headline results</h2>
        <Legend arms={run.arms} />
      </div>

      {headline.map((definition) => (
        <div key={definition.key} className="flex flex-col gap-2">
          <div className="flex flex-wrap items-baseline gap-x-3">
            <h3 className="text-[15px]">{definition.label}</h3>
            {goalHint(definition.goal) && <span className="text-[12px] text-text-secondary">{goalHint(definition.goal)}</span>}
          </div>
          <p className="text-[12px] text-text-secondary">{definition.description}</p>

          <div className="flex flex-col gap-[2px]">
            <div className="flex items-center gap-3 text-[11px] text-text-secondary">
              <span className="w-40 shrink-0" />
              <div className={`relative h-4 flex-1 ${LABEL_ROOM}`}>
                {TICKS.map((tick) => (
                  <span
                    key={tick}
                    className="absolute -translate-x-1/2"
                    style={{ left: `${tick * 100}%` }}
                  >
                    {tick * 100}%
                  </span>
                ))}
              </div>
            </div>
            {run.arms.map((arm, index) => (
              <BarRow key={arm.id} arm={arm} index={index} definition={definition} />
            ))}
          </div>
        </div>
      ))}
    </section>
  )
}
