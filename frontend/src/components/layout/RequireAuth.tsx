import { Navigate, Outlet, useLocation } from 'react-router'
import { useSession, useSessionEnd } from '../../lib/session'

/** Sends signed-out visitors to the login page, remembering where they were unless they chose to sign out. */
export function RequireAuth() {
  const session = useSession()
  const ended = useSessionEnd()
  const location = useLocation()

  if (!session) {
    return <Navigate to="/login" replace state={ended === 'signed-out' ? undefined : { from: location }} />
  }
  return <Outlet />
}
