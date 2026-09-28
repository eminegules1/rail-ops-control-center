import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HIGHLIGHT_MS, _clearHighlightsForTests, markChanged, useRecentlyChanged } from './highlights'

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  _clearHighlightsForTests()
})

describe('useRecentlyChanged', () => {
  it('is false for an event that was never marked', () => {
    const { result } = renderHook(() => useRecentlyChanged('EVT-NEVER'))
    expect(result.current).toBe(false)
  })

  it('turns true when marked and clears on its own after the highlight window', () => {
    const { result } = renderHook(() => useRecentlyChanged('EVT-1'))
    expect(result.current).toBe(false)

    act(() => markChanged('EVT-1'))
    expect(result.current).toBe(true)

    act(() => vi.advanceTimersByTime(HIGHLIGHT_MS - 1))
    expect(result.current).toBe(true)

    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toBe(false)
  })

  it('only highlights the marked event, not another one', () => {
    const { result } = renderHook(() => useRecentlyChanged('EVT-OTHER'))
    act(() => markChanged('EVT-1'))
    expect(result.current).toBe(false)
  })

  it('restarts the window on a second mark before it expires', () => {
    const { result } = renderHook(() => useRecentlyChanged('EVT-1'))

    act(() => markChanged('EVT-1'))
    act(() => vi.advanceTimersByTime(HIGHLIGHT_MS - 500))
    expect(result.current).toBe(true)

    act(() => markChanged('EVT-1'))
    act(() => vi.advanceTimersByTime(HIGHLIGHT_MS - 500))
    expect(result.current).toBe(true)

    act(() => vi.advanceTimersByTime(500))
    expect(result.current).toBe(false)
  })
})
