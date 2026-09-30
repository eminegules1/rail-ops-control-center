import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  parseEventMessage,
  parseSummaryMessage,
  patchEventDetail,
  patchEventPage,
  patchRecentEvents,
  throttle,
} from '../lib/liveCache'
import type { IncidentEvent } from '../types/dashboard'
import type { ConnectionState } from '../types/live'
import type { EventPage } from '../types/events'
import { ConnectionStateContext } from './connectionState'
import { dashboardKeys, RECENT_EVENTS_LIMIT } from './dashboard'
import { eventKeys } from './events'
import type { StatusChange } from './events'
import { markChanged } from '../lib/highlights'
import { useSession } from '../lib/session'
import { serviceKeys } from './services'
import type { ConnectFn } from './stompConnection'
import { connectLive as defaultConnectLive } from './stompConnection'

/** No connection within this long (from start, or from leaving `live`) reads as offline rather than reconnecting. */
const OFFLINE_AFTER_MS = 10_000
/** Leading + trailing window for reconciling refetches triggered by a live push. */
const REFETCH_THROTTLE_MS = 1_000
/** The recent-events prefix shared by every limit a page might cache (see `dashboardKeys.recentEvents`). */
const RECENT_EVENTS_PREFIX = ['dashboard', 'recent-events']

type Props = {
  children: ReactNode
  /** Tests pass a fake so no test opens a real socket. */
  connect?: ConnectFn
}

/** Opens one live connection while someone is signed in, patches the query cache from its pushes, and exposes its
 * connection state via `useConnectionState`. */
export function LiveUpdatesProvider({ children, connect = defaultConnectLive }: Props) {
  const [state, setStateRaw] = useState<ConnectionState>('connecting')
  const stateRef = useRef<ConnectionState>('connecting')
  const offlineTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const queryClient = useQueryClient()
  const signedIn = useSession() !== null

  const setState = (next: ConnectionState) => {
    stateRef.current = next
    setStateRaw(next)
  }

  useEffect(() => {
    // Nobody signed in, so the server would refuse the connection anyway.
    if (!signedIn) return

    // A status-change mutation for this event is in flight: its own onSettled refetch will reconcile, so a push
    // arriving in the meantime (possibly older than the change being sent) is skipped rather than applied.
    const isChangingStatus = (eventId: string) =>
      queryClient
        .getMutationCache()
        .getAll()
        .some(
          (mutation) =>
            mutation.state.status === 'pending' &&
            mutation.options.mutationKey?.length === eventKeys.statusChange.length &&
            mutation.options.mutationKey.every((part, i) => part === eventKeys.statusChange[i]) &&
            (mutation.state.variables as StatusChange | undefined)?.eventId === eventId,
        )

    const refetchLists = throttle(() => queryClient.invalidateQueries({ queryKey: eventKeys.lists }), REFETCH_THROTTLE_MS)
    const refetchTimeline = throttle(
      () => queryClient.invalidateQueries({ queryKey: ['dashboard', 'timeline'] }),
      REFETCH_THROTTLE_MS,
    )
    const refetchServices = throttle(() => queryClient.invalidateQueries({ queryKey: serviceKeys.all }), REFETCH_THROTTLE_MS)

    const startOfflineTimer = () => {
      clearTimeout(offlineTimer.current)
      offlineTimer.current = setTimeout(() => setState('offline'), OFFLINE_AFTER_MS)
    }
    startOfflineTimer()

    const connection = connect({
      onConnect: () => {
        clearTimeout(offlineTimer.current)
        setState('live')
        // Delivery is best-effort and there is no replay, so every (re)connect reconciles over REST.
        queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        queryClient.invalidateQueries({ queryKey: eventKeys.all })
        queryClient.invalidateQueries({ queryKey: serviceKeys.all })
      },
      onDisconnect: () => {
        if (stateRef.current !== 'live') return
        setState('reconnecting')
        startOfflineTimer()
      },
      onEventMessage: (body) => {
        const message = parseEventMessage(body)
        if (!message || isChangingStatus(message.event.eventId)) return

        // Looped via getQueriesData rather than the updater form of setQueriesData, so each cached list's own
        // limit (baked into its query key) is available for the trim.
        queryClient.getQueriesData<IncidentEvent[]>({ queryKey: RECENT_EVENTS_PREFIX }).forEach(([key, data]) => {
          if (!data) return
          const limit = (key[2] as number | undefined) ?? RECENT_EVENTS_LIMIT
          queryClient.setQueryData(key, patchRecentEvents(data, message, limit))
        })
        queryClient.setQueriesData<EventPage>({ queryKey: eventKeys.lists }, (data) =>
          data ? patchEventPage(data, message) : data,
        )
        queryClient.setQueryData<IncidentEvent>(eventKeys.detail(message.event.eventId), (current) =>
          patchEventDetail(current, message),
        )
        markChanged(message.event.eventId)

        refetchLists()
        refetchServices()
        if (message.type === 'CREATED') refetchTimeline()
      },
      onSummaryMessage: (body) => {
        const summary = parseSummaryMessage(body)
        if (summary) queryClient.setQueryData(dashboardKeys.summary, summary)
      },
    })

    return () => {
      clearTimeout(offlineTimer.current)
      connection.close()
      // The next sign-in starts from "connecting" again.
      setState('connecting')
    }
  }, [connect, queryClient, signedIn])

  return <ConnectionStateContext.Provider value={state}>{children}</ConnectionStateContext.Provider>
}
