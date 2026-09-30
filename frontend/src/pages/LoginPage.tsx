import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { BrandPanel } from '../components/login/BrandPanel'
import { signIn, useSession, useSessionEnd } from '../lib/session'

type FieldErrors = { username?: string; password?: string }

/** The fixed message for a wrong password; the backend answers the same for an unknown user. */
const INVALID_CREDENTIALS = 'Invalid username or password'
const UNREACHABLE = "Couldn't reach the server. Try again."

/** What went wrong, for the alert above the button: a 401 and other 4xx answers are the server's word, anything
 * else (network failure, proxy error, 5xx) is "couldn't reach". */
function failureMessage(error: Error): string {
  if (error instanceof ApiError && error.status === 401) return INVALID_CREDENTIALS
  if (error instanceof ApiError && error.status < 500) return error.message
  return UNREACHABLE
}

function returnPath(state: unknown): string {
  const from = (state as { from?: { pathname?: unknown; search?: unknown; hash?: unknown } } | null)?.from
  if (typeof from?.pathname !== 'string' || !from.pathname.startsWith('/') || from.pathname.startsWith('//')) {
    return '/dashboard'
  }
  return `${from.pathname}${typeof from.search === 'string' ? from.search : ''}${typeof from.hash === 'string' ? from.hash : ''}`
}

export function LoginPage() {
  const session = useSession()
  const ended = useSessionEnd()
  const location = useLocation()
  const usernameInput = useRef<HTMLInputElement>(null)
  const passwordInput = useRef<HTMLInputElement>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  // Below md the brand panel is not rendered at all, so a phone never downloads its video.
  const showBrandPanel = useMediaQuery(useTheme().breakpoints.up('md'), { noSsr: true })

  const attempt = useMutation({
    mutationFn: () => login(username, password),
    onSuccess: ({ token, username: name, role, expiresAt }) => signIn({ token, username: name, role, expiresAt }),
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) setPassword('')
    },
  })

  // The fields are disabled while the request is in flight, so focus moves once they are enabled again.
  const rejected = attempt.error instanceof ApiError && attempt.error.status === 401
  useEffect(() => {
    if (rejected) passwordInput.current?.focus()
  }, [rejected])

  // Signing in sets the session, and this redirect then takes the user where they were headed.
  if (session) return <Navigate to={returnPath(location.state)} replace />

  const clearErrors = () => {
    setFieldErrors({})
    if (attempt.isError) attempt.reset()
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const errors: FieldErrors = {}
    if (!username.trim()) errors.username = 'Enter your username'
    if (!password) errors.password = 'Enter your password'
    setFieldErrors(errors)
    if (errors.username) usernameInput.current?.focus()
    else if (errors.password) passwordInput.current?.focus()
    else attempt.mutate()
  }

  return (
    <Box
      component="main"
      sx={{ minHeight: '100vh', display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr' } }}
    >
      {showBrandPanel && <BrandPanel />}
      <Box sx={{ display: 'grid', placeItems: 'center', p: 2 }}>
        <Paper variant="outlined" sx={{ width: '100%', maxWidth: 400, p: 4 }}>
          <Box
            component="img"
            src="/alstom-logo.svg"
            alt="Alstom"
            width={160}
            height={44}
            sx={(theme) => ({
              display: 'block',
              mb: 3,
              borderRadius: 1,
              // The navy wordmark disappears on a dark surface, so it sits on a white tile there.
              ...theme.applyStyles('dark', { bgcolor: '#fff', p: 1, boxSizing: 'content-box' }),
            })}
          />
          <Typography variant="h1" gutterBottom>
            Sign in
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>
            Rail Ops Control Center
          </Typography>
          <Box component="form" noValidate onSubmit={submit}>
            <Stack spacing={2}>
              {attempt.isError ? (
                <Alert severity="error" variant="outlined">
                  {failureMessage(attempt.error)}
                </Alert>
              ) : (
                ended === 'expired' && (
                  <Alert severity="info" variant="outlined">
                    Your session ended. Sign in again to continue.
                  </Alert>
                )
              )}
              <TextField
                id="login-username"
                name="username"
                label="Username"
                autoComplete="username"
                autoFocus
                value={username}
                onChange={(event) => {
                  setUsername(event.target.value)
                  clearErrors()
                }}
                inputRef={usernameInput}
                error={Boolean(fieldErrors.username)}
                helperText={fieldErrors.username}
                disabled={attempt.isPending}
                slotProps={{ htmlInput: { maxLength: 50 } }}
              />
              <TextField
                id="login-password"
                name="password"
                label="Password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value)
                  clearErrors()
                }}
                inputRef={passwordInput}
                error={Boolean(fieldErrors.password)}
                helperText={fieldErrors.password}
                disabled={attempt.isPending}
                slotProps={{ htmlInput: { maxLength: 72 } }}
              />
              <Button type="submit" variant="contained" disabled={attempt.isPending}>
                {attempt.isPending ? 'Signing in…' : 'Sign in'}
              </Button>
            </Stack>
          </Box>
        </Paper>
      </Box>
    </Box>
  )
}
