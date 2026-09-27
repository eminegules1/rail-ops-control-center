import Chip from '@mui/material/Chip'
import { HEALTH_COLORS, SEVERITY_COLORS, contrastText } from '../../lib/colors'
import type { ServiceHealth, Severity } from '../../types/dashboard'

export function SeverityChip({ severity }: { severity: Severity }) {
  return <ColorChip label={severity} color={SEVERITY_COLORS[severity]} />
}

export function HealthChip({ health }: { health: ServiceHealth }) {
  return <ColorChip label={health} color={HEALTH_COLORS[health]} />
}

function ColorChip({ label, color }: { label: string; color: string }) {
  return (
    <Chip
      label={label}
      size="small"
      sx={{ bgcolor: color, color: contrastText(color), fontWeight: 600, fontSize: '0.7rem', height: 20 }}
    />
  )
}
