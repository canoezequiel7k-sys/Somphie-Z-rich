export function formatMoneyMinor(minor: number, currency: string): string {
  if (!Number.isSafeInteger(minor)) {
    throw new RangeError('The money minor value is not a safe integer.')
  }

  const amount = BigInt(minor)
  const formatter = new Intl.NumberFormat('es-AR', {
    style: 'currency',
    currency,
  })
  const digits = formatter.resolvedOptions().maximumFractionDigits
  if (digits === undefined) {
    throw new RangeError('The currency does not expose valid fraction digits.')
  }
  const scale = 10n ** BigInt(digits)
  const negative = amount < 0n
  const absolute = negative ? -amount : amount
  const whole = absolute / scale
  const fraction = (absolute % scale).toString().padStart(digits, '0')
  const parts = new Intl.NumberFormat('es-AR', {
    style: 'currency',
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).formatToParts(negative ? -whole : whole)

  return parts
    .map((part) => (part.type === 'fraction' ? fraction : part.value))
    .join('')
}
