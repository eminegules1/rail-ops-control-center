import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IncidentEvent } from '../types/dashboard'
import type { EventPage } from '../types/events'
import type { LiveEventMessage } from '../types/live'
import {
  isFresh,
  parseEventMessage,
  parseSummaryMessage,
  patchEventDetail,
  patchEventPage,
  patchRecentEvents,
  throttle,
} from './liveCache'

const baseEvent: IncidentEvent = {
  eventId: 'EVT-1',
  source: 'CBTC',
  service: 'signal-service',
  severity: 'CRITICAL',
  message: 'Signal failure at junction 4',
  status: 'OPEN',
  timestamp: '2026-09-27T12:31:00Z',
  receivedAt: '2026-09-27T12:31:00.100Z',
  updatedAt: '2026-09-27T12:31:00.100Z',
}

function updatedMessage(event: Partial<IncidentEvent> = {}): LiveEventMessage {
  return { type: 'UPDATED', event: { ...baseEvent, ...event } }
}

function createdMessage(event: Partial<IncidentEvent> = {}): LiveEventMessage {
  return { type: 'CREATED', event: { ...baseEvent, ...event } }
}

describe('parseEventMessage', () => {
  it('parses a valid CREATED or UPDATED message', () => {
    expect(parseEventMessage(JSON.stringify({ type: 'CREATED', event: baseEvent }))).toEqual(createdMessage())
    expect(parseEventMessage(JSON.stringify({ type: 'UPDATED', event: baseEvent }))).toEqual(updatedMessage())
  })

  it('rejects malformed JSON', () => {
    expect(parseEventMessage('{not json')).toBeUndefined()
  })

  it('rejects an unrecognized type', () => {
    expect(parseEventMessage(JSON.stringify({ type: 'DELETED', event: baseEvent }))).toBeUndefined()
  })

  it('rejects an event missing eventId or updatedAt', () => {
    const withoutId: Record<string, unknown> = { ...baseEvent }
    delete withoutId.eventId
    expect(parseEventMessage(JSON.stringify({ type: 'UPDATED', event: withoutId }))).toBeUndefined()

    const withoutUpdatedAt: Record<string, unknown> = { ...baseEvent }
    delete withoutUpdatedAt.updatedAt
    expect(parseEventMessage(JSON.stringify({ type: 'UPDATED', event: withoutUpdatedAt }))).toBeUndefined()
  })

  it('rejects a body that is not an object, or with no event', () => {
    expect(parseEventMessage(JSON.stringify('CREATED'))).toBeUndefined()
    expect(parseEventMessage(JSON.stringify({ type: 'CREATED' }))).toBeUndefined()
  })
})

describe('parseSummaryMessage', () => {
  it('parses a valid summary', () => {
    const summary = { totalEvents: 3, openEvents: 1, acknowledgedEvents: 1, criticalEvents: 1, severityDistribution: {}, services: [] }
    expect(parseSummaryMessage(JSON.stringify(summary))).toEqual(summary)
  })

  it('rejects malformed JSON and a body without totalEvents', () => {
    expect(parseSummaryMessage('{not json')).toBeUndefined()
    expect(parseSummaryMessage(JSON.stringify({ openEvents: 1 }))).toBeUndefined()
  })
})

describe('isFresh', () => {
  it('is fresh when there is nothing cached yet', () => {
    expect(isFresh(undefined, baseEvent)).toBe(true)
  })

  it('applies an equal or newer updatedAt, and ignores an older one', () => {
    const cached = { ...baseEvent, updatedAt: '2026-09-27T12:32:00Z' }
    expect(isFresh(cached, { ...baseEvent, updatedAt: '2026-09-27T12:32:00Z' })).toBe(true)
    expect(isFresh(cached, { ...baseEvent, updatedAt: '2026-09-27T12:33:00Z' })).toBe(true)
    expect(isFresh(cached, { ...baseEvent, updatedAt: '2026-09-27T12:31:00Z' })).toBe(false)
  })
})

