import type { DashboardSummary, IncidentEvent } from '../types/dashboard'
import type { EventPage } from '../types/events'
import type { LiveEventMessage } from '../types/live'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** Parses a `/topic/events` message body (Feature 12 contract); a malformed or unrecognized body parses to `undefined`. */
export function parseEventMessage(body: string): LiveEventMessage | undefined {
  let data: unknown
  try {
    data = JSON.parse(body)
  } catch {
    return undefined
  }
  if (!isRecord(data)) return undefined
  const { type, event } = data
  if (type !== 'CREATED' && type !== 'UPDATED') return undefined
  if (!isRecord(event)) return undefined
  if (typeof event.eventId !== 'string' || typeof event.updatedAt !== 'string') return undefined
  return { type, event: event as unknown as IncidentEvent }
}

/** Parses a `/topic/summary` message body; a malformed or unrecognized body parses to `undefined`. */
export function parseSummaryMessage(body: string): DashboardSummary | undefined {
  let data: unknown
  try {
    data = JSON.parse(body)
  } catch {
    return undefined
  }
  if (!isRecord(data) || typeof data.totalEvents !== 'number') return undefined
  return data as unknown as DashboardSummary
}

/** True when `incoming` is the same age or newer than `current` - the stale-push guard covering finding F-05. */
export function isFresh(current: IncidentEvent | undefined, incoming: IncidentEvent): boolean {
  if (!current) return true
  return Date.parse(incoming.updatedAt) >= Date.parse(current.updatedAt)
}

/**
 * Patches one cached recent-events list for a pushed message: CREATED prepends (if not already present) and trims
 * to `limit`; UPDATED patches the matching row in place, guarded by `isFresh`. Returns the same array reference
 * when nothing changes, so an unaffected query doesn't re-render.
 */
export function patchRecentEvents(events: IncidentEvent[], message: LiveEventMessage, limit: number): IncidentEvent[] {
  const index = events.findIndex((event) => event.eventId === message.event.eventId)

  if (message.type === 'CREATED') {
    if (index !== -1) return events
    return [message.event, ...events].slice(0, limit)
  }

  if (index === -1) return events
  if (!isFresh(events[index], message.event)) return events
  const next = events.slice()
  next[index] = message.event
  return next
}

/**
 * Patches one cached, filtered/paginated event-list page for a pushed UPDATED message, guarded by `isFresh`. A
 * CREATED message never inserts into a list page - the server owns filtering, search, sort and totals, so lists
 * reconcile through a throttled refetch instead.
 */
export function patchEventPage(page: EventPage, message: LiveEventMessage): EventPage {
  if (message.type !== 'UPDATED') return page
  const index = page.content.findIndex((event) => event.eventId === message.event.eventId)
  if (index === -1) return page
  if (!isFresh(page.content[index], message.event)) return page
  const content = page.content.slice()
  content[index] = message.event
  return { ...page, content }
}

/** Patches a cached event detail for a pushed message, guarded by `isFresh`. Returns `current` unchanged when the
 * message is for a different event, absent from the cache, or stale. */
export function patchEventDetail(
  current: IncidentEvent | undefined,
  message: LiveEventMessage,
): IncidentEvent | undefined {
  if (!current || current.eventId !== message.event.eventId) return current
  if (!isFresh(current, message.event)) return current
  return message.event
}

/**
 * Leading + trailing throttle: the first call in a window runs at once; further calls within the window coalesce
 * into one trailing call right after it ends. Used to rate-limit reconciling refetches triggered by live pushes.
 */
export function throttle<Args extends unknown[]>(fn: (...args: Args) => void, windowMs: number): (...args: Args) => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  let pendingArgs: Args | undefined

  return (...args: Args) => {
    if (timer === undefined) {
      fn(...args)
      timer = setTimeout(() => {
        timer = undefined
        if (pendingArgs) {
          const trailingArgs = pendingArgs
          pendingArgs = undefined
          fn(...trailingArgs)
        }
      }, windowMs)
      return
    }
    pendingArgs = args
  }
}
