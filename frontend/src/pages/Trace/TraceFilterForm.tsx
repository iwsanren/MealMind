import { useState, type FormEvent } from 'react'
import { Button } from '../../components/Button'
import { toDateTimeInputValue } from '../../lib/formatDate'

export interface TraceSearchParams {
  startAt: string
  endAt: string
  sessionId: string
  limit: number
  onlyUnlabeled: boolean
}

interface TraceFilterFormProps {
  onSearch: (params: TraceSearchParams) => void
  searching: boolean
}

const DAY_MS = 24 * 60 * 60 * 1000

export function TraceFilterForm({ onSearch, searching }: TraceFilterFormProps) {
  const [startAt, setStartAt] = useState(() => toDateTimeInputValue(new Date(Date.now() - DAY_MS)))
  const [endAt, setEndAt] = useState(() => toDateTimeInputValue(new Date()))
  const [sessionId, setSessionId] = useState('')
  const [limit, setLimit] = useState(50)
  const [onlyUnlabeled, setOnlyUnlabeled] = useState(false)

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    onSearch({ startAt, endAt, sessionId: sessionId.trim(), limit, onlyUnlabeled })
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-col gap-4 rounded border border-border bg-surface p-5"
    >
      <div className="flex flex-col gap-1">
        <label className="text-[13px] font-medium text-text-secondary">Start</label>
        <input
          type="datetime-local"
          value={startAt}
          onChange={(e) => setStartAt(e.target.value)}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-[13px] font-medium text-text-secondary">End</label>
        <input
          type="datetime-local"
          value={endAt}
          onChange={(e) => setEndAt(e.target.value)}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-[13px] font-medium text-text-secondary">Session ID (optional)</label>
        <input
          type="text"
          value={sessionId}
          onChange={(e) => setSessionId(e.target.value)}
          placeholder="sess_..."
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-[13px] font-medium text-text-secondary">Limit</label>
        <input
          type="number"
          min={1}
          value={limit}
          onChange={(e) => setLimit(Number(e.target.value) || 1)}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-[13px] font-medium text-text-secondary">Label status</label>
        <select
          value={onlyUnlabeled ? 'unlabeled' : 'all'}
          onChange={(e) => setOnlyUnlabeled(e.target.value === 'unlabeled')}
          className="rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
        >
          <option value="all">All</option>
          <option value="unlabeled">Unlabeled only</option>
        </select>
        {sessionId.trim() && (
          <span className="text-[12px] text-text-secondary">
            Ignored when searching by Session ID (that endpoint doesn&apos;t support this filter).
          </span>
        )}
      </div>

      <Button type="submit" variant="primary" disabled={searching}>
        Search
      </Button>
    </form>
  )
}
