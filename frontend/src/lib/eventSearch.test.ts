import { describe, expect, it } from 'vitest'
import { hasFilters, parseEventSearch, toApiQuery, toEventSearchParams } from './eventSearch'

describe('parseEventSearch', () => {
  it('reads every filter, the search and the page', () => {
    const search = parseEventSearch(
      new URLSearchParams('severity=CRITICAL&status=OPEN&source=CBTC&service=signal-service&q=signal&page=2'),
    )
    expect(search).toEqual({
      severity: 'CRITICAL',
      status: 'OPEN',
      source: 'CBTC',
      service: 'signal-service',
      q: 'signal',
      page: 2,
    })
  })

  it('defaults to no filters on page 1', () => {
    expect(parseEventSearch(new URLSearchParams())).toEqual({
      severity: undefined,
      status: undefined,
      source: undefined,
      service: undefined,
      q: '',
      page: 1,
    })
  })

  it('drops values the API would reject', () => {
    const search = parseEventSearch(new URLSearchParams('severity=critical&status=CLOSED&source=%20&service='))
    expect(search.severity).toBeUndefined()
    expect(search.status).toBeUndefined()
    expect(search.source).toBeUndefined()
    expect(search.service).toBeUndefined()
  })

  it.each(['0', '-1', '1.5', 'abc', '', '99999999999'])('treats page %j as page 1', (page) => {
    expect(parseEventSearch(new URLSearchParams({ page })).page).toBe(1)
  })

  it('trims the search and caps it at 200 characters', () => {
    expect(parseEventSearch(new URLSearchParams({ q: '  signal  ' })).q).toBe('signal')
    expect(parseEventSearch(new URLSearchParams({ q: 'x'.repeat(250) })).q).toHaveLength(200)
  })
})

describe('toEventSearchParams', () => {
  it('leaves out empty values and page 1', () => {
    expect(toEventSearchParams({ q: '', page: 1 }).toString()).toBe('')
    expect(toEventSearchParams({ status: 'OPEN', q: '', page: 1 }).toString()).toBe('status=OPEN')
  })

  it('round-trips through parseEventSearch', () => {
    const query = 'severity=MAJOR&status=RESOLVED&source=ATS&service=route-service&q=door+fault&page=3'
    expect(toEventSearchParams(parseEventSearch(new URLSearchParams(query))).toString()).toBe(query)
  })
})

describe('toApiQuery', () => {
  it('uses a zero-based page and the fixed page size', () => {
    expect(toApiQuery({ severity: 'CRITICAL', status: 'OPEN', q: 'signal', page: 2 })).toBe(
      'severity=CRITICAL&status=OPEN&q=signal&page=1&size=20',
    )
    expect(toApiQuery({ q: '', page: 1 })).toBe('page=0&size=20')
  })

  it('encodes search text', () => {
    expect(toApiQuery({ q: 'a&b=c', page: 1 })).toBe('q=a%26b%3Dc&page=0&size=20')
  })
})

describe('hasFilters', () => {
  it('is true once any filter or search is set', () => {
    expect(hasFilters({ q: '', page: 3 })).toBe(false)
    expect(hasFilters({ q: 'x', page: 1 })).toBe(true)
    expect(hasFilters({ source: 'PIS', q: '', page: 1 })).toBe(true)
  })
})
