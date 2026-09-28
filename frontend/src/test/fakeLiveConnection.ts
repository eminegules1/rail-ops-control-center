import type { ConnectFn, ConnectHandlers } from '../api/stompConnection'

/** A fake live connection for tests: lets a test simulate connect, drop and pushed messages without a real socket. */
export type FakeLiveConnection = {
  connect: ConnectFn
  simulateConnect: () => void
  simulateDisconnect: () => void
  simulateEventMessage: (body: string) => void
  simulateSummaryMessage: (body: string) => void
}

export function createFakeLiveConnection(): FakeLiveConnection {
  let handlers: ConnectHandlers | undefined

  const connect: ConnectFn = (h) => {
    handlers = h
    return { close: () => {} }
  }

  return {
    connect,
    simulateConnect: () => handlers?.onConnect(),
    simulateDisconnect: () => handlers?.onDisconnect(),
    simulateEventMessage: (body) => handlers?.onEventMessage(body),
    simulateSummaryMessage: (body) => handlers?.onSummaryMessage(body),
  }
}
