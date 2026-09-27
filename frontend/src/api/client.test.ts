import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchJson } from './client'

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
