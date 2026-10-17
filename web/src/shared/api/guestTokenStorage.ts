const tokenKeyPrefix = 'somphie:guest-token:'

function keyFor(tableCode: string): string {
  return `${tokenKeyPrefix}${encodeURIComponent(tableCode)}`
}

export const guestTokenStorage = {
  get(tableCode: string): string | null {
    return window.localStorage.getItem(keyFor(tableCode))
  },

  set(tableCode: string, token: string): void {
    window.localStorage.setItem(keyFor(tableCode), token)
  },

  remove(tableCode: string): void {
    window.localStorage.removeItem(keyFor(tableCode))
  },
}
