import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderApp } from './test/renderApp'

// The dashboard polls the API; these tests only care about the shell.
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<never>(() => {})))
  return () => vi.unstubAllGlobals()
})

describe('app shell', () => {
  it('redirects / to the dashboard', async () => {
    renderApp('/')
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.getByText('Rail Ops Control Center')).toBeInTheDocument()
  })

  it('shows the main navigation with the current page marked', async () => {
    renderApp('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(['Dashboard', 'Events', 'Services'])
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')
  })

  it('navigates to the events page', async () => {
    renderApp('/dashboard')
    await userEvent.click(await screen.findByRole('link', { name: 'Events' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Events' })).toBeInTheDocument()
  })

  it('shows not-found for an unknown path', async () => {
    renderApp('/nope')
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the dashboard' })).toHaveAttribute('href', '/dashboard')
  })
})

describe('theme switch', () => {
  it('defaults to System and applies and remembers Dark when chosen', async () => {
    renderApp('/dashboard')
    const group = await screen.findByRole('group', { name: 'Theme' })
    expect(within(group).getByRole('button', { name: 'System' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(within(group).getByRole('button', { name: 'Dark' }))

    expect(within(group).getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('mui-mode')).toBe('dark')
  })
})
