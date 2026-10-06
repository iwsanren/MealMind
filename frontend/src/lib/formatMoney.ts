// The one place that decides the currency and how it is written (symbol, decimal separator, position).
// Meal prices are plain numbers in the database; only the display is currency-specific.
// Not used for LLM API spend: that is billed in USD and keeps its own "$" formatting.
const MEAL_CURRENCY = 'EUR'
const MEAL_LOCALE = 'en-IE'

const formatter = new Intl.NumberFormat(MEAL_LOCALE, { style: 'currency', currency: MEAL_CURRENCY })

/** "€12.50"; null/undefined means the price is unknown. */
export function formatMoney(amount: number | null | undefined, unknown = 'Price unknown'): string {
  return amount == null ? unknown : formatter.format(amount)
}

/** The currency code for form labels, e.g. "Price (EUR)". */
export const MEAL_CURRENCY_CODE = MEAL_CURRENCY
