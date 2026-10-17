import { en } from './en'
import { esAR, type TextKey } from './es-AR'

export type Locale = 'es-AR' | 'en'

export function text(key: TextKey, locale: Locale = 'es-AR'): string {
  return locale === 'en' ? en[key] : esAR[key]
}
