import { keepPreviousData, useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toApiQuery } from '../lib/eventSearch'
import type { EventSearch } from '../lib/eventSearch'
import { markChanged } from '../lib/highlights'
import type { EventStatus, IncidentEvent } from '../types/dashboard'
import type { EventPage } from '../types/events'
import { fetchJson, sendJson } from './client'
import { useLivePollInterval } from './dashboard'

export const eventKeys = {
  all: ['events'] as const,
  lists: ['events', 'list'] as const,
  list: (apiQuery: string) => ['events', 'list', apiQuery] as const,
  detail: (eventId: string) => ['events', 'detail', eventId] as const,
  statusChange: ['events', 'status-change'] as const,
}

export function eventPath(eventId: string): string {
  return `/api/events/${encodeURIComponent(eventId)}`
}

/** Polls like the dashboard, but not while a status change is in flight, so a stale poll can't undo it. */
function usePollInterval(): number | false {
  const pollInterval = useLivePollInterval()
  return useIsMutating({ mutationKey: eventKeys.statusChange }) > 0 ? false : pollInterval
}

export function useEvents(search: EventSearch) {
  const apiQuery = toApiQuery(search)
  return useQuery({
    queryKey: eventKeys.list(apiQuery),
    queryFn: () => fetchJson<EventPage>(`/api/events?${apiQuery}`),
    refetchInterval: usePollInterval(),
    // Keep the current page on screen while the next one loads.
    placeholderData: keepPreviousData,
  })
}

export function useEvent(eventId: string | undefined) {
  return useQuery({
    queryKey: eventKeys.detail(eventId ?? ''),
    queryFn: () => fetchJson<IncidentEvent>(eventPath(eventId!)),
    enabled: eventId !== undefined,
    refetchInterval: usePollInterval(),
  })
}

export type StatusChange = { eventId: string; status: EventStatus }

/** Outcome callbacks. They run even if the caller has unmounted by the time the backend answers. */
export type StatusChangeCallbacks = {
  onSuccess?: (event: IncidentEvent) => void
  onError?: (error: Error) => void
}

/**
 * Changes an incident's status optimistically: cached list pages and the detail show the new status at once,
 * and are restored if the backend rejects the change. Everything is refetched afterwards either way.
 */
export function useChangeEventStatus({ onSuccess, onError }: StatusChangeCallbacks = {}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: eventKeys.statusChange,
    mutationFn: ({ eventId, status }: StatusChange) =>
      sendJson<IncidentEvent>(`${eventPath(eventId)}/status`, 'PUT', { status }),
    onMutate: async ({ eventId, status }) => {
      await queryClient.cancelQueries({ queryKey: eventKeys.all })
      const lists = queryClient.getQueriesData<EventPage>({ queryKey: eventKeys.lists })
      const detail = queryClient.getQueryData<IncidentEvent>(eventKeys.detail(eventId))

      const patch = (event: IncidentEvent) => (event.eventId === eventId ? { ...event, status } : event)
      queryClient.setQueriesData<EventPage>({ queryKey: eventKeys.lists }, (page) =>
        page ? { ...page, content: page.content.map(patch) } : page,
      )
      if (detail) queryClient.setQueryData(eventKeys.detail(eventId), patch(detail))
      return { lists, detail }
    },
    onSuccess: (event) => {
      // Highlights even while the socket is down, since this didn't arrive through it.
      markChanged(event.eventId)
      onSuccess?.(event)
    },
    onError: (error, { eventId }, snapshot) => {
      snapshot?.lists.forEach(([key, page]) => queryClient.setQueryData(key, page))
      if (snapshot?.detail) queryClient.setQueryData(eventKeys.detail(eventId), snapshot.detail)
      onError?.(error)
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: eventKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      ]),
  })
}
