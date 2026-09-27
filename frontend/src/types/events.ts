import type { EventStatus, IncidentEvent } from './dashboard'

export const EVENT_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'] as const satisfies readonly EventStatus[]

/** Event sources the producer publishes. */
export const SOURCES = ['ATS', 'CBTC', 'SCADA', 'TMS', 'PIS'] as const

/** The backend's EventPageResponse; `page` is zero-based. */
export type EventPage = {
  content: IncidentEvent[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
