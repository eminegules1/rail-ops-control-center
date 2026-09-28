import { useEffect, useState } from 'react'

/** How long a row stays highlighted after it was created or updated. */
export const HIGHLIGHT_MS = 3_000

const changedAt = new Map<string, number>()
const listeners = new Set<() => void>()

/** Marks an event as just changed; a later call for the same event restarts its highlight window. */
export function markChanged(eventId: string): void {
  changedAt.set(eventId, Date.now())
  listeners.forEach((listener) => listener())
}

/** Test-only: clears every mark, so marks made under one test's fake clock can't leak into a later test's. */
export function _clearHighlightsForTests(): void {
  changedAt.clear()
}

/** True for `HIGHLIGHT_MS` after the last `markChanged(eventId)` call; false otherwise or once it expires. */
export function useRecentlyChanged(eventId: string): boolean {
  const [highlighted, setHighlighted] = useState(false)

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined

    // Reads the current mark and schedules its own re-check for when the window ends; re-run on every mark,
    // not just this event's, since a listener is one shared subscription per hook instance.
    const sync = () => {
      clearTimeout(timer)
      const markedAt = changedAt.get(eventId)
      const remaining = markedAt === undefined ? 0 : markedAt + HIGHLIGHT_MS - Date.now()
      setHighlighted(remaining > 0)
      if (remaining > 0) timer = setTimeout(sync, remaining)
    }

    sync()
    listeners.add(sync)
    return () => {
      listeners.delete(sync)
      clearTimeout(timer)
    }
  }, [eventId])

  return highlighted
}
