import { QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { ConnectFn } from '../api/stompConnection'
import { AppRoutes } from '../App'
import { AppProviders } from '../AppProviders'
import { resetSession, signIn } from '../lib/session'
import type { Session } from '../types/auth'
import { createFakeLiveConnection } from './fakeLiveConnection'
import { sessionFor } from './sessions'

/**
 * Renders the whole app at `path`; by default retries are off and the live connection is a fresh fake, so failed
 * queries error at once and no test opens a real socket. Pass a `createFakeLiveConnection().connect` to drive the
 * connection from the test. The user is signed in as an ADMIN unless a `session` (or `null` for signed out) is given.
 */
export function renderApp(
  path = '/',
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  connectLive: ConnectFn = createFakeLiveConnection().connect,
  session: Session | null = sessionFor('ADMIN'),
) {
  if (session) signIn(session)
  else resetSession()
  return render(
    <AppProviders queryClient={queryClient} connectLive={connectLive}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </AppProviders>,
  )
}
