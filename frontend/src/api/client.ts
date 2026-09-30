import { endSession, getSession } from '../lib/session'

/** A non-2xx API response; `message` is the ProblemDetail detail when the backend sent one. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Query retry rule: a 4xx answer won't change on retry, so show it at once; retry the rest up to 3 times. */
export function retryUnlessClientError(failureCount: number, error: Error): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  return failureCount < 3
}

type ProblemDetail = { title?: unknown; detail?: unknown }

export async function fetchJson<T>(url: string): Promise<T> {
  return request<T>(url, { headers: { Accept: 'application/json' } })
}

/** Sends `body` as JSON and returns the JSON response, e.g. for a PUT. */
export async function sendJson<T>(url: string, method: 'POST' | 'PUT', body: unknown): Promise<T> {
  return request<T>(url, {
    method,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function request<T>(url: string, init: RequestInit): Promise<T> {
  const token = getSession()?.token
  const response = await fetch(
    url,
    token ? { ...init, headers: { ...init.headers, Authorization: `Bearer ${token}` } } : init,
  )
  if (!response.ok) {
    // A 401 to a request that carried this session's token means the token is no longer accepted. A 403 keeps the
    // session: the user is signed in but not allowed to do that.
    if (response.status === 401 && token && getSession()?.token === token) endSession('expired')
    throw new ApiError(response.status, await errorMessage(response))
  }
  return (await response.json()) as T
}

async function errorMessage(response: Response): Promise<string> {
  const fallback = `Request failed (${response.status}${response.statusText ? ` ${response.statusText}` : ''})`
  try {
    const problem = (await response.json()) as ProblemDetail
    if (typeof problem.detail === 'string' && problem.detail) return problem.detail
    if (typeof problem.title === 'string' && problem.title) return problem.title
  } catch {
    // Not JSON, e.g. an nginx or Vite proxy error page.
  }
  return fallback
}
