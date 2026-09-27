import { describe, expect, it } from 'vitest'
import { timelineTotal, toSeverityRows, toTimelineRows } from './chartData'
import { formatMinute } from './format'

describe('toTimelineRows', () => {
  it('keeps bucket order and fills missing severities with 0', () => {
    const rows = toTimelineRows([
      { minute: '2026-09-27T12:30:00Z', counts: { WARNING: 2, CRITICAL: 1 } },
      { minute: '2026-09-27T12:31:00Z', counts: {} },
    ])

    expect(rows).toEqual([
      {
        minute: '2026-09-27T12:30:00Z',
        label: formatMinute('2026-09-27T12:30:00Z'),
        INFO: 0,
        WARNING: 2,
        MAJOR: 0,
        CRITICAL: 1,
      },
      {
        minute: '2026-09-27T12:31:00Z',
        label: formatMinute('2026-09-27T12:31:00Z'),
        INFO: 0,
        WARNING: 0,
        MAJOR: 0,
        CRITICAL: 0,
      },
    ])
  })

  it('labels minutes as local HH:mm', () => {
    const [row] = toTimelineRows([{ minute: '2026-09-27T12:30:00Z', counts: {} }])
    expect(row.label).toMatch(/^\d{2}:\d{2}$/)
  })

  it('returns no rows for no buckets', () => {
    expect(toTimelineRows([])).toEqual([])
  })
})

describe('timelineTotal', () => {
  it('sums every severity across rows', () => {
    const rows = toTimelineRows([
      { minute: '2026-09-27T12:30:00Z', counts: { INFO: 1, WARNING: 2 } },
      { minute: '2026-09-27T12:31:00Z', counts: { MAJOR: 3, CRITICAL: 4 } },
    ])
    expect(timelineTotal(rows)).toBe(10)
  })
})

describe('toSeverityRows', () => {
  it('orders severities INFO to CRITICAL and defaults missing ones to 0', () => {
    expect(toSeverityRows({ CRITICAL: 5, INFO: 7 })).toEqual([
      { severity: 'INFO', count: 7 },
      { severity: 'WARNING', count: 0 },
      { severity: 'MAJOR', count: 0 },
      { severity: 'CRITICAL', count: 5 },
    ])
  })
})
