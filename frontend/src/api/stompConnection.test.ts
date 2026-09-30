import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession, signIn } from '../lib/session'
import { sessionFor } from '../test/sessions'

type Headers = Record<string, string>
type Config = { beforeConnect: (client: { connectHeaders: Headers }) => void }

const captured = vi.hoisted(() => ({ config: undefined as unknown }))

vi.mock('@stomp/stompjs', () => ({
  ReconnectionTimeMode: { EXPONENTIAL: 1 },
  Client: class {
    constructor(config: unknown) {
      captured.config = config
    }
    activate() {}
    deactivate() {}
  },
}))

const noHandlers = { onConnect() {}, onDisconnect() {}, onEventMessage() {}, onSummaryMessage() {} }

async function openConnection(): Promise<Config> {
  const { connectLive } = await import('./stompConnection')
  connectLive(noHandlers)
  return captured.config as Config
}

/** The headers the client would send if it connected now. */
function headersFor(config: Config): Headers {
  const client = { connectHeaders: {} as Headers }
  config.beforeConnect(client)
  return client.connectHeaders
}

beforeEach(() => {
  resetSession()
})

describe('connectLive', () => {
  it('puts the signed-in user token in the CONNECT frame', async () => {
    signIn({ ...sessionFor('ADMIN'), token: 'tok-1' })

    expect(headersFor(await openConnection())).toEqual({ Authorization: 'Bearer tok-1' })
  })

  it('reads the token again on every (re)connect', async () => {
    signIn({ ...sessionFor('ADMIN'), token: 'tok-1' })
    const config = await openConnection()
    const first = headersFor(config)

    signIn({ ...sessionFor('VIEWER'), token: 'tok-2' })

    expect(first).toEqual({ Authorization: 'Bearer tok-1' })
    expect(headersFor(config)).toEqual({ Authorization: 'Bearer tok-2' })
  })

  it('sends no Authorization header while signed out', async () => {
    expect(headersFor(await openConnection())).toEqual({})
  })
})
