import type { EventStatus } from '../types/dashboard'

/** Mirrors the backend's EventStatus.allowedTransitions(). */
const TRANSITIONS: Record<EventStatus, EventStatus[]> = {
  OPEN: ['ACKNOWLEDGED', 'RESOLVED'],
  ACKNOWLEDGED: ['RESOLVED'],
  RESOLVED: ['OPEN'],
}

/** Statuses an incident in `status` may change to. */
export function allowedTransitions(status: EventStatus): EventStatus[] {
  return TRANSITIONS[status]
}
