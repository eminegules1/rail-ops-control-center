import Box from '@mui/material/Box'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { TIMELINE_MINUTES, useTimeline } from '../../api/dashboard'
import { timelineTotal, toTimelineRows } from '../../lib/chartData'
import { SEVERITY_COLORS } from '../../lib/colors'
import { SEVERITIES } from '../../types/dashboard'
import { EmptyState, Panel } from './Panel'

const AXIS_TICK = { fill: 'var(--mui-palette-text-secondary)', fontSize: 12 }

export function TimelineChart() {
  const { data, isPending, isError } = useTimeline(TIMELINE_MINUTES)
  const rows = toTimelineRows(data ?? [])
  const total = timelineTotal(rows)

  return (
    <Panel
      title={`Events over time (last ${TIMELINE_MINUTES} min)`}
      loading={isPending}
      failed={isError && !data}
      skeletonHeight={240}
    >
      {total === 0 ? (
        <EmptyState>No events in the last {TIMELINE_MINUTES} minutes.</EmptyState>
      ) : (
        <Box
          role="img"
          aria-label={`Events per minute by severity over the last ${TIMELINE_MINUTES} minutes, ${total} in total`}
          sx={{ height: 240 }}
        >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="var(--mui-palette-divider)" />
              <XAxis dataKey="label" tick={AXIS_TICK} interval="preserveStartEnd" minTickGap={24} />
              <YAxis allowDecimals={false} tick={AXIS_TICK} width={40} />
              <Tooltip cursor={{ fill: 'var(--mui-palette-action-hover)' }} />
              {/* Keep INFO to CRITICAL order instead of Recharts' default alphabetical sort. */}
              <Legend wrapperStyle={{ fontSize: 12 }} itemSorter={null} />
              {SEVERITIES.map((severity) => (
                <Bar
                  key={severity}
                  dataKey={severity}
                  stackId="events"
                  fill={SEVERITY_COLORS[severity]}
                  isAnimationActive={false}
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Panel>
  )
}
