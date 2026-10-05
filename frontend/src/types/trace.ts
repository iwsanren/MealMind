import type { MealRequest } from './meal'

export interface RequestTraceRow {
  id: number
  traceId: string
  sessionId: string
  userId: number
  status: string
  eventCount: number
  durationMs: number | null
  errorMessage: string | null
  traceJson: string
  createdAt: string
  updatedAt: string
  expectedIntent: string | null
  expectedSlots: string | null
  expectedClarifyAction: string | null
  labeledBy: number | null
  labeledAt: string | null
  labelNote: string | null
}

// Trace labels only describe the seven tag dimensions, not a meal's facts.
export type ExpectedSlots = Omit<MealRequest, 'name' | 'price' | 'proteinG' | 'calories' | 'allergens'>

export interface TraceLabelRequest {
  expectedIntent: string | null
  expectedSlots: ExpectedSlots | null
  expectedClarifyAction: string | null
  labelNote: string | null
}
