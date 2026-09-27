import { useQuery } from '@tanstack/react-query'
import type { ServiceState } from '../types/services'
import { fetchJson } from './client'
import { DASHBOARD_POLL_MS } from './dashboard'

export const serviceKeys = {
  all: ['services'] as const,
}

export function useServices() {
  return useQuery({
    queryKey: serviceKeys.all,
    queryFn: () => fetchJson<ServiceState[]>('/api/services'),
    refetchInterval: DASHBOARD_POLL_MS,
  })
}
