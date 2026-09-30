import { afterEach, describe, expect, it, vi } from 'vitest'
import { getSession, getSessionEnd, signIn } from '../lib/session'
import { sessionFor } from '../test/sessions'
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

describe('bearer token', () => {
  const admin = sessionFor('ADMIN')

  it('is sent on every request while signed in', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => Response.json({}))
    vi.stubGlobal('fetch', fetchMock)
    signIn(admin)

    await fetchJson('/api/events')
    await sendJson('/api/events/EVT-1/status', 'PUT', { status: 'RESOLVED' })

    expect(fetchMock.mock.calls[0][1]?.headers).toEqual({ Accept: 'application/json', Authorization: `Bearer ${admin.token}` })
    expect(fetchMock.mock.calls[1][1]?.headers).toMatchObject({ Authorization: `Bearer ${admin.token}` })
  })

  it('is left off while signed out', async () => {
    const fetchMock = stubFetch(Response.json({}))

    await fetchJson('/api/events')

    expect(fetchMock.mock.calls[0][1].headers).toEqual({ Accept: 'application/json' })
  })

  it('ends the session as expired when the API answers 401', async () => {
    stubFetch(Response.json({ title: 'Unauthorized', detail: 'Sign in to continue' }, { status: 401 }))
    signIn(admin)

    await expect(fetchJson('/api/events')).rejects.toMatchObject({ status: 401 })

    expect(getSession()).toBeNull()
    expect(getSessionEnd()).toBe('expired')
  })

  it('keeps the session when the API answers 403', async () => {
    stubFetch(Response.json({ title: 'Forbidden', detail: 'This action requires the ADMIN role' }, { status: 403 }))
    signIn(admin)

    await expect(sendJson('/api/events/EVT-1/status', 'PUT', {})).rejects.toMatchObject({
      status: 403,
      message: 'This action requires the ADMIN role',
    })

    expect(getSession()).toEqual(admin)
  })

  it('does not end a session over a 401 to a request that carried no token', async () => {
    stubFetch(Response.json({ title: 'Invalid credentials', detail: 'Invalid username or password' }, { status: 401 }))

    await expect(sendJson('/api/auth/login', 'POST', {})).rejects.toMatchObject({ status: 401 })

    expect(getSessionEnd()).toBeNull()
  })

  it('ignores a 401 for a token that is no longer the current session', async () => {
    let answer: (response: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => (answer = resolve))))
    signIn(admin)
    const pending = fetchJson('/api/events')
    signIn({ ...admin, token: 'newer' })

    answer(Response.json({}, { status: 401 }))
    await expect(pending).rejects.toMatchObject({ status: 401 })

    expect(getSession()?.token).toBe('newer')
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
