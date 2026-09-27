import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchJson, retryUnlessClientError, sendJson } from './client'

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchJson', () => {
  it('returns the parsed body of a 2xx response', async () => {
    const fetchMock = stubFetch(Response.json({ totalEvents: 3 }))

    await expect(fetchJson('/api/dashboard/summary')).resolves.toEqual({ totalEvents: 3 })
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard/summary', {
      headers: { Accept: 'application/json' },
    })
  })

  it('throws an ApiError with the ProblemDetail detail', async () => {
    stubFetch(
      Response.json(
        { title: 'Bad Request', status: 400, detail: 'minutes must be between 1 and 120' },
        { status: 400 },
      ),
    )

    const error = await fetchJson('/api/dashboard/timeline?minutes=0').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, message: 'minutes must be between 1 and 120' })
  })

  it('falls back to the status when the error body is not JSON', async () => {
    stubFetch(new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' }))

    await expect(fetchJson('/api/dashboard/summary')).rejects.toMatchObject({
      status: 502,
      message: 'Request failed (502 Bad Gateway)',
    })
  })

  it('rejects with the network error when fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(fetchJson('/api/dashboard/summary')).rejects.toThrow('Failed to fetch')
  })
})

describe('sendJson', () => {
  it('sends the body as JSON and returns the parsed response', async () => {
    const fetchMock = stubFetch(Response.json({ eventId: 'EVT-1', status: 'ACKNOWLEDGED' }))

    await expect(sendJson('/api/events/EVT-1/status', 'PUT', { status: 'ACKNOWLEDGED' })).resolves.toEqual({
      eventId: 'EVT-1',
      status: 'ACKNOWLEDGED',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/events/EVT-1/status', {
      method: 'PUT',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: '{"status":"ACKNOWLEDGED"}',
    })
  })

  it('throws an ApiError with the ProblemDetail detail', async () => {
    stubFetch(
      Response.json(
        { title: 'Invalid status transition', status: 409, detail: 'Cannot change status from ACKNOWLEDGED to OPEN' },
        { status: 409 },
      ),
    )

    await expect(sendJson('/api/events/EVT-1/status', 'PUT', { status: 'OPEN' })).rejects.toMatchObject({
      status: 409,
      message: 'Cannot change status from ACKNOWLEDGED to OPEN',
    })
  })
})

describe('retryUnlessClientError', () => {
  it('never retries a 4xx answer', () => {
    expect(retryUnlessClientError(0, new ApiError(404, 'not found'))).toBe(false)
    expect(retryUnlessClientError(0, new ApiError(400, 'bad request'))).toBe(false)
  })

  it('retries server and network errors up to 3 times', () => {
    expect(retryUnlessClientError(0, new ApiError(503, 'unavailable'))).toBe(true)
    expect(retryUnlessClientError(2, new TypeError('Failed to fetch'))).toBe(true)
    expect(retryUnlessClientError(3, new ApiError(500, 'error'))).toBe(false)
  })
})
