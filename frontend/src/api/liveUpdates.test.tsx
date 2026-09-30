import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { endSession, signIn } from '../lib/session'
import { createFakeLiveConnection } from '../test/fakeLiveConnection'
import { renderApp } from '../test/renderApp'
import { sessionFor } from '../test/sessions'

// The pages poll the API; these tests only care about when the live connection is open.
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('live connection and sign-in', () => {
  it('stays closed while signed out', async () => {
    const live = createFakeLiveConnection()
    renderApp('/login', undefined, live.connect, null)

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(live.connects).toBe(0)
  })

  it('opens once signed in', async () => {
    const live = createFakeLiveConnection()
    renderApp('/dashboard', undefined, live.connect)

    expect(await screen.findByRole('status')).toHaveTextContent('Connecting')
    expect(live.connects).toBe(1)
  })

  it('opens after a sign-in and closes on sign-out', async () => {
    const live = createFakeLiveConnection()
    renderApp('/login', undefined, live.connect, null)
    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    expect(live.connects).toBe(0)

    act(() => signIn(sessionFor('ADMIN')))
    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(live.connects).toBe(1)
    expect(live.closes).toBe(0)

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(live.closes).toBe(1)
    expect(live.connects).toBe(1)
  })

  it('closes when the session expires', async () => {
    const live = createFakeLiveConnection()
    renderApp('/dashboard', undefined, live.connect)
    await screen.findByRole('navigation', { name: 'Main' })

    act(() => endSession('expired'))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(live.closes).toBe(1)
  })
})
