import CssBaseline from '@mui/material/CssBaseline'
import { ThemeProvider } from '@mui/material/styles'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { theme } from './theme'

type Props = {
  children: ReactNode
  /** Tests pass their own client, e.g. with retries off. */
  queryClient?: QueryClient
}

export function AppProviders({ children, queryClient }: Props) {
  const [defaultClient] = useState(() => new QueryClient())

  return (
    <ThemeProvider theme={theme} noSsr>
      <CssBaseline />
      <QueryClientProvider client={queryClient ?? defaultClient}>{children}</QueryClientProvider>
    </ThemeProvider>
  )
}
