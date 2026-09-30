export type Role = 'ADMIN' | 'VIEWER'

/** A signed-in user's bearer token; `expiresAt` is an ISO-8601 instant. */
export type Session = {
  token: string
  username: string
  role: Role
  expiresAt: string
}

/** Why the last session ended; the login page and the route guard behave differently for each. */
export type SessionEnd = 'signed-out' | 'expired'
