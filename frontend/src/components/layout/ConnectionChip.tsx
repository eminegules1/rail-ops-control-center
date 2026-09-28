import Chip from '@mui/material/Chip'
import type { ChipProps } from '@mui/material/Chip'
import { useConnectionState } from '../../api/connectionState'
import type { ConnectionState } from '../../types/live'

const LABELS: Record<ConnectionState, string> = {
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
}

const COLORS: Record<ConnectionState, ChipProps['color']> = {
  connecting: 'warning',
  live: 'success',
  reconnecting: 'warning',
  offline: 'error',
}

/** Live / reconnecting / offline state of the real-time push connection; the label carries the meaning, not color
 * alone, and `role="status"` announces changes to screen readers. */
export function ConnectionChip() {
  const state = useConnectionState()

  return (
    <Chip
      role="status"
      aria-live="polite"
      label={LABELS[state]}
      color={COLORS[state]}
      size="small"
      variant={state === 'live' ? 'filled' : 'outlined'}
    />
  )
}
