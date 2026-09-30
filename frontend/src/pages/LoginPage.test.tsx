import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getSession } from '../lib/session'
import { renderApp } from '../test/renderApp'
import { sessionFor } from '../test/sessions'

const adminLogin = { ...sessionFor('ADMIN'), tokenType: 'Bearer' }

type Handler = (init?: RequestInit) => Response | Promise<Response>

/** Answers the login request with `login`; every other request is a 404, which is all these tests need. */
function stubApi(login: Handler = () => Response.json(adminLogin)) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) =>
    url === '/api/auth/login' ? login(init) : new Response(null, { status: 404 }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderLogin(path = '/login') {
  return renderApp(path, undefined, undefined, null)
}

async function signInAs(username: string, password: string) {
  await userEvent.type(screen.getByLabelText('Username'), username)
  await userEvent.type(screen.getByLabelText('Password'), password)
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/** jsdom has no layout, so a test says whether the `md` breakpoint (min-width) matches. */
function setDesktop(desktop: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query) =>
      ({
        matches: desktop && query.includes('min-width'),
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as MediaQueryList,
  )
}

describe('login page layout', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the Alstom logo above the form', async () => {
    stubApi()
    renderLogin()

    const logo = await screen.findByRole('img', { name: 'Alstom' })
    expect(logo).toHaveAttribute('src', '/alstom-logo.svg')
  })

  it('has no video and only one heading below the md breakpoint', async () => {
    setDesktop(false)
    stubApi()
    const { container } = renderLogin()

    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    expect(container.querySelector('video')).toBeNull()
    expect(screen.queryByText('Alstom Rail Operations Control Center')).not.toBeInTheDocument()
  })

  it('adds the looping background video and the product name from md up', async () => {
    setDesktop(true)
    stubApi()
    const { container } = renderLogin()

    expect(await screen.findByText('Alstom Rail Operations Control Center')).toBeInTheDocument()
    expect(screen.getByText('Real-time incident monitoring & fleet telemetry.')).toBeInTheDocument()
    const video = container.querySelector('video')!
    expect(video).toHaveAttribute('src', '/Alstom_History_Innovation.mp4')
    expect(video).toHaveAttribute('poster', '/login_page.jpg')
    expect(video).toHaveAttribute('autoplay')
    expect(video).toHaveAttribute('loop')
    expect(video).toHaveAttribute('playsinline')
    expect(video).toHaveProperty('muted', true)
    // Decoration only: hidden from assistive technology and out of the tab order.
    expect(video).toHaveAttribute('aria-hidden', 'true')
    expect(video).toHaveAttribute('tabindex', '-1')
    // The form and its only heading are still there.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })
})

describe('login page', () => {
  it('sends a signed-out visitor to the login page', async () => {
    stubApi()
    renderLogin('/dashboard')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })

  it('labels the fields for password managers', async () => {
    stubApi()
    renderLogin()

    expect(await screen.findByLabelText('Username')).toHaveAttribute('autocomplete', 'username')
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })

  it('names an empty username and focuses it, without calling the server', async () => {
    const fetchMock = stubApi()
    renderLogin()

    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(screen.getByText('Enter your username')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveFocus()
    expect(screen.getByLabelText('Username')).toBeInvalid()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('focuses the password when only it is empty', async () => {
    stubApi()
    renderLogin()

    await userEvent.type(await screen.findByLabelText('Username'), 'admin')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveFocus()
  })

  it('submits with Enter and signs in as the returned user', async () => {
    const fetchMock = stubApi()
    renderLogin()

    await userEvent.type(await screen.findByLabelText('Username'), 'admin')
    await userEvent.type(screen.getByLabelText('Password'), 'RailOps#Admin2026{Enter}')

    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(getSession()).toMatchObject({ username: 'admin', role: 'ADMIN' })
    const [, init] = fetchMock.mock.calls[0]
    expect(init).toMatchObject({ method: 'POST', body: '{"username":"admin","password":"RailOps#Admin2026"}' })
    expect(init?.headers).not.toHaveProperty('Authorization')
  })

  it('explains wrong credentials, keeps the username and refocuses the empty password', async () => {
    stubApi(() =>
      Response.json({ title: 'Invalid credentials', detail: 'Invalid username or password' }, { status: 401 }),
    )
    renderLogin()

    await signInAs('admin', 'wrong')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(screen.getByLabelText('Username')).toHaveValue('admin')
    expect(screen.getByLabelText('Password')).toHaveValue('')
    await waitFor(() => expect(screen.getByLabelText('Password')).toHaveFocus())
    expect(getSession()).toBeNull()
  })

  it('clears the error when the user edits a field', async () => {
    stubApi(() => Response.json({ title: 'Invalid credentials' }, { status: 401 }))
    renderLogin()
    await signInAs('admin', 'wrong')
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Password'), 'x')

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each([
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['a proxy error', () => new Response('<html>Bad Gateway</html>', { status: 502 })],
  ])('says the server could not be reached after %s', async (_, failure) => {
    stubApi(failure)
    renderLogin()

    await signInAs('admin', 'RailOps#Admin2026')

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't reach the server. Try again.")
    expect(screen.getByLabelText('Username')).toHaveValue('admin')
  })

  it('disables the form while signing in', async () => {
    let answer: (response: Response) => void = () => {}
    stubApi(() => new Promise<Response>((resolve) => (answer = resolve)))
    renderLogin()

    await signInAs('admin', 'RailOps#Admin2026')

    const button = await screen.findByRole('button', { name: 'Signing in…' })
    expect(button).toBeDisabled()
    expect(screen.getByLabelText('Username')).toBeDisabled()
    answer(Response.json(adminLogin))
    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument()
  })

  it('returns to the page the visitor was headed for, drawer and all', async () => {
    stubApi()
    renderLogin('/events/EVT-1?status=OPEN')

    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    await signInAs('admin', 'RailOps#Admin2026')

    const drawer = await screen.findByRole('dialog', { name: 'EVT-1' })
    expect(await within(drawer).findByText(/Event not found/)).toBeInTheDocument()
  })

  it('goes to the dashboard when there is nowhere to return to', async () => {
    stubApi()
    renderLogin()

    await signInAs('admin', 'RailOps#Admin2026')

    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
  })

  it('returns to the page the session ended on, with a notice', async () => {
    let loggedIn = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/api/auth/login') {
          loggedIn = true
          return Response.json(adminLogin)
        }
        return new Response(null, { status: loggedIn ? 404 : 401 })
      }),
    )
    renderApp('/events/EVT-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Your session ended. Sign in again to continue.')).toBeInTheDocument()
    await signInAs('admin', 'RailOps#Admin2026')

    expect(await screen.findByRole('dialog', { name: 'EVT-1' })).toBeInTheDocument()
  })

  it('goes to the dashboard after an explicit sign-out, not back to the page left', async () => {
    stubApi()
    renderApp('/services')
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    await screen.findByRole('heading', { level: 1, name: 'Sign in' })
    expect(screen.queryByText('Your session ended. Sign in again to continue.')).not.toBeInTheDocument()
    await signInAs('admin', 'RailOps#Admin2026')

    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
  })

  it('sends a signed-in user away from the login page', async () => {
    stubApi()
    renderApp('/login')

    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
  })
})
