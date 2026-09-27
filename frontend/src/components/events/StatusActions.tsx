import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import { useChangeEventStatus } from '../../api/events'
import type { StatusChangeCallbacks } from '../../api/events'
import { allowedTransitions } from '../../lib/lifecycle'
import type { EventStatus, IncidentEvent } from '../../types/dashboard'

const ACTION_LABELS: Record<EventStatus, string> = {
  ACKNOWLEDGED: 'Acknowledge',
  RESOLVED: 'Resolve',
  OPEN: 'Reopen',
}

type Props = StatusChangeCallbacks & { event: IncidentEvent }

/** One button per status the incident may move to; the page reports the outcome, and a rejection rolls back. */
export function StatusActions({ event, onSuccess, onError }: Props) {
  const change = useChangeEventStatus({ onSuccess, onError })

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
