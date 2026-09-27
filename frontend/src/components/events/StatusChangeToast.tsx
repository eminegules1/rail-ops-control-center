import Alert from '@mui/material/Alert'
import Portal from '@mui/material/Portal'
import Snackbar from '@mui/material/Snackbar'

export type StatusFeedback = {
  /** Distinguishes consecutive messages, so a new one restarts the auto-hide timer. */
  id: number
  severity: 'success' | 'error'
  message: string
}

type Props = {
  /** The latest message; kept while closing so it doesn't change during the exit animation. */
  feedback: StatusFeedback | null
  open: boolean
  onClose: () => void
}

/** The outcome of the latest status change. Lives on the page, so it outlasts the detail drawer. */
export function StatusChangeToast({ feedback, open, onClose }: Props) {
  const success = feedback?.severity === 'success'

  return (
    // Rendered straight into <body>: the page behind the open drawer is aria-hidden, and a toast inside it
    // would never reach a screen reader. Snackbar adds its element only on opening, after the drawer hid the rest.
    <Portal>
      <Snackbar
        key={feedback?.id}
        open={open && feedback !== null}
        autoHideDuration={success ? 4_000 : 8_000}
        onClose={(_, reason) => reason !== 'clickaway' && onClose()}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          severity={feedback?.severity ?? 'success'}
          variant="filled"
          // A success is announced politely; a failure interrupts.
          role={success ? 'status' : 'alert'}
          onClose={onClose}
        >
          {feedback?.message}
        </Alert>
      </Snackbar>
    </Portal>
  )
}
