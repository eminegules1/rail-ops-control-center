import { sendJson } from './client'
import type { Session } from '../types/auth'

/** The login response is exactly a session plus the fixed token type. */
export type LoginResponse = Session & { tokenType: 'Bearer' }

export function login(username: string, password: string): Promise<LoginResponse> {
  return sendJson<LoginResponse>('/api/auth/login', 'POST', { username, password })
}
