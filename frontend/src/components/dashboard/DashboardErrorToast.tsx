import { useDashboardSummary, useRecentEvents, useTimeline } from '../../api/dashboard'
import { BackendErrorToast } from '../layout/BackendErrorToast'

/** Open from the first failed attempt of any dashboard query, closed once all recover. */
export function DashboardErrorToast() {
  const queries = [useDashboardSummary(), useTimeline(), useRecentEvents()]

  return <BackendErrorToast open={queries.some((query) => query.isError || query.failureCount > 0)} />
}
