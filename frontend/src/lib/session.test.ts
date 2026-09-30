import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Session } from '../types/auth'

const STORAGE_KEY = 'railops.session'

function session(overrides: Partial<Session> = {}): Session {
  return {
    token: 'tok',
    username: 'admin',
    role: 'ADMIN',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    ...overrides,
  }
}

/** A fresh copy of the module, so its start-up read of localStorage can be tested. */
async function loadSession() {
  vi.resetModules()
  return import('./session')
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('session store', () => {
  it('signs in, persists the session and forgets it on sign-out', async () => {
    const { signIn, endSession, getSession } = await loadSession()
    const current = session()

    signIn(current)
    expect(getSession()).toEqual(current)
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual(current)

    endSession('signed-out')
    expect(getSession()).toBeNull()
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('restores a stored session that has not expired', async () => {
    const stored = session({ username: 'viewer', role: 'VIEWER' })
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))

    const { getSession } = await loadSession()

    expect(getSession()).toEqual(stored)
  })

  it.each([
    ['an expired session', JSON.stringify(session({ expiresAt: new Date(Date.now() - 1000).toISOString() }))],
    ['corrupt JSON', '{not json'],
    ['a JSON value that is not an object', '"text"'],
    ['null', 'null'],
    ['an unknown role', JSON.stringify({ ...session(), role: 'ROOT' })],
    ['a missing token', JSON.stringify({ username: 'admin', role: 'ADMIN', expiresAt: session().expiresAt })],
  ])('starts signed out for %s', async (_, stored) => {
    localStorage.setItem(STORAGE_KEY, stored)

    const { getSession } = await loadSession()

    expect(getSession()).toBeNull()
  })

  it('starts signed out when storage cannot be read', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    const { getSession } = await loadSession()

    expect(getSession()).toBeNull()
  })

  it('keeps the session in memory when storage cannot be written', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full')
    })
    const { signIn, getSession } = await loadSession()

    signIn(session())

    expect(getSession()).not.toBeNull()
  })

  it('ends the session as expired when its time comes', async () => {
    vi.useFakeTimers()
    const { signIn, getSession } = await loadSession()
    const seen: (string | null)[] = []

    signIn(session({ expiresAt: new Date(Date.now() + 5000).toISOString() }))
    vi.advanceTimersByTime(4999)
    seen.push(getSession() ? 'in' : 'out')
    vi.advanceTimersByTime(2)
    seen.push(getSession() ? 'in' : 'out')

    expect(seen).toEqual(['in', 'out'])
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })
})