describe('patchRecentEvents', () => {
  it('prepends a CREATED event and trims to the limit', () => {
    const existing = [{ ...baseEvent, eventId: 'EVT-OLD' }]
    const result = patchRecentEvents(existing, createdMessage({ eventId: 'EVT-NEW' }), 1)
    expect(result.map((e) => e.eventId)).toEqual(['EVT-NEW'])
  })

  it('does not duplicate a CREATED event already present', () => {
    const existing = [baseEvent]
    const result = patchRecentEvents(existing, createdMessage(), 20)
    expect(result).toBe(existing)
  })

  it('patches a matching UPDATED event in place when fresh, and ignores a stale one', () => {
    const existing = [{ ...baseEvent, updatedAt: '2026-09-27T12:32:00Z' }]
    const fresh = patchRecentEvents(existing, updatedMessage({ status: 'ACKNOWLEDGED', updatedAt: '2026-09-27T12:33:00Z' }), 20)
    expect(fresh[0].status).toBe('ACKNOWLEDGED')

    const stale = patchRecentEvents(existing, updatedMessage({ status: 'RESOLVED', updatedAt: '2026-09-27T12:30:00Z' }), 20)
    expect(stale).toBe(existing)
  })

  it('ignores an UPDATED event that is not in the list', () => {
    const existing = [{ ...baseEvent, eventId: 'EVT-OTHER' }]
    expect(patchRecentEvents(existing, updatedMessage(), 20)).toBe(existing)
  })
})

describe('patchEventPage', () => {
  const page: EventPage = { content: [baseEvent], page: 0, size: 20, totalElements: 1, totalPages: 1 }

  it('patches a matching row for UPDATED', () => {
    const result = patchEventPage(page, updatedMessage({ status: 'ACKNOWLEDGED', updatedAt: '2026-09-27T12:32:00Z' }))
    expect(result.content[0].status).toBe('ACKNOWLEDGED')
  })

  it('never inserts a CREATED event into a list page', () => {
    expect(patchEventPage(page, createdMessage({ eventId: 'EVT-NEW' }))).toBe(page)
  })

  it('ignores a stale UPDATED and an event not on the page', () => {
    expect(patchEventPage(page, updatedMessage({ updatedAt: '2026-09-26T00:00:00Z' }))).toBe(page)
    expect(patchEventPage(page, updatedMessage({ eventId: 'EVT-OTHER' }))).toBe(page)
  })
})

describe('patchEventDetail', () => {
  it('applies a fresh message for the cached event', () => {
    const result = patchEventDetail(baseEvent, updatedMessage({ status: 'ACKNOWLEDGED', updatedAt: '2026-09-27T12:32:00Z' }))
    expect(result?.status).toBe('ACKNOWLEDGED')
  })

  it('leaves an uncached detail, a different event, and a stale message unchanged', () => {
    expect(patchEventDetail(undefined, updatedMessage())).toBeUndefined()
    expect(patchEventDetail(baseEvent, updatedMessage({ eventId: 'EVT-OTHER' }))).toBe(baseEvent)
    expect(patchEventDetail(baseEvent, updatedMessage({ updatedAt: '2026-09-26T00:00:00Z' }))).toBe(baseEvent)
  })
})

describe('throttle', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('calls immediately (leading) and coalesces further calls into one trailing call', () => {
    const fn = vi.fn()
    const throttled = throttle(fn, 1_000)

    throttled('a')
    expect(fn).toHaveBeenCalledTimes(1)
    expect(fn).toHaveBeenLastCalledWith('a')

    throttled('b')
    throttled('c')
    expect(fn).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(999)
    expect(fn).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(1)
    expect(fn).toHaveBeenCalledTimes(2)
    expect(fn).toHaveBeenLastCalledWith('c')
  })

  it('calls immediately again once the window has fully elapsed with no pending call', () => {
    const fn = vi.fn()
    const throttled = throttle(fn, 1_000)

    throttled()
    vi.advanceTimersByTime(1_000)
    expect(fn).toHaveBeenCalledTimes(1)

    throttled()
    expect(fn).toHaveBeenCalledTimes(2)
  })
})
