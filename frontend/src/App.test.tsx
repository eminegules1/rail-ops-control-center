import { QueryClient } from '@tanstack/react-query'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession } from './lib/session'
import { renderApp } from './test/renderApp'
import { sessionFor } from './test/sessions'

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

describe('signed-in user', () => {
  it('shows who is signed in and their role', async () => {
    renderApp('/dashboard', undefined, undefined, sessionFor('VIEWER'))

    await userEvent.click(await screen.findByRole('button', { name: 'VIEWER' }))

    expect(await screen.findByText('Access control & role')).toBeInTheDocument()
    expect(screen.getByText('viewer')).toBeInTheDocument()
    expect(screen.getByText('Read-only: Telemetry monitoring without modification permissions.')).toBeInTheDocument()
    expect(screen.queryByText('viewer · VIEWER')).not.toBeInTheDocument()
  })

  it('labels the badge with the ADMIN role', async () => {
    renderApp('/dashboard')

    expect(await screen.findByRole('button', { name: 'ADMIN' })).toBeInTheDocument()
  })

  it('signs out to the login page and forgets the session', async () => {
    renderApp('/dashboard')

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(getSession()).toBeNull()
    expect(localStorage.getItem('railops.session')).toBeNull()
  })

  it('empties the query cache on sign-out', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    queryClient.setQueryData(['events', 'list', 'x'], { content: [] })
    renderApp('/dashboard', queryClient)

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })

  it('returns to the login page when the API rejects the token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ title: 'Unauthorized' }, { status: 401 })))
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(getSession()).toBeNull()
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
