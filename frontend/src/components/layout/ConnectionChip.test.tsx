import { act, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createFakeLiveConnection } from '../../test/fakeLiveConnection'
import { renderApp } from '../../test/renderApp'

// The chip renders synchronously on mount, so these assertions never need to await anything; that keeps the
// fake-timer clock (the 10s offline threshold) independent of the sandbox's real wall-clock speed.
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
  vi.useFakeTimers()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('connection chip', () => {
  it('shows connecting, live, reconnecting, offline, then live again', () => {
    const live = createFakeLiveConnection()
    renderApp('/dashboard', undefined, live.connect)

    expect(screen.getByRole('status')).toHaveTextContent('Connecting')

    act(() => live.simulateConnect())
    expect(screen.getByRole('status')).toHaveTextContent('Live')

    act(() => live.simulateDisconnect())
    expect(screen.getByRole('status')).toHaveTextContent('Reconnecting')

    act(() => vi.advanceTimersByTime(9_999))
    expect(screen.getByRole('status')).toHaveTextContent('Reconnecting')

    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByRole('status')).toHaveTextContent('Offline')

    act(() => live.simulateConnect())
    expect(screen.getByRole('status')).toHaveTextContent('Live')
  })

  it('does not go offline before 10s when it never connects at all', () => {
    renderApp('/dashboard')

    expect(screen.getByRole('status')).toHaveTextContent('Connecting')
    act(() => vi.advanceTimersByTime(9_999))
    expect(screen.getByRole('status')).toHaveTextContent('Connecting')

    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByRole('status')).toHaveTextContent('Offline')
  })
})
