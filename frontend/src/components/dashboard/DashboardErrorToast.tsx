import { useDashboardSummary, useRecentEvents, useTimeline } from '../../api/dashboard'
import { BackendErrorToast } from '../layout/BackendErrorToast'

/** Open while any dashboard query is failing, closed once all recover. */
export function DashboardErrorToast() {
  const summary = useDashboardSummary()
  const timeline = useTimeline()
  const recent = useRecentEvents()

  return <BackendErrorToast open={summary.isError || timeline.isError || recent.isError} />
}
