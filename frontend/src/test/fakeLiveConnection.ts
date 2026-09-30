import type { ConnectFn, ConnectHandlers } from '../api/stompConnection'

/** A fake live connection for tests: lets a test simulate connect, drop and pushed messages without a real socket. */
export type FakeLiveConnection = {
  connect: ConnectFn
  /** How many connections the app opened, and how many it closed again. */
  connects: number
  closes: number
  simulateConnect: () => void
  simulateDisconnect: () => void
  simulateEventMessage: (body: string) => void
  simulateSummaryMessage: (body: string) => void
}

export function createFakeLiveConnection(): FakeLiveConnection {
  let handlers: ConnectHandlers | undefined
  let connects = 0
  let closes = 0

  const connect: ConnectFn = (h) => {
    handlers = h
    connects++
    return {
      close: () => {
        closes++
      },
    }
  }

  return {
    connect,
    get connects() {
      return connects
    },
    get closes() {
      return closes
    },
    simulateConnect: () => handlers?.onConnect(),
    simulateDisconnect: () => handlers?.onDisconnect(),
    simulateEventMessage: (body) => handlers?.onEventMessage(body),
    simulateSummaryMessage: (body) => handlers?.onSummaryMessage(body),
  }
}
