import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import { getSession } from '../lib/session'

/** Matches the backend's heartbeat (WebSocketConfig). */
const HEARTBEAT_MS = 10_000
const RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 30_000

export type ConnectHandlers = {
  onConnect: () => void
  onDisconnect: () => void
  onEventMessage: (body: string) => void
  onSummaryMessage: (body: string) => void
}

export type LiveConnection = {
  close: () => void
}

/** Opens the live connection and wires the given handlers; returns a handle to close it. Tests substitute a fake. */
export type ConnectFn = (handlers: ConnectHandlers) => LiveConnection

/**
 * The real STOMP-over-WebSocket connection to same-origin `/ws` (Feature 12). One `Client` per call; reconnects on
 * its own with exponential backoff and never sends - the broker only pushes. The CONNECT frame carries the login
 * token (Feature 20); the browser cannot put a header on the WebSocket handshake itself.
 */
export const connectLive: ConnectFn = (handlers) => {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const client = new Client({
    brokerURL: `${protocol}//${location.host}/ws`,
    reconnectDelay: RECONNECT_DELAY_MS,
    maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: HEARTBEAT_MS,
    heartbeatOutgoing: HEARTBEAT_MS,
    // Read at every (re)connect, so a reconnect after a new sign-in never presents the old token.
    beforeConnect: (stomp) => {
      const token = getSession()?.token
      stomp.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {}
    },
    onConnect: () => {
      client.subscribe('/topic/events', (message) => handlers.onEventMessage(message.body))
      client.subscribe('/topic/summary', (message) => handlers.onSummaryMessage(message.body))
      handlers.onConnect()
    },
    onWebSocketClose: () => handlers.onDisconnect(),
  })
  client.activate()
  return { close: () => client.deactivate() }
}
