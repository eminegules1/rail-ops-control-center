import Box from '@mui/material/Box'
import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { TIMELINE_MINUTES, useTimeline } from '../../api/dashboard'
import { timelineTotal, toTimelineRows } from '../../lib/chartData'
import { SEVERITY_COLORS } from '../../lib/colors'
import { formatCount } from '../../lib/format'
import { SEVERITIES } from '../../types/dashboard'
import type { Severity } from '../../types/dashboard'
import { EmptyState, Panel } from './Panel'

const AXIS_TICK = { fill: 'var(--mui-palette-text-secondary)', fontSize: 12 }

// CRITICAL is drawn first so it sits on the baseline, where its trend reads against a flat line; INFO goes on top.
const STACK_ORDER = [...SEVERITIES].reverse()

/** Legend text wears the text color like the axes; the colored marker beside it carries the severity. */
const legendLabel = (value: string) => <span style={{ color: 'var(--mui-palette-text-secondary)' }}>{value}</span>

/** Sorts legend and tooltip items INFO to CRITICAL, i.e. the stacked bands from top to bottom. */
const bySeverity = (item: { dataKey?: unknown }) => SEVERITIES.indexOf(item.dataKey as Severity)

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
            <AreaChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="var(--mui-palette-divider)" />
              <XAxis dataKey="label" tick={AXIS_TICK} interval="preserveStartEnd" minTickGap={24} />
              <YAxis allowDecimals={false} tick={AXIS_TICK} width={52} tickFormatter={formatCount} />
              <Tooltip
                cursor={{ stroke: 'var(--mui-palette-divider)' }}
                itemSorter={bySeverity}
                formatter={(value) => formatCount(Number(value))}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} itemSorter={bySeverity} formatter={legendLabel} />
              {STACK_ORDER.map((severity) => (
                <Area
                  key={severity}
                  type="monotone"
                  dataKey={severity}
                  stackId="events"
                  stroke={SEVERITY_COLORS[severity]}
                  strokeWidth={2}
                  fill={SEVERITY_COLORS[severity]}
                  fillOpacity={0.7}
                  isAnimationActive={false}
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Panel>
  )
}
