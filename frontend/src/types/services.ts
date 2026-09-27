import type { ServiceHealth, Severity } from './dashboard'

/** The backend's ServiceState; a field missing from the service's Redis hash comes back as null. */
export type ServiceState = {
  name: string
  status: ServiceHealth | null
  lastEventTime: string | null
  latestSeverity: Severity | null
  /** Incidents with status OPEN. */
  openCount: number
  /** Incidents with status OPEN or ACKNOWLEDGED. */
  activeCount: number
}
