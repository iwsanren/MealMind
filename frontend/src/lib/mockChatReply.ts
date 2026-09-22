import { filterMealsBySlots } from './mealFilters'
import { SLOT_DIMENSIONS, type MealResponse, type SlotDimension, type SlotOptionsMap } from '../types/meal'

const CLARIFY_PROMPTS = [
  'What meal are we talking about — breakfast, lunch, dinner, or a late-night snack?',
  'Before I suggest anything: is this for breakfast, lunch, dinner, or a snack?',
  'Which meal time should I focus on — breakfast, lunch, dinner, or something later?',
]

function pickRandom<T>(items: T[]): T {
  return items[Math.floor(Math.random() * items.length)]
}

function sampleRandom<T>(items: T[], count: number): T[] {
  const shuffled = [...items].sort(() => Math.random() - 0.5)
  return shuffled.slice(0, count)
}

export function detectSlots(
  message: string,
  slotOptions: SlotOptionsMap,
): Record<SlotDimension, Set<string>> {
  const lower = message.toLowerCase()

  return SLOT_DIMENSIONS.reduce(
    (acc, dimension) => {
      const matches = (slotOptions[dimension] ?? []).filter((tag) => lower.includes(tag.toLowerCase()))
      acc[dimension] = new Set(matches)
      return acc
    },
    {} as Record<SlotDimension, Set<string>>,
  )
}

export interface MockChatReplyResult {
  text: string
  meals: MealResponse[]
  missingSlots: SlotDimension[]
}

export function mockChatReply(
  message: string,
  candidateMeals: MealResponse[],
  slotOptions: SlotOptionsMap,
): MockChatReplyResult {
  if (candidateMeals.length === 0) {
    return {
      text: "Your meal library is empty right now. Add a few meals on the Personal Meals page, or switch this chat to the Public library above.",
      meals: [],
      missingSlots: [],
    }
  }

  const detected = detectSlots(message, slotOptions)

  if (detected.mealTime.size === 0) {
    return {
      text: pickRandom(CLARIFY_PROMPTS),
      meals: [],
      missingSlots: ['mealTime'],
    }
  }

  const matched = filterMealsBySlots(candidateMeals, detected)

  if (matched.length > 0) {
    const detectedTags = SLOT_DIMENSIONS.flatMap((dimension) => [...detected[dimension]])
    return {
      text: `Here's what matched ${detectedTags.join(', ')}:`,
      meals: matched.slice(0, 3),
      missingSlots: [],
    }
  }

  return {
    text: "Nothing matched exactly, but here are a few options from your library:",
    meals: sampleRandom(candidateMeals, Math.min(candidateMeals.length, 2 + Math.round(Math.random()))),
    missingSlots: [],
  }
}
