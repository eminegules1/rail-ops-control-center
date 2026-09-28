import type { IncidentEvent } from './dashboard'

/** A message pushed on `/topic/events` (Feature 12 contract). */
export type LiveEventMessage = {
  type: 'CREATED' | 'UPDATED'
  event: IncidentEvent
}

/** State of the real-time push connection, shown by the top-bar chip. */
export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline'
