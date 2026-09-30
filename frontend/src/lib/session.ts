import { useSyncExternalStore } from 'react'
import type { Role, Session, SessionEnd } from '../types/auth'

const STORAGE_KEY = 'railops.session'

type State = { session: Session | null; ended: SessionEnd | null }

let state: State = { session: readStored(), ended: null }
let expiryTimer: ReturnType<typeof setTimeout> | undefined
const listeners = new Set<() => void>()

function isRole(value: unknown): value is Role {
  return value === 'ADMIN' || value === 'VIEWER'
}

function isLive(session: Session): boolean {
  return Date.parse(session.expiresAt) > Date.now()
}

// Storage can be missing or throw (private windows, blocked site data); the app then just starts signed out.
function readStored(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null
    const { token, username, role, expiresAt } = value as Record<string, unknown>
    if (typeof token !== 'string' || typeof username !== 'string' || typeof expiresAt !== 'string' || !isRole(role)) {
      return null
    }
    const session = { token, username, role, expiresAt }
    return isLive(session) ? session : null
  } catch {
    return null
  }
}

function writeStored(session: Session | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // The session then lives only in memory.
  }
}

function update(next: State) {
  state = next
  clearTimeout(expiryTimer)
  if (next.session) {
    const remaining = Date.parse(next.session.expiresAt) - Date.now()
    expiryTimer = setTimeout(() => endSession('expired'), Math.max(remaining, 0))
  }
  listeners.forEach((listener) => listener())
}

update(state)

export function getSession(): Session | null {
  return state.session
}

export function getSessionEnd(): SessionEnd | null {
  return state.ended
}

export function signIn(session: Session) {
  writeStored(session)
  update({ session, ended: null })
}

export function endSession(reason: SessionEnd) {
  writeStored(null)
  update({ session: null, ended: reason })
}

/** Forgets the session without giving a reason, as if the app had just opened signed out. */
export function resetSession() {
  writeStored(null)
  update({ session: null, ended: null })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, () => state.session)
}

export function useSessionEnd(): SessionEnd | null {
  return useSyncExternalStore(subscribe, getSessionEnd)
}
