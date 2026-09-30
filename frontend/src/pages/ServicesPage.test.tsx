import { QueryClient } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderApp } from '../test/renderApp'
import type { ServiceState } from '../types/services'

const services: ServiceState[] = [
  {
    name: 'signal-service',
    status: 'DOWN',
    lastEventTime: '2026-09-27T12:31:00Z',
    latestSeverity: 'CRITICAL',
    openCount: 1234,
    activeCount: 1300,
  },
  {
    name: 'route & depot',
    status: null,
    lastEventTime: null,
    latestSeverity: null,
    openCount: 0,
    activeCount: 0,
  },
]

/** Answers /api/services with `body`, or every request with `status`; events requests get an empty page. */
function stubApi(response: { body: ServiceState[] } | { status: number }) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if ('status' in response) return Response.json({ status: response.status }, { status: response.status })
      if (url === '/api/services') return Response.json(response.body)
      if (url.startsWith('/api/events?')) {
        return Response.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      }
      if (url.startsWith('/api/dashboard/summary')) {
        return Response.json({
          totalEvents: 0,
          openEvents: 0,
          acknowledgedEvents: 0,
          criticalEvents: 0,
          severityDistribution: {},
          services: [],
        })
      }
      return new Response(null, { status: 404 })
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/** Retries at once, as the real app does 3 times, so a test can hold a retry open on a pending response. */
function retryingClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: 3, retryDelay: 0 } } })
}

describe('services page', () => {
  it('shows a skeleton during the first load', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
    renderApp('/services')
    expect(await screen.findByRole('region', { name: 'Service status' })).toHaveAttribute('aria-busy', 'true')
  })

  it('shows each service with health, counts, latest severity and last event', async () => {
    stubApi({ body: services })
    renderApp('/services')

    const row = (await screen.findByRole('link', { name: 'signal-service' })).closest('tr')!
    const cells = within(row).getAllByRole('cell')
    expect(cells.map((cell) => cell.textContent)).toEqual([
      'signal-service',
      'DOWN',
      '1,234',
      '1,300',
      'CRITICAL',
      expect.stringMatching(/\d{2}:\d{2}:\d{2}/),
    ])
    expect(screen.queryByText("Can't reach the backend - retrying")).not.toBeInTheDocument()
  })

  it('shows dashes for missing fields', async () => {
    stubApi({ body: services })
    renderApp('/services')

    const row = (await screen.findByRole('link', { name: 'route & depot' })).closest('tr')!
    expect(within(row).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'route & depot',
      '—',
      '0',
      '0',
      '—',
      '—',
    ])
  })

  it('links each service to its filtered events', async () => {
    stubApi({ body: services })
    renderApp('/services')

    expect(await screen.findByRole('link', { name: 'route & depot' })).toHaveAttribute(
      'href',
      '/events?service=route+%26+depot',
    )
    await userEvent.click(screen.getByRole('link', { name: 'signal-service' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Events' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Service' })).toHaveTextContent('signal-service')
  })

  it('shows the empty state before any service has reported', async () => {
    stubApi({ body: [] })
    renderApp('/services')
    expect(await screen.findByText('No services have reported events yet.')).toBeInTheDocument()
  })

  it('shows the error state and the backend toast when the API fails', async () => {
    stubApi({ status: 500 })
    renderApp('/services')
    expect(await screen.findByText("Couldn't load service status.")).toBeInTheDocument()
    expect(screen.getByText("Can't reach the backend - retrying")).toBeInTheDocument()
  })

  it('shows the backend toast on the first failed attempt and closes it when the retry succeeds', async () => {
    let answerRetry: (response: Response) => void = () => {}
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ status: 502 }, { status: 502 }))
      .mockReturnValueOnce(new Promise<Response>((resolve) => (answerRetry = resolve)))
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/services', retryingClient())

    // The retry is still waiting, so the query has not errored yet, but the toast is already up.
    expect(await screen.findByText("Can't reach the backend - retrying")).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.queryByText("Couldn't load service status.")).not.toBeInTheDocument()

    await act(async () => answerRetry(Response.json(services)))
    expect(await screen.findByRole('link', { name: 'signal-service' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText("Can't reach the backend - retrying")).not.toBeInTheDocument())
  })
})
