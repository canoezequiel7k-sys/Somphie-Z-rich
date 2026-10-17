import { ApiError } from '../api/client'
import { text } from './index'

const errorTextByCode: Record<string, Parameters<typeof text>[0]> = {
  NETWORK_ERROR: 'connectionError',
  INVALID_RESPONSE: 'unexpectedError',
  UNEXPECTED_RESPONSE: 'unexpectedError',
  TABLE_CODE_INVALID: 'tableCodeInvalid',
  TABLE_UNAVAILABLE: 'tableUnavailable',
  RESTAURANT_CLOSED: 'tableClosed',
  SESSION_ALREADY_OPEN: 'sessionAlreadyOpen',
  JOIN_CODE_REQUIRED: 'genericError',
  JOIN_CODE_INVALID: 'joinCodeInvalid',
  JOIN_CODE_RATE_LIMITED: 'joinCodeRateLimited',
  SESSION_FULL: 'sessionFull',
  SESSION_CLOSED: 'sessionClosed',
  TOKEN_REVOKED: 'genericError',
  TOKEN_EXPIRED: 'genericError',
  UNAUTHENTICATED: 'genericError',
  PRICE_CHANGED: 'priceChangedDescription',
  PRODUCT_UNAVAILABLE: 'productUnavailable',
  OPTION_UNAVAILABLE: 'optionUnavailable',
  OPTION_SELECTION_INVALID: 'optionRequired',
  OPTION_NOT_FOUND: 'optionUnavailable',
  ORDER_PENDING_LIMIT_REACHED: 'orderSendError',
  ORDER_EMPTY: 'emptyCartDescription',
  ORDER_TOO_MANY_LINES: 'cartLineLimit',
  ORDER_QUANTITY_EXCEEDED: 'quantityLimit',
  ORDER_NOT_CANCELLABLE: 'orderSendError',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return text(errorTextByCode[error.code] ?? 'unexpectedError')
  }

  return text('unexpectedError')
}
