import { useQuery } from '@tanstack/react-query'
import type { DashboardSummary, IncidentEvent, TimelineBucket } from '../types/dashboard'
import { fetchJson } from './client'
import { useConnectionState } from './connectionState'

/** How often dashboard data refreshes while the live connection isn't up; matches the backend's 5 s summary cache. */
export const DASHBOARD_POLL_MS = 5_000
export const TIMELINE_MINUTES = 60
export const RECENT_EVENTS_LIMIT = 20

export const dashboardKeys = {
  summary: ['dashboard', 'summary'] as const,
  timeline: (minutes: number) => ['dashboard', 'timeline', minutes] as const,
  recentEvents: (limit: number) => ['dashboard', 'recent-events', limit] as const,
}

/** Polling is a fallback: pushes plus their reconciling refetches keep data fresh while the connection is live. */
export function useLivePollInterval(): number | false {
  return useConnectionState() === 'live' ? false : DASHBOARD_POLL_MS
}

export function useDashboardSummary() {
  return useQuery({
    queryKey: dashboardKeys.summary,
    queryFn: () => fetchJson<DashboardSummary>('/api/dashboard/summary'),
    refetchInterval: useLivePollInterval(),
  })
}

export function useTimeline(minutes = TIMELINE_MINUTES) {
  return useQuery({
    queryKey: dashboardKeys.timeline(minutes),
    queryFn: () => fetchJson<TimelineBucket[]>(`/api/dashboard/timeline?minutes=${minutes}`),
    refetchInterval: useLivePollInterval(),
  })
}

export function useRecentEvents(limit = RECENT_EVENTS_LIMIT) {
  return useQuery({
    queryKey: dashboardKeys.recentEvents(limit),
    queryFn: () => fetchJson<IncidentEvent[]>(`/api/dashboard/recent-events?limit=${limit}`),
    refetchInterval: useLivePollInterval(),
  })
}
