import { QueryClient } from '@tanstack/react-query'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderApp } from '../test/renderApp'
import type { DashboardSummary, IncidentEvent } from '../types/dashboard'
import type { EventPage } from '../types/events'

const signalFailure: IncidentEvent = {
  eventId: 'EVT-1',
  source: 'CBTC',
  service: 'signal-service',
  severity: 'CRITICAL',
  message: '<b>Signal failure</b> at junction 4',
  status: 'OPEN',
  timestamp: '2026-09-27T12:31:00Z',
  receivedAt: '2026-09-27T12:31:00.100Z',
  updatedAt: '2026-09-27T12:31:00.100Z',
}

const doorFault: IncidentEvent = {
  ...signalFailure,
  eventId: 'EVT-2',
  source: 'TMS',
  service: 'train-tracking',
  severity: 'WARNING',
  message: 'Door fault on train 12',
  status: 'ACKNOWLEDGED',
}

const summary: DashboardSummary = {
  totalEvents: 2,
  openEvents: 1,
  acknowledgedEvents: 1,
  criticalEvents: 1,
  severityDistribution: {},
  services: [
    { name: 'signal-service', status: 'DOWN', lastEventTime: null },
    { name: 'train-tracking', status: 'DEGRADED', lastEventTime: null },
  ],
}

function page(content: IncidentEvent[], overrides: Partial<EventPage> = {}): EventPage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, ...overrides }
}

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>

