import type { ServiceHealth, Severity } from '../types/dashboard'

// The one severity and health color mapping. Mid-tone shades so the colors
// read on both the light and dark scheme; labels always accompany them.
export const SEVERITY_COLORS: Record<Severity, string> = {
  INFO: '#1e88e5',
  WARNING: '#f9a825',
  MAJOR: '#ef6c00',
  CRITICAL: '#e53935',
}

export const HEALTH_COLORS: Record<ServiceHealth, string> = {
  HEALTHY: '#43a047',
  DEGRADED: '#f9a825',
  DOWN: '#e53935',
}

/** Text color that stays readable on top of a severity or health color. */
export function contrastText(background: string): string {
  return background === SEVERITY_COLORS.WARNING ? '#000' : '#fff'
}
