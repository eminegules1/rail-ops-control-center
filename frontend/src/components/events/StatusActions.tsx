import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Snackbar from '@mui/material/Snackbar'
import Stack from '@mui/material/Stack'
import { useChangeEventStatus } from '../../api/events'
import { allowedTransitions } from '../../lib/lifecycle'
import type { EventStatus, IncidentEvent } from '../../types/dashboard'

const ACTION_LABELS: Record<EventStatus, string> = {
  ACKNOWLEDGED: 'Acknowledge',
  RESOLVED: 'Resolve',
  OPEN: 'Reopen',
}

/** One button per status the incident may move to; a rejected change rolls back and shows why. */
export function StatusActions({ event }: { event: IncidentEvent }) {
  const change = useChangeEventStatus()

  return (
    <>
      <Stack direction="row" spacing={1}>
        {allowedTransitions(event.status).map((status) => (
          <Button
            key={status}
            variant={status === 'OPEN' ? 'outlined' : 'contained'}
            disabled={change.isPending}
            onClick={() => change.mutate({ eventId: event.eventId, status })}
          >
            {ACTION_LABELS[status]}
          </Button>
        ))}
      </Stack>
      <Snackbar
        open={change.isError}
        autoHideDuration={8_000}
        onClose={() => change.reset()}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert severity="error" variant="filled" onClose={() => change.reset()}>
          {change.error?.message || "Couldn't change the status"}
        </Alert>
      </Snackbar>
    </>
  )
}
