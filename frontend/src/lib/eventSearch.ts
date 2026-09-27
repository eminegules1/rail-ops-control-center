import { SEVERITIES } from '../types/dashboard'
import type { EventStatus, Severity } from '../types/dashboard'
import { EVENT_STATUSES } from '../types/events'

export const EVENTS_PAGE_SIZE = 20
/** The API rejects longer searches. */
export const MAX_QUERY_LENGTH = 200

/** The Events page's filters, search and 1-based page, as kept in the query string. */
export type EventSearch = {
  severity?: Severity
  status?: EventStatus
  source?: string
  service?: string
  q: string
  page: number
}

export const EMPTY_SEARCH: EventSearch = { q: '', page: 1 }

function oneOf<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((candidate) => candidate === value)
}

function text(value: string | null): string | undefined {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}

/** Reads the page state from the URL, dropping values the API would reject. */
export function parseEventSearch(params: URLSearchParams): EventSearch {
  const page = params.get('page')
  return {
    severity: oneOf(SEVERITIES, params.get('severity')),
    status: oneOf(EVENT_STATUSES, params.get('status')),
    source: text(params.get('source')),
    service: text(params.get('service')),
    q: (params.get('q') ?? '').trim().slice(0, MAX_QUERY_LENGTH),
    page: page && /^[1-9]\d{0,8}$/.test(page) ? Number(page) : 1,
  }
}

/** The URL form of the page state; empty values and page 1 are left out. */
export function toEventSearchParams(search: EventSearch): URLSearchParams {
  const params = new URLSearchParams()
  if (search.severity) params.set('severity', search.severity)
  if (search.status) params.set('status', search.status)
  if (search.source) params.set('source', search.source)
  if (search.service) params.set('service', search.service)
  if (search.q) params.set('q', search.q)
  if (search.page > 1) params.set('page', String(search.page))
  return params
}

/** The `GET /api/events` query string; the API's page is zero-based. */
export function toApiQuery(search: EventSearch): string {
  const params = toEventSearchParams({ ...search, page: 1 })
  params.set('page', String(search.page - 1))
  params.set('size', String(EVENTS_PAGE_SIZE))
  return params.toString()
}

export function hasFilters(search: EventSearch): boolean {
  return Boolean(search.severity || search.status || search.source || search.service || search.q)
}