/** Routes each request to a handler; returns the fetch mock so tests can inspect the calls. */
function stubApi({
  list = () => Response.json(page([signalFailure, doorFault])),
  detail = () => Response.json(signalFailure),
  status,
}: { list?: Handler; detail?: Handler; status?: Handler } = {}) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith('/api/dashboard/summary')) return Response.json(summary)
    if (url.startsWith('/api/events?')) return list(url, init)
    if (url.endsWith('/status') && init?.method === 'PUT' && status) return status(url, init)
    if (url.startsWith('/api/events/')) return detail(url, init)
    return new Response(null, { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function listUrls(fetchMock: ReturnType<typeof stubApi>): string[] {
  return fetchMock.mock.calls.map(([url]) => url).filter((url) => url.startsWith('/api/events?'))
}

function problem(status: number, detail: string) {
  return Response.json({ title: 'Error', status, detail }, { status })
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('events table', () => {
  it('shows a skeleton during the first load', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
    renderApp('/events')
    expect(await screen.findByRole('region', { name: 'Events' })).toHaveAttribute('aria-busy', 'true')
  })

  it('renders a page of events with user text as plain text', async () => {
    stubApi({ list: () => Response.json(page([signalFailure, doorFault], { totalElements: 42, totalPages: 3 })) })
    renderApp('/events')

    const row = (await screen.findByText('<b>Signal failure</b> at junction 4')).closest('tr')!
    expect(row.querySelector('b')).toBeNull()
    expect(within(row).getByText('CRITICAL')).toBeInTheDocument()
    expect(within(row).getByText('OPEN')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: 'EVT-1' })).toHaveAttribute('href', '/events/EVT-1')
    expect(screen.getByText('1–20 of 42')).toBeInTheDocument()
  })

  it('shows the empty state with no events and the filtered empty state with filters', async () => {
    stubApi({ list: () => Response.json(page([])) })
    const { unmount } = renderApp('/events')
    expect(await screen.findByText('No events yet.')).toBeInTheDocument()
    unmount()

    renderApp('/events?status=RESOLVED')
    expect(await screen.findByText('No events match these filters.')).toBeInTheDocument()
  })

  it('offers the first page when the URL page is past the end', async () => {
    const fetchMock = stubApi({
      list: (url) =>
        url.includes('page=4&')
          ? Response.json(page([], { page: 4, totalElements: 2, totalPages: 1 }))
          : Response.json(page([signalFailure, doorFault])),
    })
    renderApp('/events?page=5')

    await userEvent.click(await screen.findByRole('button', { name: 'Go to first page' }))
    expect(await screen.findByText('Door fault on train 12')).toBeInTheDocument()
    expect(listUrls(fetchMock).at(-1)).toBe('/api/events?page=0&size=20')
  })

  it('sends the URL filters to the API and pre-fills the controls', async () => {
    const fetchMock = stubApi()
    renderApp('/events?severity=CRITICAL&status=OPEN&q=signal&page=2')

    await screen.findByText('Door fault on train 12')
    expect(listUrls(fetchMock)[0]).toBe('/api/events?severity=CRITICAL&status=OPEN&q=signal&page=1&size=20')
    expect(screen.getByRole('textbox', { name: 'Search events' })).toHaveValue('signal')
    expect(screen.getByRole('combobox', { name: 'Severity' })).toHaveTextContent('CRITICAL')
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveTextContent('OPEN')
  })

  it('updates the URL and resets the page when a filter changes', async () => {
    const fetchMock = stubApi()
    renderApp('/events?page=3')
    await screen.findByText('Door fault on train 12')

    await userEvent.click(screen.getByRole('combobox', { name: 'Source' }))
    await userEvent.click(await screen.findByRole('option', { name: 'SCADA' }))

    await waitFor(() => expect(listUrls(fetchMock).at(-1)).toBe('/api/events?source=SCADA&page=0&size=20'))
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(listUrls(fetchMock).at(-1)).toBe('/api/events?page=0&size=20'))
  })

  it('debounces the search', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fetchMock = stubApi()
    renderApp('/events')
    await screen.findByText('Door fault on train 12')

    fireEvent.change(screen.getByRole('textbox', { name: 'Search events' }), { target: { value: 'door' } })
    await act(() => vi.advanceTimersByTimeAsync(200))
    expect(listUrls(fetchMock).some((url) => url.includes('q=door'))).toBe(false)

    await act(() => vi.advanceTimersByTimeAsync(150))
    await waitFor(() => expect(listUrls(fetchMock).at(-1)).toBe('/api/events?q=door&page=0&size=20'))
  })

  it('shows the API error detail when the list is rejected', async () => {
    stubApi({ list: () => problem(400, 'q must be at most 200 characters') })
    renderApp('/events')
    expect(await screen.findByText('q must be at most 200 characters')).toBeInTheDocument()
  })

  it('leaves a first load that is still retrying to the table, without the backend toast', async () => {
    let listCalls = 0
    stubApi({
      list: () => {
        listCalls += 1
        return listCalls === 1 ? Response.json({ status: 502 }, { status: 502 }) : new Promise<never>(() => {})
      },
    })
    renderApp('/events', new QueryClient({ defaultOptions: { queries: { retry: 3, retryDelay: 0 } } }))

    await waitFor(() => expect(listCalls).toBe(2))
    expect(screen.getByRole('region', { name: 'Events' })).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByText("Can't reach the backend - retrying")).not.toBeInTheDocument()
  })
})

describe('event detail drawer', () => {
  it('opens from a deep link over the filtered table and closes back to it', async () => {
    const fetchMock = stubApi()
    renderApp('/events/EVT-1?status=OPEN')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    expect(await within(drawer).findByText('<b>Signal failure</b> at junction 4')).toBeInTheDocument()
    expect(within(drawer).getByText('signal-service')).toBeInTheDocument()
    expect(within(drawer).getByText('Event ID').nextElementSibling).toHaveTextContent('EVT-1')
    expect(fetchMock).toHaveBeenCalledWith('/api/events/EVT-1', expect.anything())
    expect(listUrls(fetchMock)[0]).toBe('/api/events?status=OPEN&page=0&size=20')

    await userEvent.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // The status filter survived the round trip.
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveTextContent('OPEN')
  })

  it('shows not found for an unknown event', async () => {
    stubApi({ detail: () => problem(404, 'Event EVT-404 not found') })
    renderApp('/events/EVT-404')
    const drawer = await screen.findByRole('dialog', { name: 'EVT-404' })
    expect(await within(drawer).findByText('Event not found. No event has the ID EVT-404.')).toBeInTheDocument()
  })

  it('opens when an event ID is clicked', async () => {
    stubApi({ detail: () => Response.json(doorFault) })
    renderApp('/events')
    await userEvent.click(await screen.findByRole('link', { name: 'EVT-2' }))
    const drawer = await screen.findByRole('dialog', { name: 'EVT-2' })
    expect(await within(drawer).findByText('train-tracking')).toBeInTheDocument()
  })
})

