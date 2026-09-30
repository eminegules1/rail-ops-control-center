import { describe, expect, it } from 'vitest'
import { contrastRatio, contrastText, HEALTH_COLORS, SEVERITY_COLORS } from './colors'

describe('contrastText', () => {
  const backgrounds = [...Object.entries(SEVERITY_COLORS), ...Object.entries(HEALTH_COLORS)]

  it.each(backgrounds)('reaches WCAG AA on %s (%s)', (_name, background) => {
    expect(contrastRatio(contrastText(background), background)).toBeGreaterThanOrEqual(4.5)
  })

  it('picks white on a dark background', () => {
    expect(contrastText('#102030')).toBe('#ffffff')
  })
})
