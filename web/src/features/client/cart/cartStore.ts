import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { Product } from '../../../shared/api/types'
import { cartLineId, type CartLine } from './cartModel'

interface CartState {
  carts: Record<string, CartLine[]>
  addProduct: (sessionId: string, product: Product, optionIds: string[]) => void
  removeLine: (sessionId: string, lineId: string) => void
  setQuantity: (sessionId: string, lineId: string, quantity: number) => void
  clearCart: (sessionId: string) => void
}

/**
 * Guarda carritos separados por sesión. El estado local sobrevive a recargas,
 * pero cada consulta y envío sigue usando el token y la sesión del backend.
 */
export const useCartStore = create<CartState>()(
  persist(
    (set) => ({
      carts: {},
      addProduct: (sessionId, product, optionIds) =>
        set((state) => {
          const current = state.carts[sessionId] ?? []
          const id = cartLineId(product.id, optionIds)
          const existing = current.find((line) => line.id === id)

          if (existing) {
            if (existing.quantity >= 20) {
              return state
            }
            return {
              carts: {
                ...state.carts,
                [sessionId]: current.map((line) =>
                  line.id === id
                    ? { ...line, product, quantity: line.quantity + 1 }
                    : line,
                ),
              },
            }
          }

          return {
            carts: {
              ...state.carts,
              [sessionId]: [
                ...current,
                { id, product, optionIds: [...optionIds].sort(), quantity: 1 },
              ],
            },
          }
        }),
      removeLine: (sessionId, lineId) =>
        set((state) => ({
          carts: {
            ...state.carts,
            [sessionId]: (state.carts[sessionId] ?? []).filter(
              (line) => line.id !== lineId,
            ),
          },
        })),
      setQuantity: (sessionId, lineId, quantity) =>
        set((state) => ({
          carts: {
            ...state.carts,
            [sessionId]: (state.carts[sessionId] ?? []).map((line) =>
              line.id === lineId
                ? { ...line, quantity: Math.max(1, Math.min(20, quantity)) }
                : line,
            ),
          },
        })),
      clearCart: (sessionId) =>
        set((state) => {
          const carts = { ...state.carts }
          delete carts[sessionId]
          return { carts }
        }),
    }),
    {
      name: 'somphie:customer-carts',
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ carts: state.carts }),
    },
  ),
)
