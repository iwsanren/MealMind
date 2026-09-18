export const SLOT_DIMENSIONS = [
  'mealTime',
  'mood',
  'scene',
  'healthGoal',
  'cuisine',
  'taste',
  'convenience',
] as const

export type SlotDimension = (typeof SLOT_DIMENSIONS)[number]

export type SlotOptionsMap = Record<SlotDimension, string[]>

export interface MealResponse {
  id: number
  sourceType: string
  name: string
  mealTime: string[]
  mood: string[]
  scene: string[]
  healthGoal: string[]
  cuisine: string[]
  taste: string[]
  convenience: string[]
  matchScore: number
}
