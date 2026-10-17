import { clearIdempotencyKey, getIdempotencyKey } from './idempotencyKey'
import type { OpenSessionRequest } from './types'

export async function getOpenSessionKey(
  tableCode: string,
  body: OpenSessionRequest,
): Promise<string> {
  return getIdempotencyKey(`open-session:${tableCode}`, body)
}

export function clearOpenSessionKey(tableCode: string): void {
  clearIdempotencyKey(`open-session:${tableCode}`)
}
