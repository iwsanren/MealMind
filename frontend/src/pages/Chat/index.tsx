import { useEffect, useState } from 'react'
import { getPersonalMeals, getPublicMeals, getSlotOptions } from '../../api/meals'
import { createSession } from '../../api/session'
import { Button } from '../../components/Button'
import { ApiError } from '../../lib/apiClient'
import { mockChatReply } from '../../lib/mockChatReply'
import type { MealResponse, SlotOptionsMap } from '../../types/meal'
import { ChatMessage, type ChatMessageData } from './ChatMessage'
import { QuickReplies } from './QuickReplies'

type SourceMode = 'PERSONAL' | 'PUBLIC'

function greeting(sourceMode: SourceMode): ChatMessageData {
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    text:
      sourceMode === 'PUBLIC'
        ? "Hi! Tell me what you're in the mood for and I'll suggest something from the public library."
        : "Hi! Tell me what you're in the mood for and I'll suggest something from your personal meals.",
  }
}

export function ChatPage() {
  const [sourceMode, setSourceMode] = useState<SourceMode>('PUBLIC')
  const [candidateMeals, setCandidateMeals] = useState<MealResponse[]>([])
  const [slotOptions, setSlotOptions] = useState<SlotOptionsMap | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessageData[]>(() => [greeting('PUBLIC')])
  const [inputText, setInputText] = useState('')
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)

  useEffect(() => {
    getSlotOptions()
      .then(setSlotOptions)
      .catch(() => setSlotOptions(null))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoadError(null)

    const request = sourceMode === 'PUBLIC' ? getPublicMeals() : getPersonalMeals()
    request
      .then((meals) => {
        if (!cancelled) setCandidateMeals(meals)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setCandidateMeals([])
        setLoadError(err instanceof ApiError ? err.message : 'Failed to load candidate meals.')
      })

    return () => {
      cancelled = true
    }
  }, [sourceMode])

  function switchSource(next: SourceMode) {
    if (next === sourceMode) return
    setSourceMode(next)
    setSessionId(null)
    setMessages([greeting(next)])
    setSendError(null)
  }

  function startNewChat() {
    setSessionId(null)
    setMessages([greeting(sourceMode)])
    setSendError(null)
  }

  async function handleSend() {
    const text = inputText.trim()
    if (!text || sending) return

    setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: 'user', text }])
    setInputText('')
    setSending(true)
    setSendError(null)

    try {
      let activeSessionId = sessionId
      if (!activeSessionId) {
        const created = await createSession(sourceMode)
        activeSessionId = created.sessionId
        setSessionId(created.sessionId)
      }

      const reply = mockChatReply(text, candidateMeals, slotOptions ?? ({} as SlotOptionsMap))
      setMessages((prev) => [
        ...prev,
        { id: crypto.randomUUID(), role: 'assistant', text: reply.text, meals: reply.meals },
      ])
    } catch (err) {
      setSendError(err instanceof ApiError ? err.message : 'Failed to send message.')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-10">
      <header className="flex flex-col gap-1">
        <h1>Chat</h1>
        <p className="text-text-secondary">Ask for a meal recommendation.</p>
      </header>

      <div className="rounded border border-border bg-accent-subtle p-3 text-[13px] text-text-primary">
        Recommendations are simulated for now — matched by keyword against your real meal library. Real AI
        matching arrives with the agent backend.
      </div>

      {loadError && (
        <div className="rounded border border-danger bg-danger-subtle p-3 text-[13px] text-danger">
          {loadError}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex gap-2">
          <Button
            type="button"
            variant={sourceMode === 'PUBLIC' ? 'primary' : 'ghost'}
            onClick={() => switchSource('PUBLIC')}
          >
            Public
          </Button>
          <Button
            type="button"
            variant={sourceMode === 'PERSONAL' ? 'primary' : 'ghost'}
            onClick={() => switchSource('PERSONAL')}
          >
            Personal
          </Button>
        </div>
        <Button type="button" variant="secondary" onClick={startNewChat}>
          New chat
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_260px] lg:items-start">
        <div className="flex flex-col gap-6 rounded border border-border bg-surface p-5">
          <div className="flex flex-col gap-4">
            {messages.map((message) => (
              <ChatMessage key={message.id} message={message} sessionId={sessionId} />
            ))}
          </div>

          {sendError && <div className="text-[13px] text-danger">{sendError}</div>}

          <div className="flex gap-3">
            <input
              type="text"
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  handleSend()
                }
              }}
              disabled={sending}
              placeholder="Tell me what you're in the mood for..."
              className="flex-1 rounded-sm border border-border bg-surface px-3 py-2 text-[14px] text-text-primary focus:border-accent focus:outline-none"
            />
            <Button type="button" variant="primary" disabled={sending} onClick={handleSend}>
              Send
            </Button>
          </div>
        </div>

        <QuickReplies onPick={setInputText} />
      </div>
    </div>
  )
}

export default ChatPage
