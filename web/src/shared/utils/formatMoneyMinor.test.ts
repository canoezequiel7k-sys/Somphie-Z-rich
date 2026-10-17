import { describe, expect, it } from 'vitest'
import { formatMoneyMinor } from './formatMoneyMinor'

describe('formatMoneyMinor', () => {
  it('formats centavos without converting them through floating-point arithmetic', () => {
    expect(formatMoneyMinor(485000, 'ARS')).toBe(
      new Intl.NumberFormat('es-AR', {
        style: 'currency',
        currency: 'ARS',
      }).format(4850),
    )
    expect(formatMoneyMinor(10, 'ARS')).toBe(
      new Intl.NumberFormat('es-AR', {
        style: 'currency',
        currency: 'ARS',
      }).format(0.1),
    )
  })

  it('rejects amounts that have already lost integer precision', () => {
    expect(() => formatMoneyMinor(Number.MAX_SAFE_INTEGER + 1, 'ARS')).toThrow(
      RangeError,
    )
  })
})
