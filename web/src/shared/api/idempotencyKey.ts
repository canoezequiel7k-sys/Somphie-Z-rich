interface StoredKey {
  bodyHash: string
  key: string
}

const keyPrefix = 'somphie:idempotency:'
const uuidV4Pattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function storageKey(scope: string): string {
  return `${keyPrefix}${encodeURIComponent(scope)}`
}

async function hashBody(body: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(body))
  const digest = await window.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
}

function newUuidV4(): string {
  const bytes = window.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function readStoredKey(scope: string): StoredKey | null {
  const raw = window.sessionStorage.getItem(storageKey(scope))
  if (raw === null) {
    return null
  }

  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new Error('INVALID_PENDING_IDEMPOTENCY_KEY')
  }

  if (
    typeof value !== 'object' ||
    value === null ||
    !('bodyHash' in value) ||
    typeof value.bodyHash !== 'string' ||
    !('key' in value) ||
    typeof value.key !== 'string' ||
    !uuidV4Pattern.test(value.key)
  ) {
    throw new Error('INVALID_PENDING_IDEMPOTENCY_KEY')
  }

  return { bodyHash: value.bodyHash, key: value.key }
}

/**
 * Conserva una clave por alcance y cuerpo para que recargar o reintentar una
 * petición no convierta una misma operación en dos efectos distintos.
 */
export async function getIdempotencyKey(
  scope: string,
  body: unknown,
): Promise<string> {
  const bodyHash = await hashBody(body)
  const stored = readStoredKey(scope)
  if (stored?.bodyHash === bodyHash) {
    return stored.key
  }

  const key = newUuidV4()
  window.sessionStorage.setItem(
    storageKey(scope),
    JSON.stringify({ bodyHash, key } satisfies StoredKey),
  )
  return key
}

export function clearIdempotencyKey(scope: string): void {
  window.sessionStorage.removeItem(storageKey(scope))
}
