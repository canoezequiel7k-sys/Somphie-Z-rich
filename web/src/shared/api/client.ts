import type {
  CurrentSession,
  CreateOrderRequest,
  JoinSessionRequest,
  MenuResponse,
  Order,
  OrderPage,
  OpenSessionRequest,
  PublicTable,
  SessionAccessResponse,
} from './types'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1').replace(/\/+$/, '')

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly extra: Record<string, unknown>

  constructor(
    code: string,
    status: number,
    extra: Record<string, unknown> = {},
  ) {
    super(code)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.extra = extra
  }
}

function isProblem(
  value: unknown,
): value is { code: string; extra?: Record<string, unknown> } {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'string' &&
    (!('extra' in value) ||
      (typeof value.extra === 'object' &&
        value.extra !== null &&
        !Array.isArray(value.extra)))
  )
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('Accept-Language', 'es-AR')
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      ...init,
      headers,
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', 0)
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new ApiError('INVALID_RESPONSE', response.status)
  }

  if (!response.ok) {
    throw new ApiError(
      isProblem(body) ? body.code : 'UNEXPECTED_RESPONSE',
      response.status,
      isProblem(body) ? body.extra : {},
    )
  }

  return body as T
}

export function getPublicTable(tableCode: string): Promise<PublicTable> {
  return request(`/public/tables/${encodeURIComponent(tableCode)}`)
}

export function getMenu(restaurantId?: string): Promise<MenuResponse> {
  const query = restaurantId ? `?restaurantId=${encodeURIComponent(restaurantId)}` : ''
  return request(`/menu${query}`)
}

export function openSession(
  tableCode: string,
  requestBody: OpenSessionRequest,
  idempotencyKey: string,
): Promise<SessionAccessResponse> {
  return request(`/public/tables/${encodeURIComponent(tableCode)}/sessions`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(requestBody),
  })
}

export function joinSession(
  tableCode: string,
  requestBody: JoinSessionRequest,
): Promise<SessionAccessResponse> {
  return request(`/public/tables/${encodeURIComponent(tableCode)}/join`, {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
}

export function getCurrentSession(guestToken: string): Promise<CurrentSession> {
  return request('/sessions/current', {
    headers: { Authorization: `Bearer ${guestToken}` },
  })
}

export function createOrder(
  guestToken: string,
  body: CreateOrderRequest,
  idempotencyKey: string,
): Promise<Order> {
  return request('/sessions/current/orders', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${guestToken}`,
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(body),
  })
}

export function getSessionOrders(
  guestToken: string,
  limit = 50,
): Promise<OrderPage> {
  return request(`/sessions/current/orders?limit=${limit}`, {
    headers: { Authorization: `Bearer ${guestToken}` },
  })
}

export function productImageUrl(path: string, version: number): string {
  const mediaBaseUrl =
    import.meta.env.VITE_MEDIA_BASE_URL ?? `${apiBaseUrl}/media/`
  const base = mediaBaseUrl.endsWith('/') ? mediaBaseUrl : `${mediaBaseUrl}/`
  return `${base}${path}?v=${version}`
}
