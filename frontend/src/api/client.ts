/** A non-2xx API response; `message` is the ProblemDetail detail when the backend sent one. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

type ProblemDetail = { title?: unknown; detail?: unknown }

export async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
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
