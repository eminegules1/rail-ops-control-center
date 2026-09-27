import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useId } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { useEvent } from '../../api/events'
import type { StatusChangeCallbacks } from '../../api/events'
import { formatDateTime } from '../../lib/format'
import type { IncidentEvent } from '../../types/dashboard'
import { SeverityChip } from '../dashboard/StatusChips'
import { StatusActions } from './StatusActions'

type Props = StatusChangeCallbacks & {
  /** The event to show; the drawer is closed while undefined. */
  eventId: string | undefined
  onClose: () => void
}

export function EventDetailDrawer({ eventId, onClose, ...statusCallbacks }: Props) {
  const titleId = useId()

  return (
    <Drawer
      anchor="right"
      open={eventId !== undefined}
      onClose={onClose}
      slotProps={{ paper: { role: 'dialog', 'aria-modal': true, 'aria-labelledby': titleId } }}
    >
      <Box sx={{ width: { xs: '100vw', sm: 440 }, p: 2 }}>
        <Stack direction="row" sx={{ alignItems: 'flex-start', justifyContent: 'space-between', gap: 1, mb: 2 }}>
          <Typography id={titleId} variant="h2" sx={{ wordBreak: 'break-all' }}>
            {eventId}
          </Typography>
          <IconButton aria-label="Close" onClick={onClose} size="small">
            ×
          </IconButton>
        </Stack>
        {eventId !== undefined && <EventDetail eventId={eventId} {...statusCallbacks} />}
      </Box>
    </Drawer>
  )
}

function EventDetail({ eventId, ...statusCallbacks }: StatusChangeCallbacks & { eventId: string }) {
  const { data, isPending, error, refetch } = useEvent(eventId)

  if (isPending) return <Skeleton variant="rounded" height={320} />
  if (!data) {
    return error instanceof ApiError && error.status === 404 ? (
      <Alert severity="warning" variant="outlined">
        Event not found. No event has the ID {eventId}.
      </Alert>
    ) : (
      <Alert
        severity="error"
        variant="outlined"
        action={
          <Button color="inherit" size="small" onClick={() => refetch()}>
            Retry
          </Button>
        }
      >
        Couldn't load this event.
      </Alert>
    )
  }
  return <EventFields event={data} {...statusCallbacks} />
}

function EventFields({ event, ...statusCallbacks }: StatusChangeCallbacks & { event: IncidentEvent }) {
  return (
    <Stack spacing={2}>
      <StatusActions event={event} {...statusCallbacks} />
      <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: 1, columnGap: 2 }}>
        <Field label="Event ID">{event.eventId}</Field>
        <Field label="Severity">
          <SeverityChip severity={event.severity} />
        </Field>
        <Field label="Status">{event.status}</Field>
        <Field label="Service">{event.service}</Field>
        <Field label="Source">{event.source}</Field>
        <Field label="Time">{formatDateTime(event.timestamp)}</Field>
        <Field label="Received">{formatDateTime(event.receivedAt)}</Field>
        <Field label="Updated">{formatDateTime(event.updatedAt)}</Field>
        <Field label="Message">
          <Box component="span" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {event.message}
          </Box>
        </Field>
      </Box>
    </Stack>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <Typography component="dt" variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, overflowWrap: 'anywhere' }}>
        {children}
      </Typography>
    </>
  )
}
