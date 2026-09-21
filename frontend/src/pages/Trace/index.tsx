import { useEffect, useState } from 'react'
import { getSlotOptions } from '../../api/meals'
import { getTracesBySession, getTracesByTimeRange } from '../../api/trace'
import { EmptyState } from '../../components/EmptyState'
import { ApiError } from '../../lib/apiClient'
import { formatTraceDateTime } from '../../lib/formatDate'
import type { SlotOptionsMap } from '../../types/meal'
import type { RequestTraceRow } from '../../types/trace'
import { TraceFilterForm, type TraceSearchParams } from './TraceFilterForm'
import { TraceLabelForm } from './TraceLabelForm'
import { TraceTable } from './TraceTable'

function ensureSeconds(value: string): string {
  return value.length === 16 ? `${value}:00` : value
}

export function TracePage() {
  const [slotOptions, setSlotOptions] = useState<SlotOptionsMap | null>(null)
  const [results, setResults] = useState<RequestTraceRow[]>([])
  const [selectedTrace, setSelectedTrace] = useState<RequestTraceRow | null>(null)
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [hasSearched, setHasSearched] = useState(false)

  useEffect(() => {
    getSlotOptions()
      .then(setSlotOptions)
      .catch(() => setSlotOptions(null))
  }, [])

  async function handleSearch(params: TraceSearchParams) {
    setSearching(true)
    setSearchError(null)
    try {
      const rows = params.sessionId
        ? await getTracesBySession(params.sessionId, params.limit)
        : await getTracesByTimeRange({
            startAt: ensureSeconds(params.startAt),
            endAt: ensureSeconds(params.endAt),
            onlyUnlabeled: params.onlyUnlabeled,
            limit: params.limit,
          })
      setResults(rows)
      setHasSearched(true)
      setSelectedTrace(null)
    } catch (err) {
      setSearchError(err instanceof ApiError ? err.message : 'Failed to search traces.')
    } finally {
      setSearching(false)
    }
  }

  function handleSaved(updated: RequestTraceRow) {
    setSelectedTrace(updated)
    setResults((rows) => rows.map((row) => (row.traceId === updated.traceId ? updated : row)))
  }

  const prettyTraceJson = (() => {
    if (!selectedTrace) return ''
    try {
      return JSON.stringify(JSON.parse(selectedTrace.traceJson), null, 2)
    } catch {
      return selectedTrace.traceJson
    }
  })()

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-8 px-6 py-10">
      <header className="flex flex-col gap-1">
        <h1>Trace</h1>
        <p className="text-text-secondary">Inspect request traces and label expected answers.</p>
      </header>

      {searchError && (
        <div className="rounded border border-danger bg-danger-subtle p-4 text-danger">{searchError}</div>
      )}

      <div className="grid grid-cols-1 gap-8 lg:grid-cols-[320px_1fr] lg:items-start">
        <TraceFilterForm onSearch={handleSearch} searching={searching} />

        <div className="flex flex-col gap-8">
          {hasSearched ? (
            <TraceTable
              rows={results}
              selectedTraceId={selectedTrace?.traceId ?? null}
              onSelect={setSelectedTrace}
            />
          ) : (
            <EmptyState message="Run a search on the left to see traces." />
          )}

          {selectedTrace ? (
            <div className="flex flex-col gap-8 lg:grid lg:grid-cols-2 lg:items-start lg:gap-8">
              <div className="flex flex-col gap-4">
                <div className="rounded border border-border bg-surface p-4 text-[13px] text-text-secondary">
                  <div>
                    Session: <span className="font-mono text-text-primary">{selectedTrace.sessionId}</span>
                  </div>
                  <div>
                    Events: <span className="text-text-primary">{selectedTrace.eventCount}</span>
                  </div>
                  <div>
                    Duration:{' '}
                    <span className="text-text-primary">
                      {selectedTrace.durationMs === null ? '—' : `${selectedTrace.durationMs} ms`}
                    </span>
                  </div>
                  <div>
                    Created:{' '}
                    <span className="text-text-primary">{formatTraceDateTime(selectedTrace.createdAt)}</span>
                  </div>
                  {selectedTrace.errorMessage && (
                    <div className="text-danger">Error: {selectedTrace.errorMessage}</div>
                  )}
                </div>
                <pre className="max-h-[500px] overflow-auto rounded border border-border bg-surface p-4 font-mono text-[12px] text-text-primary">
                  {prettyTraceJson}
                </pre>
              </div>

              {slotOptions ? (
                <TraceLabelForm
                  key={selectedTrace.traceId}
                  trace={selectedTrace}
                  slotOptions={slotOptions}
                  onSaved={handleSaved}
                />
              ) : (
                <p className="text-text-secondary">Loading slot options...</p>
              )}
            </div>
          ) : (
            hasSearched && <EmptyState message="Select a trace from the table to see its detail." />
          )}
        </div>
      </div>
    </div>
  )
}

export default TracePage
