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

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((start) => {
    const channel = parseInt(hex.slice(start, start + 2), 16) / 255
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two `#rrggbb` colors. */
export function contrastRatio(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (light + 0.05) / (dark + 0.05)
}

/** Black or white, whichever reads better on top of a severity or health color. */
export function contrastText(background: string): string {
  return contrastRatio(background, '#000000') >= contrastRatio(background, '#ffffff') ? '#000000' : '#ffffff'
}
