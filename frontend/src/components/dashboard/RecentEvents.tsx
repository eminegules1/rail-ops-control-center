import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import { keyframes } from '@mui/material/styles'
import { RECENT_EVENTS_LIMIT, useRecentEvents } from '../../api/dashboard'
import { formatDateTime, formatTime } from '../../lib/format'
import { HIGHLIGHT_MS, useRecentlyChanged } from '../../lib/highlights'
import type { IncidentEvent } from '../../types/dashboard'
import { EmptyState, Panel } from './Panel'
import { SeverityChip } from './StatusChips'

const fadeIn = keyframes`
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: none; }
`

const highlightFade = keyframes`
  from { background-color: rgba(33, 150, 243, 0.16); }
  to { background-color: transparent; }
`

export function RecentEvents() {
  const { data, isPending, isError } = useRecentEvents(RECENT_EVENTS_LIMIT)
  const events = data ?? []

  return (
    <Panel title="Recent events" loading={isPending} failed={isError && !data} skeletonHeight={320}>
      {events.length === 0 ? (
        <EmptyState>No events yet.</EmptyState>
      ) : (
        <TableContainer>
          <Table size="small" sx={{ tableLayout: 'fixed', minWidth: 760 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 90 }}>Time</TableCell>
                <TableCell sx={{ width: 120 }}>Severity</TableCell>
                <TableCell sx={{ width: 150 }}>Service</TableCell>
                <TableCell sx={{ width: 80 }}>Source</TableCell>
                <TableCell sx={{ width: 140 }}>Status</TableCell>
                <TableCell>Message</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {events.map((event) => (
                // Keyed by eventId, so only rows that are new to the list mount and fade in.
                <RecentEventRow key={event.eventId} event={event} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Panel>
  )
}

/** A pushed create/update, or the operator's own status change, briefly highlights this row (see `highlights.ts`). */
function RecentEventRow({ event }: { event: IncidentEvent }) {
  const highlighted = useRecentlyChanged(event.eventId)

  return (
    <TableRow
      data-highlight={highlighted ? 'true' : undefined}
      sx={{
        animation: highlighted
          ? `${fadeIn} 400ms ease-out, ${highlightFade} ${HIGHLIGHT_MS}ms ease-out`
          : `${fadeIn} 400ms ease-out`,
        '@media (prefers-reduced-motion: reduce)': {
          animation: 'none',
          bgcolor: highlighted ? 'rgba(33, 150, 243, 0.16)' : undefined,
        },
      }}
    >
      <TableCell title={formatDateTime(event.timestamp)}>{formatTime(event.timestamp)}</TableCell>
      <TableCell>
        <SeverityChip severity={event.severity} />
      </TableCell>
      <TableCell sx={noWrap} title={event.service}>
        {event.service}
      </TableCell>
      <TableCell sx={noWrap} title={event.source}>
        {event.source}
      </TableCell>
      <TableCell>{event.status}</TableCell>
      <TableCell sx={noWrap} title={event.message}>
        {event.message}
      </TableCell>
    </TableRow>
  )
}

const noWrap = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
