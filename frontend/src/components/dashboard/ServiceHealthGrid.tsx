import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useDashboardSummary } from '../../api/dashboard'
import { HEALTH_COLORS } from '../../lib/colors'
import { formatDateTime, formatTime } from '../../lib/format'
import { EmptyState, Panel } from './Panel'
import { HealthChip } from './StatusChips'

export function ServiceHealthGrid() {
  const { data, isPending, isError } = useDashboardSummary()
  const services = data?.services ?? []

  return (
    <Panel title="Service health" loading={isPending} failed={isError && !data} skeletonHeight={72}>
      {services.length === 0 ? (
        <EmptyState>No services have reported events yet.</EmptyState>
      ) : (
        <Box
          component="ul"
          sx={{
            listStyle: 'none',
            m: 0,
            p: 0,
            display: 'grid',
            gap: 1,
            gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))',
          }}
        >
          {services.map((service) => (
            <Box
              component="li"
              key={service.name}
              sx={{
                p: 1.25,
                border: 1,
                borderColor: 'divider',
                borderLeft: `4px solid ${HEALTH_COLORS[service.status]}`,
                borderRadius: 1,
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
                <Typography sx={{ fontWeight: 600 }} noWrap title={service.name}>
                  {service.name}
                </Typography>
                <HealthChip health={service.status} />
              </Box>
              <Typography variant="caption" color="text.secondary" title={formatDateTime(service.lastEventTime)}>
                Last event {formatTime(service.lastEventTime)}
              </Typography>
            </Box>
          ))}
        </Box>
      )}
    </Panel>
  )
}
