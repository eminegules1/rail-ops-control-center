import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useChangeEventStatus } from '../../api/events'
import type { StatusChangeCallbacks } from '../../api/events'
import { allowedTransitions } from '../../lib/lifecycle'
import { useSession } from '../../lib/session'
import type { EventStatus, IncidentEvent } from '../../types/dashboard'

const ACTION_LABELS: Record<EventStatus, string> = {
  ACKNOWLEDGED: 'Acknowledge',
  RESOLVED: 'Resolve',
  OPEN: 'Reopen',
}

type Props = StatusChangeCallbacks & { event: IncidentEvent }

/**
 * One button per status the incident may move to; the page reports the outcome, and a rejection rolls back.
 * Only an ADMIN gets the buttons; a VIEWER gets a read-only note.
 */
export function StatusActions({ event, onSuccess, onError }: Props) {
  const change = useChangeEventStatus({ onSuccess, onError })
  const session = useSession()

  if (session?.role !== 'ADMIN') {
    return (
      <Typography variant="body2" color="text.secondary">
        Read-only access: only an ADMIN can change an incident's status.
      </Typography>
    )
  }

  return (
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
  )
}
