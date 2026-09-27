export const SEVERITIES = ['INFO', 'WARNING', 'MAJOR', 'CRITICAL'] as const
export type Severity = (typeof SEVERITIES)[number]

export type EventStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'

export type ServiceHealth = 'HEALTHY' | 'DEGRADED' | 'DOWN'

export type SeverityCounts = Partial<Record<Severity, number>>

export type ServiceSummary = {
  name: string
  status: ServiceHealth
  lastEventTime: string | null
}

export type DashboardSummary = {
  totalEvents: number
  openEvents: number
  acknowledgedEvents: number
  /** CRITICAL events that are not RESOLVED. */
  criticalEvents: number
  severityDistribution: SeverityCounts
  services: ServiceSummary[]
}

/** Events per severity whose timestamp falls in the UTC minute starting at `minute`. */
export type TimelineBucket = {
  minute: string
  counts: SeverityCounts
}

/** The backend's EventResponse; list items and detail share it. */
export type IncidentEvent = {
  eventId: string
  source: string
  service: string
  severity: Severity
  message: string
  status: EventStatus
  timestamp: string
  receivedAt: string
  updatedAt: string
}
