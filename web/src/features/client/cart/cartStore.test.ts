import { describe, expect, it } from 'vitest'
import type { Product } from '../../../shared/api/types'
import { useCartStore } from './cartStore'
import { cartLineId } from './cartModel'

const product = {
  id: '9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7',
  name: 'Flat white',
  priceMinor: 485000,
  available: true,
  station: 'BAR',
} satisfies Product

const lineId = cartLineId(product.id, [])
const storageName = 'somphie:customer-carts'

describe('cart store', () => {
  it('keeps quantities within the contract limits and isolates each session', () => {
    const cart = useCartStore.getState()
    cart.addProduct('session-a', product, [])
    cart.addProduct('session-b', product, [])
    cart.setQuantity('session-a', lineId, 25)

    expect(useCartStore.getState().carts['session-a']?.[0]?.quantity).toBe(20)
    expect(useCartStore.getState().carts['session-b']?.[0]?.quantity).toBe(1)

    useCartStore.getState().addProduct('session-a', product, [])
    expect(useCartStore.getState().carts['session-a']?.[0]?.quantity).toBe(20)
  })

  it('rehydrates the session cart from browser storage after a page reload', async () => {
    useCartStore.getState().addProduct('session-a', product, [])
    const persistedCart = window.localStorage.getItem(storageName)

    if (persistedCart === null) {
      throw new Error('El carrito no se guardó en localStorage.')
    }

    // Reemplaza el estado en memoria y vuelve a leer lo que sobrevivió en localStorage.
    useCartStore.setState({ carts: {} })
    window.localStorage.setItem(storageName, persistedCart)
    await useCartStore.persist.rehydrate()

    expect(useCartStore.getState().carts['session-a']).toHaveLength(1)
    expect(useCartStore.getState().carts['session-a']?.[0]?.product.id).toBe(
      product.id,
    )
  })
})
