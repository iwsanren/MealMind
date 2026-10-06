// Shapes of GET /api/v1/evaluations (see backend dto/evaluation). The page is written against these, not against any file
// format: arms and metrics are lists, and the data's origin is stated by dataKind.

export type DataKind = 'synthetic' | 'real' | 'unknown'

export interface EvaluationRunSummary {
  runId: string
  source: string
  dataKind: DataKind
  title: string
  createdAt: string | null
  model: string | null
  cases: number | null
  repeats: number | null
}

export interface MetricDefinition {
  key: string
  label: string
  description: string
  /** "rate" = k out of n with an interval, "mean" = one number */
  type: 'rate' | 'mean'
  unit: string
  goal: 'lower_is_better' | 'higher_is_better' | 'neutral'
  headline: boolean
}

export interface MetricValue {
  k: number | null
  n: number | null
  value: number | null
  ciLow: number | null
  ciHigh: number | null
}

export interface ArmResult {
  id: string
  label: string
  runs: number
  metrics: Record<string, MetricValue>
}

export interface BreakdownGroup {
  key: string
  /** arm id -> counts ("runs" plus one entry per Breakdown.countKeys) */
  arms: Record<string, Record<string, number>>
}

export interface Breakdown {
  dimension: string
  countKeys: string[]
  groups: BreakdownGroup[]
}

export interface Comparison {
  left: string
  right: string
  metric: string
  cases: number
  meanDiff: number
  ciLow: number
  ciHigh: number
  leftHigher: number
  rightHigher: number
  equal: number
}

export interface Gate {
  name: string
  blockedCaseIds: string[]
  falsePositives: string[]
  falseNegatives: string[]
}

export interface EvaluationRun {
  schemaVersion: number
  runId: string
  source: string
  dataKind: DataKind
  dataNote: string
  meta: {
    model: string | null
    temperature: number | null
    promptVersions: Record<string, string>
    spentUsd: number | null
    cases: number | null
    repeats: number | null
    createdAt: string | null
  }
  metrics: MetricDefinition[]
  arms: ArmResult[]
  breakdown: Breakdown | null
  comparisons: Comparison[]
  gate: Gate | null
  caveats: string[]
}

export interface CaseConstraints {
  maxPrice: number | null
  excludeAllergens: string[]
  requiresHighProtein: boolean
  minProteinG: number | null
  mealTime: string | null
}

export interface ArmCounts {
  runs: number
  unsafe: number
  correctOutcome: number
}

export interface CaseRow {
  caseId: string
  category: string
  userMessage: string | null
  constraints: CaseConstraints | null
  feasibleExists: boolean | null
  arms: Record<string, ArmCounts>
}

export interface CasePage {
  total: number
  limit: number
  offset: number
  categories: string[]
  items: CaseRow[]
}

export interface TimelineStep {
  type: string
  round: number | null
  label: string
}

export interface CaseRepeat {
  repeat: number
  status: string
  shown: boolean
  unsafe: boolean
  correctOutcome: boolean
  hardViolation: boolean
  claimFail: boolean
  hallucinatedDish: boolean
  falseNoMatch: boolean
  issues: string[]
  recommendation: { mealId: number | null; mealName: string | null; reason: string | null; noMatchReason: string | null } | null
  llmCalls: number
  costUsd: number
  traceId: string | null
  timeline: TimelineStep[]
}

export interface CaseDetail {
  caseId: string
  category: string
  userMessage: string | null
  constraints: CaseConstraints | null
  feasibleExists: boolean | null
  arms: { id: string; label: string; repeats: CaseRepeat[] }[]
}
