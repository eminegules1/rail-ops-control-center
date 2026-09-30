import CssBaseline from '@mui/material/CssBaseline'
import { ThemeProvider } from '@mui/material/styles'
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { LiveUpdatesProvider } from './api/liveUpdates'
import { retryUnlessClientError } from './api/client'
import { connectLive } from './api/stompConnection'
import type { ConnectFn } from './api/stompConnection'
import { useSession } from './lib/session'
import { theme } from './theme'

/** Empties the query cache whenever nobody is signed in, so the next user never sees the last user's data. */
function ClearCacheWhenSignedOut() {
  const signedIn = useSession() !== null
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!signedIn) queryClient.clear()
  }, [signedIn, queryClient])
  return null
}

type Props = {
  children: ReactNode
  /** Tests pass their own client, e.g. with retries off. The app's client never retries a 4xx answer. */
  queryClient?: QueryClient
  /** Tests pass a fake so no test opens a real socket. */
  connectLive?: ConnectFn
}

export function AppProviders({ children, queryClient, connectLive: connect = connectLive }: Props) {
  const [defaultClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: retryUnlessClientError } } }),
  )

  return (
    <ThemeProvider theme={theme} noSsr>
      <CssBaseline />
      <QueryClientProvider client={queryClient ?? defaultClient}>
        <ClearCacheWhenSignedOut />
        <LiveUpdatesProvider connect={connect}>{children}</LiveUpdatesProvider>
      </QueryClientProvider>
    </ThemeProvider>
  )
}
