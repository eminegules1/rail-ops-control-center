import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TablePagination from '@mui/material/TablePagination'
import TableRow from '@mui/material/TableRow'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router'
import { EVENTS_PAGE_SIZE } from '../../lib/eventSearch'
import { formatDateTime, formatTime } from '../../lib/format'
import type { EventPage } from '../../types/events'
import { EmptyState, Panel } from '../dashboard/Panel'
import { SeverityChip } from '../dashboard/StatusChips'

type Props = {
  data: EventPage | undefined
  loading: boolean
  /** Set when the list failed and there is no page to show. */
  error: string | undefined
  /** 1-based. */
  page: number
  filtered: boolean
  onPageChange: (page: number) => void
  onClearFilters: () => void
}

export function EventTable({ data, loading, error, page, filtered, onPageChange, onClearFilters }: Props) {
  const navigate = useNavigate()
  const { search } = useLocation()
  const detailPath = (eventId: string) => `/events/${encodeURIComponent(eventId)}${search}`
  const events = data?.content ?? []

  return (
    <Panel title="Events" loading={loading} failed={error !== undefined} errorText={error} skeletonHeight={480}>
      {events.length === 0 ? (
        data && data.totalElements > 0 ? (
          <EmptyState>
            No events on this page. <Button onClick={() => onPageChange(1)}>Go to first page</Button>
          </EmptyState>
        ) : filtered ? (
          <EmptyState>
            No events match these filters. <Button onClick={onClearFilters}>Clear filters</Button>
          </EmptyState>
        ) : (
          <EmptyState>No events yet.</EmptyState>
        )
      ) : (
        <TableContainer>
          <Table size="small" sx={{ tableLayout: 'fixed', minWidth: 980 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 90 }}>Time</TableCell>
                <TableCell sx={{ width: 120 }}>Severity</TableCell>
                <TableCell sx={{ width: 150 }}>Service</TableCell>
                <TableCell sx={{ width: 80 }}>Source</TableCell>
                <TableCell sx={{ width: 140 }}>Status</TableCell>
                <TableCell>Message</TableCell>
                <TableCell sx={{ width: 200 }}>Event ID</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {events.map((event) => (
                <TableRow
                  key={event.eventId}
                  hover
                  onClick={() => navigate(detailPath(event.eventId))}
                  sx={{ cursor: 'pointer' }}
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
                  <TableCell sx={noWrap} title={event.eventId}>
                    {/* The keyboard route to the detail; the row click is a mouse shortcut. */}
                    <Link component={RouterLink} to={detailPath(event.eventId)} onClick={(e) => e.stopPropagation()}>
                      {event.eventId}
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {data && data.totalElements > 0 && (
        <TablePagination
          component="div"
          count={data.totalElements}
          page={page - 1}
          rowsPerPage={EVENTS_PAGE_SIZE}
          rowsPerPageOptions={[]}
          onPageChange={(_, zeroBased) => onPageChange(zeroBased + 1)}
        />
      )}
    </Panel>
  )
}

const noWrap = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
