import { useState, type FormEvent } from 'react'
import { getTraceById, labelTrace } from '../../api/trace'
import { Button } from '../../components/Button'
import { DimensionChipGroup } from '../../components/DimensionChipGroup'
import { ApiError } from '../../lib/apiClient'
import { SLOT_DIMENSIONS, type SlotDimension, type SlotOptionsMap } from '../../types/meal'
import type { RequestTraceRow } from '../../types/trace'

type Selection = Record<SlotDimension, Set<string>>

function parseExpectedSlots(raw: string | null): Selection {
  let parsed: Partial<Record<SlotDimension, string[]>> = {}
  if (raw) {
    try {
      parsed = JSON.parse(raw) as Partial<Record<SlotDimension, string[]>>
    } catch {
      parsed = {}
    }
  }
  return SLOT_DIMENSIONS.reduce((acc, dimension) => {
    acc[dimension] = new Set(parsed[dimension] ?? [])
    return acc
  }, {} as Selection)
}

interface TraceLabelFormProps {
  trace: RequestTraceRow
  slotOptions: SlotOptionsMap
  onSaved: (updated: RequestTraceRow) => void
}

export function TraceLabelForm({ trace, slotOptions, onSaved }: TraceLabelFormProps) {
  const [expectedIntent, setExpectedIntent] = useState(trace.expectedIntent ?? '')
  const [expectedClarifyAction, setExpectedClarifyAction] = useState(trace.expectedClarifyAction ?? '')
  const [labelNote, setLabelNote] = useState(trace.labelNote ?? '')
  const [selection, setSelection] = useState<Selection>(() => parseExpectedSlots(trace.expectedSlots))
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function toggle(dimension: SlotDimension, value: string) {
    setSelection((prev) => {
      const next = { ...prev, [dimension]: new Set(prev[dimension]) }
      if (next[dimension].has(value)) {
        next[dimension].delete(value)
      } else {
        next[dimension].add(value)
      }
      return next
    })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)

    try {
      await labelTrace(trace.traceId, {
        expectedIntent: expectedIntent.trim() || null,
        expectedClarifyAction: expectedClarifyAction.trim() || null,
        labelNote: labelNote.trim() || null,
        expectedSlots: {
          mealTime: [...selection.mealTime],
          mood: [...selection.mood],
          scene: [...selection.scene],
          healthGoal: [...selection.healthGoal],
          cuisine: [...selection.cuisine],
          taste: [...selection.taste],
          convenience: [...selection.convenience],
        },
      })
      const updated = await getTraceById(trace.traceId)
      onSaved(updated)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save label.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-5 rounded border border-border bg-surface p-5">
      <h2 className="text-[16px] font-semibold">Expected answer</h2>

      <label className="flex flex-col gap-1">
        <span className="text-[13px] font-medium text-text-secondary">Expected intent</span>
        <input
          type="text"
          value={expectedIntent}
          onChange={(e) => setExpectedIntent(e.target.value)}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-[13px] font-medium text-text-secondary">Expected clarify action</span>
        <input
          type="text"
          value={expectedClarifyAction}
          onChange={(e) => setExpectedClarifyAction(e.target.value)}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </label>

      {SLOT_DIMENSIONS.map((dimension) => (
        <DimensionChipGroup
          key={dimension}
          dimension={dimension}
          options={slotOptions[dimension] ?? []}
          selected={selection[dimension]}
          onToggle={(value) => toggle(dimension, value)}
        />
      ))}

      <label className="flex flex-col gap-1">
        <span className="text-[13px] font-medium text-text-secondary">Label note</span>
        <textarea
          value={labelNote}
          onChange={(e) => setLabelNote(e.target.value)}
          rows={3}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </label>

      {error && <span className="text-[13px] text-danger">{error}</span>}

      <Button type="submit" variant="primary" disabled={submitting}>
        Save
      </Button>
    </form>
  )
}
