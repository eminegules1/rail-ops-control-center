import Alert from '@mui/material/Alert'
import Snackbar from '@mui/material/Snackbar'

/**
 * The one "backend unreachable" message every page shows. Pages derive `open` from their query state, so
 * repeated polling failures never stack toasts and the message closes once the queries recover.
 */
export function BackendErrorToast({ open }: { open: boolean }) {
  return (
    <Snackbar open={open} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}>
      <Alert severity="error" variant="filled">
        Can't reach the backend - retrying
      </Alert>
    </Snackbar>
  )
}
