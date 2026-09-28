import { createContext, useContext } from 'react'
import type { ConnectionState } from '../types/live'

/** Kept in its own module (not liveUpdates.tsx) so that file can export only the provider component. */
export const ConnectionStateContext = createContext<ConnectionState>('connecting')

export function useConnectionState(): ConnectionState {
  return useContext(ConnectionStateContext)
}
