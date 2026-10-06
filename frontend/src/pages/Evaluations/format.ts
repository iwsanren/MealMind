import type { MetricDefinition, MetricValue } from '../../types/evaluation'

/** Categorical slots 1-3 of the validated palette (light surface). Colour follows the arm, never its position in a list. */
const ARM_COLORS: Record<string, string> = { A: '#2a78d6', B: '#eb6834', C: '#1baf7a' }
const FALLBACK_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948']

export function armColor(armId: string, index: number): string {
  return ARM_COLORS[armId] ?? FALLBACK_COLORS[index % FALLBACK_COLORS.length]
}

export function percent(value: number | null, digits = 1): string {
  return value === null ? '—' : `${(value * 100).toFixed(digits)}%`
}

/** "30.4%" for a rate, "$0.00248" / "4.46 calls" for a mean. */
export function formatMetric(definition: MetricDefinition, metric: MetricValue | undefined): string {
  if (!metric || metric.value === null) return '—'
  if (definition.type === 'rate') return percent(metric.value)
  if (definition.unit === 'USD') return `$${metric.value.toFixed(5)}`
  return `${metric.value.toFixed(2)} ${definition.unit}`
}

/** "31/102 · 95% CI 22.3–39.9%" for a rate; empty for a mean. */
export function formatDetail(metric: MetricValue | undefined): string {
  if (!metric || metric.k === null || metric.n === null) return ''
  const ci = metric.ciLow !== null && metric.ciHigh !== null ? ` · 95% CI ${percent(metric.ciLow)}–${percent(metric.ciHigh)}` : ''
  return `${metric.k}/${metric.n}${ci}`
}

export function goalHint(goal: MetricDefinition['goal']): string {
  if (goal === 'lower_is_better') return 'lower is better'
  if (goal === 'higher_is_better') return 'higher is better'
  return ''
}

export function formatDate(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
