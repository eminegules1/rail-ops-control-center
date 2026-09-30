import { describe, expect, it } from 'vitest'
import { formatCount, formatDateTime, formatMinute, formatTime } from './format'

describe('formatTime', () => {
  it('formats an ISO time as local HH:mm:ss', () => {
    expect(formatTime('2026-09-27T12:30:05.123Z')).toMatch(/^\d{2}:\d{2}:05$/)
  })

  it('shows a dash for a missing or invalid time', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime(undefined)).toBe('—')
    expect(formatTime('not-a-date')).toBe('—')
  })
})

describe('formatMinute', () => {
  it('returns an empty label for an invalid time', () => {
    expect(formatMinute('nope')).toBe('')
  })
})

describe('formatDateTime', () => {
  it('shows a dash for a missing time', () => {
    expect(formatDateTime(null)).toBe('—')
  })

  it('includes the seconds of a valid time', () => {
    expect(formatDateTime('2026-09-27T12:30:05Z')).toContain('05')
  })
})

describe('formatCount', () => {
  it('groups thousands with commas whatever the process locale', () => {
    expect(formatCount(100197)).toBe('100,197')
    expect(formatCount(50097)).toBe('50,097')
  })
})

describe('pinned locale', () => {
  it('uses a 24 hour clock and an English month name', () => {
    expect(formatTime('2026-09-27T23:30:05Z')).toMatch(/^\d{2}:\d{2}:05$/)
    expect(formatDateTime('2026-09-30T12:00:00Z')).toMatch(/^30 Sept 2026, \d{2}:\d{2}:\d{2}$/)
  })
})
