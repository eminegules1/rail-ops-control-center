import Link from '@mui/material/Link'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import { Link as RouterLink } from 'react-router'
import { toEventSearchParams } from '../../lib/eventSearch'
import { formatCount, formatDateTime, formatTime } from '../../lib/format'
import type { ServiceState } from '../../types/services'
import { EmptyState, Panel } from '../dashboard/Panel'
import { HealthChip, SeverityChip } from '../dashboard/StatusChips'

type Props = {
  services: ServiceState[] | undefined
  loading: boolean
  failed: boolean
}

export function ServiceStatusTable({ services, loading, failed }: Props) {
  const rows = services ?? []

  return (
    <Panel title="Service status" loading={loading} failed={failed} skeletonHeight={320}>
      {rows.length === 0 ? (
        <EmptyState>No services have reported events yet.</EmptyState>
      ) : (
        <TableContainer>
          <Table size="small" sx={{ tableLayout: 'fixed', minWidth: 720 }}>
            <TableHead>
              <TableRow>
                <TableCell>Service</TableCell>
                <TableCell sx={{ width: 120 }}>Health</TableCell>
                <TableCell align="right" sx={{ width: 90 }}>
                  Open
                </TableCell>
                <TableCell align="right" sx={{ width: 90 }}>
                  Active
                </TableCell>
                <TableCell sx={{ width: 150 }}>Latest severity</TableCell>
                <TableCell sx={{ width: 110 }}>Last event</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((service) => (
                <TableRow key={service.name}>
                  <TableCell sx={noWrap} title={service.name}>
                    <Link component={RouterLink} to={eventsFor(service.name)}>
                      {service.name}
                    </Link>
                  </TableCell>
                  <TableCell>{service.status ? <HealthChip health={service.status} /> : '—'}</TableCell>
                  {/* The main column: the incidents nobody has picked up yet. */}
                  <TableCell align="right" sx={{ fontWeight: 700, fontSize: '1.1rem' }}>
                    {formatCount(service.openCount)}
                  </TableCell>
                  <TableCell align="right">{formatCount(service.activeCount)}</TableCell>
                  <TableCell>
                    {service.latestSeverity ? <SeverityChip severity={service.latestSeverity} /> : '—'}
                  </TableCell>
                  <TableCell title={formatDateTime(service.lastEventTime)}>{formatTime(service.lastEventTime)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Panel>
  )
}

/** The Events page filtered to one service. */
function eventsFor(service: string): string {
  return `/events?${toEventSearchParams({ service, q: '', page: 1 })}`
}

const noWrap = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
