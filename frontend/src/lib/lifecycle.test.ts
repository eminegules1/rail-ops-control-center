import { describe, expect, it } from 'vitest'
import { allowedTransitions } from './lifecycle'

describe('allowedTransitions', () => {
  it('follows the incident lifecycle', () => {
    expect(allowedTransitions('OPEN')).toEqual(['ACKNOWLEDGED', 'RESOLVED'])
    expect(allowedTransitions('ACKNOWLEDGED')).toEqual(['RESOLVED'])
    expect(allowedTransitions('RESOLVED')).toEqual(['OPEN'])
  })
})
