import { QueryClient } from '@tanstack/react-query'
import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderApp } from '../test/renderApp'
import type { DashboardSummary, IncidentEvent, TimelineBucket } from '../types/dashboard'

type Responses = {
  summary: DashboardSummary
  timeline: TimelineBucket[]
  recent: IncidentEvent[]
}

/** Answers each dashboard URL with its JSON, or with `status` for every request. */
function stubApi(responses: Responses | { status: number }) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if ('status' in responses) {
        return Response.json({ title: 'Internal Server Error', status: responses.status }, { status: responses.status })
      }
      if (url.startsWith('/api/dashboard/summary')) return Response.json(responses.summary)
      if (url.startsWith('/api/dashboard/timeline')) return Response.json(responses.timeline)
      if (url.startsWith('/api/dashboard/recent-events')) return Response.json(responses.recent)
      return new Response(null, { status: 404 })
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const populated: Responses = {
  summary: {
    totalEvents: 1234,
    openEvents: 120,
    acknowledgedEvents: 30,
    criticalEvents: 12,
    severityDistribution: { INFO: 900, WARNING: 200, MAJOR: 100, CRITICAL: 34 },
    services: [
      { name: 'route-service', status: 'HEALTHY', lastEventTime: '2026-09-27T12:30:05Z' },
      { name: 'signal-service', status: 'DOWN', lastEventTime: '2026-09-27T12:31:00Z' },
    ],
  },
  timeline: [
    { minute: '2026-09-27T12:30:00Z', counts: { INFO: 1, WARNING: 2, MAJOR: 0, CRITICAL: 1 } },
    { minute: '2026-09-27T12:31:00Z', counts: { INFO: 0, WARNING: 0, MAJOR: 0, CRITICAL: 0 } },
  ],
  recent: [
    {
      eventId: 'EVT-1',
      source: 'CBTC',
      service: 'signal-service',
      severity: 'CRITICAL',
      message: '<b>Signal failure</b> at junction 4',
      status: 'OPEN',
      timestamp: '2026-09-27T12:31:00Z',
      receivedAt: '2026-09-27T12:31:00.100Z',
      updatedAt: '2026-09-27T12:31:00.100Z',
    },
  ],
}

const empty: Responses = {
  summary: {
    totalEvents: 0,
    openEvents: 0,
    acknowledgedEvents: 0,
    criticalEvents: 0,
    severityDistribution: { INFO: 0, WARNING: 0, MAJOR: 0, CRITICAL: 0 },
    services: [],
  },
  timeline: [{ minute: '2026-09-27T12:31:00Z', counts: { INFO: 0, WARNING: 0, MAJOR: 0, CRITICAL: 0 } }],
  recent: [],
}

describe('dashboard page', () => {
  it('shows skeletons while the first load is in progress', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
    renderApp('/dashboard')

    const health = await screen.findByRole('region', { name: 'Service health' })
    expect(health).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('region', { name: 'Total events' })).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('region', { name: 'Recent events' })).toHaveAttribute('aria-busy', 'true')
  })

  it('shows KPIs, service health, charts and recent events', async () => {
    stubApi(populated)
    renderApp('/dashboard')

    const total = screen.getByRole('region', { name: 'Total events' })
    expect(await within(total).findByText((1234).toLocaleString())).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Open' })).getByText('120')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Critical' })).getByText('12')).toBeInTheDocument()

    const health = screen.getByRole('region', { name: 'Service health' })
    const signal = within(health).getByText('signal-service').closest('li')!
    expect(within(signal).getByText('DOWN')).toBeInTheDocument()
    const route = within(health).getByText('route-service').closest('li')!
    expect(within(route).getByText('HEALTHY')).toBeInTheDocument()

    expect(
      screen.getByRole('img', { name: 'Events by severity: INFO 900, WARNING 200, MAJOR 100, CRITICAL 34' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: /last 60 minutes, 4 in total/ })).toBeInTheDocument()

    const recent = screen.getByRole('region', { name: 'Recent events' })
    const row = (await within(recent).findByText('<b>Signal failure</b> at junction 4')).closest('tr')!
    // User text renders as text, never as markup.
    expect(row.querySelector('b')).toBeNull()
    expect(within(row).getByText('CRITICAL')).toBeInTheDocument()
    expect(within(row).getByText('CBTC')).toBeInTheDocument()
    expect(within(row).getByText('OPEN')).toBeInTheDocument()

    expect(screen.queryByText("Can't reach the backend - retrying")).not.toBeInTheDocument()
  })

  it('shows empty states when there are no events yet', async () => {
    stubApi(empty)
    renderApp('/dashboard')

    expect(await screen.findByText('No services have reported events yet.')).toBeInTheDocument()
    expect(await screen.findByText('No events in the last 60 minutes.')).toBeInTheDocument()
    const severity = screen.getByRole('region', { name: 'Severity distribution' })
    expect(within(severity).getByText('No events yet.')).toBeInTheDocument()
    const recent = screen.getByRole('region', { name: 'Recent events' })
    expect(await within(recent).findByText('No events yet.')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Total events' })).getByText('0')).toBeInTheDocument()
  })

  it('shows error states and the backend toast when the API fails', async () => {
    stubApi({ status: 500 })
    renderApp('/dashboard')

    expect(await screen.findByText("Can't reach the backend - retrying")).toBeInTheDocument()
    expect(await screen.findByText("Couldn't load service health.")).toBeInTheDocument()
    expect(screen.getByText("Couldn't load recent events.")).toBeInTheDocument()
    expect(screen.getByText("Couldn't load severity distribution.")).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Total events' })).getByText('—')).toBeInTheDocument()
  })

  it('shows the backend toast while a failed query is still retrying', async () => {
    let summaryCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.startsWith('/api/dashboard/summary')) {
          summaryCalls += 1
          // The first attempt fails; the retry never answers, so the query never reaches its error state.
          return summaryCalls === 1 ? Response.json({ status: 502 }, { status: 502 }) : new Promise<never>(() => {})
        }
        if (url.startsWith('/api/dashboard/timeline')) return Response.json(populated.timeline)
        if (url.startsWith('/api/dashboard/recent-events')) return Response.json(populated.recent)
        return new Response(null, { status: 404 })
      }),
    )
    renderApp('/dashboard', new QueryClient({ defaultOptions: { queries: { retry: 3, retryDelay: 0 } } }))

    expect(await screen.findByText("Can't reach the backend - retrying")).toBeInTheDocument()
    expect(summaryCalls).toBe(2)
    expect(screen.queryByText("Couldn't load service health.")).not.toBeInTheDocument()
  })
})
