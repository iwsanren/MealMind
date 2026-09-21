import { EmptyState } from '../../components/EmptyState'
import { formatTraceDateTime } from '../../lib/formatDate'
import type { RequestTraceRow } from '../../types/trace'

interface TraceTableProps {
  rows: RequestTraceRow[]
  selectedTraceId: string | null
  onSelect: (row: RequestTraceRow) => void
}

function statusClasses(status: string): string {
  if (status === 'SUCCESS') return 'bg-accent-subtle text-accent'
  if (status === 'FAILED') return 'bg-danger-subtle text-danger'
  return 'border border-border bg-surface text-text-secondary'
}

function StatusPill({ status }: { status: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-sm px-2 py-0.5 text-[12px] font-medium ${statusClasses(status)}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      {status}
    </span>
  )
}

export function TraceTable({ rows, selectedTraceId, onSelect }: TraceTableProps) {
  if (rows.length === 0) {
    return <EmptyState message="No traces match this search." />
  }

  return (
    <div className="overflow-x-auto rounded border border-border bg-surface">
      <table className="w-full text-left text-[13px]">
        <thead>
          <tr className="border-b border-border text-text-secondary">
            <th className="px-3 py-2 font-medium">Trace ID</th>
            <th className="px-3 py-2 font-medium">Session</th>
            <th className="px-3 py-2 font-medium">Status</th>
            <th className="px-3 py-2 font-medium">Events</th>
            <th className="px-3 py-2 font-medium">Duration</th>
            <th className="px-3 py-2 font-medium">Created</th>
            <th className="px-3 py-2 font-medium">Label</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.traceId}
              onClick={() => onSelect(row)}
              className={[
                'cursor-pointer border-b border-border last:border-b-0 hover:bg-bg',
                selectedTraceId === row.traceId ? 'bg-accent-subtle' : '',
              ].join(' ')}
            >
              <td className="px-3 py-2 font-mono text-[12px]">{row.traceId}</td>
              <td className="px-3 py-2 font-mono text-[12px]">{row.sessionId}</td>
              <td className="px-3 py-2">
                <StatusPill status={row.status} />
              </td>
              <td className="px-3 py-2">{row.eventCount}</td>
              <td className="px-3 py-2">{row.durationMs === null ? '—' : `${row.durationMs} ms`}</td>
              <td className="px-3 py-2">{formatTraceDateTime(row.createdAt)}</td>
              <td className="px-3 py-2">{row.expectedIntent ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
