import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'
import { useCartStore } from '../features/client/cart/cartStore'

beforeAll(() => server.listen())
afterEach(() => {
  cleanup()
  server.resetHandlers()
  window.localStorage.clear()
  window.sessionStorage.clear()
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    value: true,
  })
  useCartStore.setState({ carts: {} })
})
afterAll(() => server.close())
