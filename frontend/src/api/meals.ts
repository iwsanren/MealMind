import { apiFetch } from '../lib/apiClient'
import type { MealRequest, MealResponse, SlotOptionsMap } from '../types/meal'

export function getPublicMeals(): Promise<MealResponse[]> {
  return apiFetch<MealResponse[]>('/api/v1/meals/public')
}

export function getPersonalMeals(): Promise<MealResponse[]> {
  return apiFetch<MealResponse[]>('/api/v1/meals/personal')
}

export function createPersonalMeal(payload: MealRequest): Promise<MealResponse> {
  return apiFetch<MealResponse>('/api/v1/meals/personal', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updatePersonalMeal(mealId: number, payload: MealRequest): Promise<MealResponse> {
  return apiFetch<MealResponse>(`/api/v1/meals/personal/${mealId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deletePersonalMeal(mealId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/meals/personal/${mealId}`, {
    method: 'DELETE',
  })
}

export function getSlotOptions(): Promise<SlotOptionsMap> {
  return apiFetch<SlotOptionsMap>('/api/v1/slot-options')
}
