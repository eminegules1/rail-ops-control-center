import Box from '@mui/material/Box'
import { Bar, BarChart, CartesianGrid, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useDashboardSummary } from '../../api/dashboard'
import { toSeverityRows } from '../../lib/chartData'
import { SEVERITY_COLORS } from '../../lib/colors'
import { formatCount } from '../../lib/format'
import { EmptyState, Panel } from './Panel'

const AXIS_TICK = { fill: 'var(--mui-palette-text-secondary)', fontSize: 12 }

export function SeverityChart() {
  const { data, isPending, isError } = useDashboardSummary()
  const rows = toSeverityRows(data?.severityDistribution ?? {})
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  const description = `Events by severity: ${rows.map((row) => `${row.severity} ${row.count}`).join(', ')}`

  return (
    <Panel title="Severity distribution" loading={isPending} failed={isError && !data} skeletonHeight={240}>
      {total === 0 ? (
        <EmptyState>No events yet.</EmptyState>
      ) : (
        <Box role="img" aria-label={description} sx={{ height: 240 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} margin={{ top: 20, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="var(--mui-palette-divider)" />
              <XAxis dataKey="severity" tick={AXIS_TICK} interval={0} />
              <YAxis allowDecimals={false} tick={AXIS_TICK} width={52} tickFormatter={formatCount} />
              <Tooltip cursor={{ fill: 'var(--mui-palette-action-hover)' }} formatter={(value) => formatCount(Number(value))} />
              <Bar dataKey="count" name="Events" isAnimationActive={false}>
                {rows.map((row) => (
                  <Cell key={row.severity} fill={SEVERITY_COLORS[row.severity]} />
                ))}
                <LabelList
                  dataKey="count"
                  position="top"
                  fill="var(--mui-palette-text-primary)"
                  fontSize={12}
                  formatter={(value) => formatCount(Number(value))}
                />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Panel>
  )
}
