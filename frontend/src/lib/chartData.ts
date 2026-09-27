import { SEVERITIES } from '../types/dashboard'
import type { Severity, SeverityCounts, TimelineBucket } from '../types/dashboard'
import { formatMinute } from './format'

export type TimelineRow = { minute: string; label: string } & Record<Severity, number>

export type SeverityRow = { severity: Severity; count: number }

/** Flattens timeline buckets into chart rows, with a missing severity counted as 0. */
export function toTimelineRows(buckets: TimelineBucket[]): TimelineRow[] {
  return buckets.map((bucket) => ({
    minute: bucket.minute,
    label: formatMinute(bucket.minute),
    ...countsBySeverity(bucket.counts),
  }))
}

/** One row per severity in INFO, WARNING, MAJOR, CRITICAL order. */
export function toSeverityRows(distribution: SeverityCounts): SeverityRow[] {
  return SEVERITIES.map((severity) => ({ severity, count: distribution[severity] ?? 0 }))
}

export function timelineTotal(rows: TimelineRow[]): number {
  return rows.reduce((sum, row) => sum + SEVERITIES.reduce((s, severity) => s + row[severity], 0), 0)
}

function countsBySeverity(counts: SeverityCounts): Record<Severity, number> {
  return {
    INFO: counts.INFO ?? 0,
    WARNING: counts.WARNING ?? 0,
    MAJOR: counts.MAJOR ?? 0,
    CRITICAL: counts.CRITICAL ?? 0,
  }
}
