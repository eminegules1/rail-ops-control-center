import { useQuery } from '@tanstack/react-query'
import type { DashboardSummary, IncidentEvent, TimelineBucket } from '../types/dashboard'
import { fetchJson } from './client'

/** How often dashboard data refreshes; matches the backend's 5 s summary cache. */
export const DASHBOARD_POLL_MS = 5_000
export const TIMELINE_MINUTES = 60
export const RECENT_EVENTS_LIMIT = 20

export const dashboardKeys = {
  summary: ['dashboard', 'summary'] as const,
  timeline: (minutes: number) => ['dashboard', 'timeline', minutes] as const,
  recentEvents: (limit: number) => ['dashboard', 'recent-events', limit] as const,
}

export function useDashboardSummary() {
  return useQuery({
    queryKey: dashboardKeys.summary,
    queryFn: () => fetchJson<DashboardSummary>('/api/dashboard/summary'),
    refetchInterval: DASHBOARD_POLL_MS,
  })
}

export function useTimeline(minutes = TIMELINE_MINUTES) {
  return useQuery({
    queryKey: dashboardKeys.timeline(minutes),
    queryFn: () => fetchJson<TimelineBucket[]>(`/api/dashboard/timeline?minutes=${minutes}`),
    refetchInterval: DASHBOARD_POLL_MS,
  })
}

export function useRecentEvents(limit = RECENT_EVENTS_LIMIT) {
  return useQuery({
    queryKey: dashboardKeys.recentEvents(limit),
    queryFn: () => fetchJson<IncidentEvent[]>(`/api/dashboard/recent-events?limit=${limit}`),
    refetchInterval: DASHBOARD_POLL_MS,
  })
}
