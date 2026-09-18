import { apiFetch } from '../lib/apiClient'
import type { MealResponse, SlotOptionsMap } from '../types/meal'

export function getPublicMeals(): Promise<MealResponse[]> {
  return apiFetch<MealResponse[]>('/api/v1/meals/public')
}

export function getPersonalMeals(): Promise<MealResponse[]> {
  return apiFetch<MealResponse[]>('/api/v1/meals/personal')
}

export function getSlotOptions(): Promise<SlotOptionsMap> {
  return apiFetch<SlotOptionsMap>('/api/v1/slot-options')
}
