import { QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { ConnectFn } from '../api/stompConnection'
import { AppRoutes } from '../App'
import { AppProviders } from '../AppProviders'
import { createFakeLiveConnection } from './fakeLiveConnection'

/**
 * Renders the whole app at `path`; by default retries are off and the live connection is a fresh fake, so failed
 * queries error at once and no test opens a real socket. Pass a `createFakeLiveConnection().connect` to drive the
 * connection from the test.
 */
export function renderApp(
  path = '/',
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  connectLive: ConnectFn = createFakeLiveConnection().connect,
) {
  return render(
    <AppProviders queryClient={queryClient} connectLive={connectLive}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </AppProviders>,
  )
}
