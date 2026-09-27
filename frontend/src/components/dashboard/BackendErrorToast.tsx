import Alert from '@mui/material/Alert'
import Snackbar from '@mui/material/Snackbar'
import { useDashboardSummary, useRecentEvents, useTimeline } from '../../api/dashboard'

/**
 * Open while any dashboard query is failing, closed once all recover. Derived
 * from query state, so repeated polling failures never stack toasts.
 */
export function BackendErrorToast() {
  const summary = useDashboardSummary()
  const timeline = useTimeline()
  const recent = useRecentEvents()
  const failing = summary.isError || timeline.isError || recent.isError

  return (
    <Snackbar open={failing} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}>
      <Alert severity="error" variant="filled">
        Can't reach the backend - retrying
      </Alert>
    </Snackbar>
  )
}
