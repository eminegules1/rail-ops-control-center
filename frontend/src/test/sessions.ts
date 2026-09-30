import type { Role, Session } from '../types/auth'

/** A session that is valid for the rest of any test run. */
export function sessionFor(role: Role, username = role.toLowerCase()): Session {
  return { token: `test-token-${username}`, username, role, expiresAt: new Date(Date.now() + 8 * 3_600_000).toISOString() }
}
