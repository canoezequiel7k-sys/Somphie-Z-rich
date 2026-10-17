import type { CreateOrderRequest } from '../../../shared/api/types'

export interface PendingOrderAttempt {
  body: CreateOrderRequest
  idempotencyKey: string
}

function storageKey(sessionId: string): string {
  return `somphie:pending-order:${encodeURIComponent(sessionId)}`
}

function isCreateOrderRequest(value: unknown): value is CreateOrderRequest {
  if (typeof value !== 'object' || value === null || !('items' in value) || !Array.isArray(value.items)) {
    return false
  }
  if (
    !('acknowledgedTotalMinor' in value) ||
    typeof value.acknowledgedTotalMinor !== 'number' ||
    !Number.isSafeInteger(value.acknowledgedTotalMinor)
  ) {
    return false
  }

  return value.items.every((item) => {
    if (typeof item !== 'object' || item === null) {
      return false
    }
    return (
      'productId' in item &&
      typeof item.productId === 'string' &&
      'quantity' in item &&
      typeof item.quantity === 'number' &&
      Number.isInteger(item.quantity) &&
      (!('optionIds' in item) ||
        (Array.isArray(item.optionIds) &&
        item.optionIds.every((optionId: unknown) => typeof optionId === 'string')))
    )
  })
}

/**
 * Un timeout deja incierto si el servidor alcanzó a confirmar la operación.
 * Por eso se conserva el cuerpo exacto junto con su clave hasta conocer un
 * resultado definitivo; al recargar la página se puede repetir con seguridad.
 */
export function readPendingOrderAttempt(
  sessionId: string,
): PendingOrderAttempt | null {
  const raw = window.sessionStorage.getItem(storageKey(sessionId))
  if (raw === null) {
    return null
  }

  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new Error('INVALID_PENDING_ORDER_ATTEMPT')
  }

  if (
    typeof value !== 'object' ||
    value === null ||
    !('body' in value) ||
    !isCreateOrderRequest(value.body) ||
    !('idempotencyKey' in value) ||
    typeof value.idempotencyKey !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.idempotencyKey)
  ) {
    throw new Error('INVALID_PENDING_ORDER_ATTEMPT')
  }

  return { body: value.body, idempotencyKey: value.idempotencyKey }
}

export function savePendingOrderAttempt(
  sessionId: string,
  attempt: PendingOrderAttempt,
): void {
  window.sessionStorage.setItem(
    storageKey(sessionId),
    JSON.stringify(attempt),
  )
}

export function clearPendingOrderAttempt(sessionId: string): void {
  window.sessionStorage.removeItem(storageKey(sessionId))
}