describe('status change', () => {
  it('offers only the allowed transitions', async () => {
    stubApi({ detail: () => Response.json(doorFault) })
    renderApp('/events/EVT-2')
    const drawer = await screen.findByRole('dialog', { name: 'EVT-2' })
    expect(await within(drawer).findByRole('button', { name: 'Resolve' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Reopen' })).not.toBeInTheDocument()
  })

  it('shows the new status before the backend answers', async () => {
    let answer: (response: Response) => void = () => {}
    const fetchMock = stubApi({ status: () => new Promise<Response>((resolve) => (answer = resolve)) })
    renderApp('/events/EVT-1')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    await userEvent.click(await within(drawer).findByRole('button', { name: 'Acknowledge' }))

    expect(await within(drawer).findByText('ACKNOWLEDGED')).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Resolve' })).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/events/EVT-1/status',
      expect.objectContaining({ method: 'PUT', body: '{"status":"ACKNOWLEDGED"}' }),
    )
    answer(Response.json({ ...signalFailure, status: 'ACKNOWLEDGED' }))
  })

  it('rolls back and explains a rejected change', async () => {
    let reject: (response: Response) => void = () => {}
    stubApi({ status: () => new Promise<Response>((resolve) => (reject = resolve)) })
    renderApp('/events/EVT-1')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    await userEvent.click(await within(drawer).findByRole('button', { name: 'Resolve' }))
    const row = screen.getByRole('link', { name: 'EVT-1', hidden: true }).closest('tr')!
    await waitFor(() => expect(within(row).getByText('RESOLVED')).toBeInTheDocument())

    reject(problem(409, 'The event was changed by another request; reload it and try again'))

    expect(await screen.findByText('The event was changed by another request; reload it and try again')).toBeInTheDocument()
    expect(within(drawer).getByText('OPEN')).toBeInTheDocument()
    expect(within(row).getByText('OPEN')).toBeInTheDocument()
  })

  it('confirms a successful change, and the confirmation outlasts the drawer', async () => {
    stubApi({ status: () => Response.json({ ...signalFailure, status: 'ACKNOWLEDGED' }) })
    renderApp('/events/EVT-1')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    await userEvent.click(await within(drawer).findByRole('button', { name: 'Acknowledge' }))

    const confirmation = await screen.findByRole('status')
    expect(confirmation).toHaveTextContent('EVT-1 status changed to ACKNOWLEDGED')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await userEvent.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('status')).toHaveTextContent('EVT-1 status changed to ACKNOWLEDGED')
  })

  it('reports a rejection that arrives after the drawer was closed', async () => {
    let reject: (response: Response) => void = () => {}
    stubApi({ status: () => new Promise<Response>((resolve) => (reject = resolve)) })
    renderApp('/events/EVT-1')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    await userEvent.click(await within(drawer).findByRole('button', { name: 'Resolve' }))
    await userEvent.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    reject(problem(409, 'Cannot change status from OPEN to RESOLVED'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot change status from OPEN to RESOLVED')
    const row = screen.getByRole('link', { name: 'EVT-1' }).closest('tr')!
    await waitFor(() => expect(within(row).getByText('OPEN')).toBeInTheDocument())
  })
})
