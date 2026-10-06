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

export const DIMENSION_LABELS: Record<SlotDimension, string> = {
  mealTime: 'Meal Time',
  mood: 'Mood',
  scene: 'Scene',
  healthGoal: 'Health Goal',
  cuisine: 'Cuisine',
  taste: 'Taste',
  convenience: 'Convenience',
}

/** Closed allergen vocabulary; must match backend enums/Allergen.java. */
export const ALLERGENS = [
  'milk',
  'egg',
  'fish',
  'shellfish',
  'tree_nut',
  'peanut',
  'wheat',
  'soy',
  'sesame',
] as const

export type Allergen = (typeof ALLERGENS)[number]

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
  /** EUR per serving; null = unknown */
  price: number | null
  /** grams per serving; null = unknown */
  proteinG: number | null
  /** kcal per serving; null = unknown */
  calories: number | null
  /** null = unknown, [] = known to contain none */
  allergens: string[] | null
  matchScore: number
}

export interface MealRequest {
  name: string
  mealTime: string[]
  mood: string[]
  scene: string[]
  healthGoal: string[]
  cuisine: string[]
  taste: string[]
  convenience: string[]
  price: number | null
  proteinG: number | null
  calories: number | null
  allergens: string[] | null
}
