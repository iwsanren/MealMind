import { useEffect, useState } from 'react'
import { setUserId } from '../../lib/apiClient'
import { useUserId } from '../../lib/useUserId'

export function UserIdField() {
  const userId = useUserId()
  const [draft, setDraft] = useState(userId)

  useEffect(() => {
    setDraft(userId)
  }, [userId])

  return (
    <label className="flex items-center gap-2 text-[13px] text-text-secondary">
      <span>User ID</span>
      <input
        type="number"
        min={1}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => setDraft(setUserId(draft))}
        onKeyDown={(e) => {
          if (e.key === 'Enter') setDraft(setUserId(draft))
        }}
        className="w-16 rounded-sm border border-border bg-surface px-2 py-1 text-[13px] text-text-primary focus:border-accent focus:outline-none"
      />
    </label>
  )
}
