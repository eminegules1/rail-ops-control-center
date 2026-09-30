// The UI copy is English only, so numbers and dates must not follow the browser locale.
const LOCALE = 'en-GB'

const timeFormat = new Intl.DateTimeFormat(LOCALE, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const minuteFormat = new Intl.DateTimeFormat(LOCALE, {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const dateTimeFormat = new Intl.DateTimeFormat(LOCALE, {
  dateStyle: 'medium',
  timeStyle: 'medium',
  hour12: false,
})

function parse(iso: string): Date | null {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

/** Local `HH:mm:ss`, or an em dash for a missing or invalid time. */
export function formatTime(iso: string | null | undefined): string {
  const date = iso ? parse(iso) : null
  return date ? timeFormat.format(date) : '—'
}

/** Local `HH:mm` for chart axes. */
export function formatMinute(iso: string): string {
  const date = parse(iso)
  return date ? minuteFormat.format(date) : ''
}

/** Local date and time, e.g. for a "last event" cell. */
export function formatDateTime(iso: string | null | undefined): string {
  const date = iso ? parse(iso) : null
  return date ? dateTimeFormat.format(date) : '—'
}

const countFormat = new Intl.NumberFormat(LOCALE)

export function formatCount(value: number): string {
  return countFormat.format(value)
}
