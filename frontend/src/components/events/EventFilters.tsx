import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import { useEffect, useState } from 'react'
import { useDashboardSummary } from '../../api/dashboard'
import { MAX_QUERY_LENGTH, hasFilters } from '../../lib/eventSearch'
import type { EventSearch } from '../../lib/eventSearch'
import { SEVERITIES } from '../../types/dashboard'
import { EVENT_STATUSES, SOURCES } from '../../types/events'

export const SEARCH_DEBOUNCE_MS = 300

type Props = {
  search: EventSearch
  /** `replace` swaps the history entry instead of pushing one, for search typing. */
  onChange: (next: EventSearch, replace?: boolean) => void
  onClear: () => void
}

export function EventFilters({ search, onChange, onClear }: Props) {
  const summary = useDashboardSummary()
  const serviceNames = (summary.data?.services ?? []).map((service) => service.name)
  if (search.service && !serviceNames.includes(search.service)) serviceNames.push(search.service)
  serviceNames.sort()

  const [draft, setDraft] = useState(search.q)
  const [urlQ, setUrlQ] = useState(search.q)
  // The URL's search changed from outside the field (clear filters, back button): show it.
  if (search.q !== urlQ) {
    setUrlQ(search.q)
    if (normalize(draft) !== search.q) setDraft(search.q)
  }

  useEffect(() => {
    const q = normalize(draft)
    if (q === search.q) return
    const timer = setTimeout(() => onChange({ ...search, q, page: 1 }, true), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [draft, search, onChange])

  const select = (key: 'severity' | 'status' | 'source' | 'service', value: string) =>
    onChange({ ...search, [key]: value || undefined, page: 1 })

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center' }}>
      <TextField
        label="Search events"
        placeholder="Message, service or event ID"
        size="small"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        slotProps={{ htmlInput: { maxLength: MAX_QUERY_LENGTH } }}
        sx={{ flex: '1 1 240px' }}
      />
      <FilterSelect label="Severity" value={search.severity} options={SEVERITIES} onChange={(v) => select('severity', v)} />
      <FilterSelect label="Status" value={search.status} options={EVENT_STATUSES} onChange={(v) => select('status', v)} />
      <FilterSelect label="Source" value={search.source} options={SOURCES} onChange={(v) => select('source', v)} />
      <FilterSelect label="Service" value={search.service} options={serviceNames} onChange={(v) => select('service', v)} />
      {hasFilters(search) && <Button onClick={onClear}>Clear filters</Button>}
    </Box>
  )
}

function normalize(draft: string): string {
  return draft.trim().slice(0, MAX_QUERY_LENGTH)
}

type FilterSelectProps = {
  label: string
  value: string | undefined
  options: readonly string[]
  onChange: (value: string) => void
}

function FilterSelect({ label, value, options, onChange }: FilterSelectProps) {
  return (
    <TextField
      select
      label={label}
      size="small"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      sx={{ minWidth: 150 }}
    >
      <MenuItem value="">All</MenuItem>
      {options.map((option) => (
        <MenuItem key={option} value={option}>
          {option}
        </MenuItem>
      ))}
    </TextField>
  )
}
