import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useCallback, useMemo } from 'react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { useEvents } from '../api/events'
import { EventDetailDrawer } from '../components/events/EventDetailDrawer'
import { EventFilters } from '../components/events/EventFilters'
import { EventTable } from '../components/events/EventTable'
import { BackendErrorToast } from '../components/layout/BackendErrorToast'
import { EMPTY_SEARCH, hasFilters, parseEventSearch, toEventSearchParams } from '../lib/eventSearch'
import type { EventSearch } from '../lib/eventSearch'

/** `/events`, and `/events/:eventId` with that event's detail drawer open over the same table. */
export function EventsPage() {
  const { eventId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const search = useMemo(() => parseEventSearch(searchParams), [searchParams])
  const setSearch = useCallback(
    (next: EventSearch, replace = false) => setSearchParams(toEventSearchParams(next), { replace }),
    [setSearchParams],
  )
  const clearFilters = useCallback(() => setSearch(EMPTY_SEARCH), [setSearch])

  const events = useEvents(search)
  const failedWithoutData = events.isError && !events.data

  return (
    // minmax(0, …) lets the wide table scroll inside its panel instead of widening the page.
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'minmax(0, 1fr)' }}>
      <Typography variant="h1">Events</Typography>
      <EventFilters search={search} onChange={setSearch} onClear={clearFilters} />
      <EventTable
        data={events.data}
        loading={events.isPending}
        error={failedWithoutData ? events.error.message : undefined}
        page={search.page}
        filtered={hasFilters(search)}
        onPageChange={(page) => setSearch({ ...search, page })}
        onClearFilters={clearFilters}
      />
      <EventDetailDrawer
        eventId={eventId}
        onClose={() => navigate({ pathname: '/events', search: location.search })}
      />
      <BackendErrorToast open={events.isError && !failedWithoutData} />
    </Box>
  )
}
