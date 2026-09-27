import { keepPreviousData, useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toApiQuery } from '../lib/eventSearch'
import type { EventSearch } from '../lib/eventSearch'
import type { EventStatus, IncidentEvent } from '../types/dashboard'
import type { EventPage } from '../types/events'
import { fetchJson, retryUnlessClientError, sendJson } from './client'
import { DASHBOARD_POLL_MS } from './dashboard'

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
  return useIsMutating({ mutationKey: eventKeys.statusChange }) > 0 ? false : DASHBOARD_POLL_MS
}

export function useEvents(search: EventSearch) {
  const apiQuery = toApiQuery(search)
  return useQuery({
    queryKey: eventKeys.list(apiQuery),
    queryFn: () => fetchJson<EventPage>(`/api/events?${apiQuery}`),
    refetchInterval: usePollInterval(),
    retry: retryUnlessClientError,
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
    retry: retryUnlessClientError,
  })
}

type StatusChange = { eventId: string; status: EventStatus }

/**
 * Changes an incident's status optimistically: cached list pages and the detail show the new status at once,
 * and are restored if the backend rejects the change. Everything is refetched afterwards either way.
 */
export function useChangeEventStatus() {
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
    onError: (_error, { eventId }, snapshot) => {
      snapshot?.lists.forEach(([key, page]) => queryClient.setQueryData(key, page))
      if (snapshot?.detail) queryClient.setQueryData(eventKeys.detail(eventId), snapshot.detail)
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: eventKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      ]),
  })
}
