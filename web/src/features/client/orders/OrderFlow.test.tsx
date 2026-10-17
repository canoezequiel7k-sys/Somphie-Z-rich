import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../../../App'
import { server } from '../../../test/server'
import { useCartStore } from '../cart/cartStore'

const api = 'http://localhost:8080/api/v1'
const sessionId = '5d3e8f10-2a4b-4c6d-9e8f-0a1b2c3d4e5f'
const productId = '9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7'
const smallId = 'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70'
const largeId = 'e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081'
const oatId = 'f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192'
const sessionUrl = `${api}/sessions/current`
const tableUrl = `${api}/public/tables/TABLE-7`

const table = {
  tableLabel: 'Mesa 7',
  restaurantId: '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68',
  restaurantOpen: true,
  hasActiveSession: true,
  canOpen: false,
}

function menu(basePrice = 485000) {
  return {
    catalogVersion: 42,
    locale: 'es-AR',
    currency: 'ARS',
    restaurantOpen: true,
    categories: [
      {
        id: '5c0f2a7e-3d41-4b8a-9a52-7e1d6f0a2b11',
        name: 'Café',
        sortOrder: 1,
        products: [
          {
            id: productId,
            name: 'Flat white',
            description: 'Doble espresso con leche.',
            priceMinor: basePrice,
            available: true,
            station: 'BAR',
            optionGroups: [
              {
                id: '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6',
                name: 'Tamaño',
                selectionType: 'SINGLE',
                required: true,
                minSelections: 1,
                maxSelections: 1,
                options: [
                  {
                    id: smallId,
                    name: 'Chico',
                    priceDeltaMinor: 0,
                    available: true,
                    isDefault: true,
                  },
                  {
                    id: largeId,
                    name: 'Grande',
                    priceDeltaMinor: 80000,
                    available: true,
                    isDefault: false,
                  },
                ],
              },
              {
                id: '4a0f2e3d-5c6b-4798-8b1c-d2e3f4a5b6c7',
                name: 'Leche',
                selectionType: 'MULTIPLE',
                required: false,
                minSelections: 0,
                maxSelections: 2,
                options: [
                  {
                    id: oatId,
                    name: 'Avena',
                    priceDeltaMinor: 60000,
                    available: true,
                    isDefault: false,
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  }
}

const currentSession = {
  session: {
    id: sessionId,
    status: 'OPEN',
    tableLabel: 'Mesa 7',
    openedAt: '2026-10-11T15:00:10Z',
    readOnly: false,
  },
  participant: {
    id: 'ab12cd34-ef56-4789-8abc-def012345678',
    displayName: 'Clara',
    role: 'HOST',
  },
  joinCode: 'K7M2QX',
}

function addBaseHandlers(
  menuValue = menu(),
  getOrders: () => object = () => ({ items: [], nextCursor: null }),
) {
  server.use(
    http.get(tableUrl, () => HttpResponse.json(table)),
    http.get(sessionUrl, ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer guest-token')
      return HttpResponse.json(currentSession)
    }),
    http.get(`${api}/menu`, () => HttpResponse.json(menuValue)),
    http.get(`${api}/sessions/current/orders`, ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer guest-token')
      return HttpResponse.json(getOrders())
    }),
  )
}

function renderOrderFlow() {
  window.localStorage.setItem('somphie:guest-token:TABLE-7', 'guest-token')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/t/TABLE-7']}>
        <AppRoutes />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function addLargeOatProduct() {
  await screen.findByRole('heading', { name: 'Flat white' })
  fireEvent.click(screen.getByRole('radio', { name: /Grande/ }))
  fireEvent.click(screen.getByRole('checkbox', { name: /Avena/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
  fireEvent.click(screen.getByRole('button', { name: /Carrito/ }))
}

describe('customer order flow', () => {
  it('persists a session cart and sends selected options, quantity and estimated total', async () => {
    let createdOrder: Record<string, unknown> | null = null
    addBaseHandlers(menu(), () => ({
      items: createdOrder ? [createdOrder] : [],
      nextCursor: null,
    }))
    server.use(
      http.post(`${sessionUrl}/orders`, async ({ request }) => {
        expect(request.headers.get('Authorization')).toBe('Bearer guest-token')
        expect(request.headers.get('Idempotency-Key')).toMatch(
          /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
        )
        const body = await request.json()
        expect(body).toEqual({
          items: [{ productId, quantity: 1, optionIds: [largeId, oatId] }],
          acknowledgedTotalMinor: 625000,
        })
        createdOrder = {
          id: 'e91a7b3c-5d2f-4e8a-9b1c-3d5f7a9b1c2e',
          displayCode: '#042',
          status: 'PENDING_REVIEW',
          version: 1,
          createdAt: '2026-10-17T12:00:00Z',
          items: [
            {
              id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
              productId,
              productName: 'Flat white',
              quantity: 1,
              station: 'BAR',
              status: 'PENDING',
              options: [
                {
                  groupId: '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6',
                  optionId: largeId,
                  groupName: 'Tamaño',
                  optionName: 'Grande',
                },
              ],
            },
          ],
          totalMinor: 625000,
        }
        return HttpResponse.json(createdOrder, { status: 201 })
      }),
    )

    renderOrderFlow()
    await addLargeOatProduct()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar y enviar pedido' }))

    expect(await screen.findByText('El pedido se recibió.')).toBeInTheDocument()
    expect(await screen.findByText('Esperando confirmación')).toBeInTheDocument()
    expect(screen.getByText('Pendiente')).toBeInTheDocument()
    expect(useCartStore.getState().carts[sessionId]).toBeUndefined()
  })

  it('requires confirmation of the server total and sends a new key after PRICE_CHANGED', async () => {
    const requestKeys: string[] = []
    let requestCount = 0
    addBaseHandlers(menu())
    server.use(
      http.post(`${sessionUrl}/orders`, async ({ request }) => {
        requestCount += 1
        requestKeys.push(request.headers.get('Idempotency-Key') ?? '')
        const body = await request.json()
        if (requestCount === 1) {
          expect(body).toMatchObject({ acknowledgedTotalMinor: 625000 })
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Price changed',
              status: 409,
              code: 'PRICE_CHANGED',
              extra: { currentTotalMinor: 650000 },
            },
            { status: 409 },
          )
        }

        expect(body).toMatchObject({ acknowledgedTotalMinor: 650000 })
        return HttpResponse.json(
          {
            id: 'e91a7b3c-5d2f-4e8a-9b1c-3d5f7a9b1c2e',
            displayCode: '#042',
            status: 'PENDING_REVIEW',
            version: 1,
            createdAt: '2026-10-17T12:00:00Z',
            items: [],
            totalMinor: 650000,
          },
          { status: 201 },
        )
      }),
    )

    renderOrderFlow()
    await addLargeOatProduct()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar y enviar pedido' }))

    expect(await screen.findByText('El total cambió')).toBeInTheDocument()
    expect(screen.getByText(/6\.500,00/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Confirmar este total y enviar' }),
    ).toBeInTheDocument()
    expect(requestCount).toBe(1)

    fireEvent.click(
      screen.getByRole('button', { name: 'Confirmar este total y enviar' }),
    )
    await screen.findByText('El pedido se recibió.')
    expect(requestCount).toBe(2)
    expect(requestKeys[0]).not.toBe(requestKeys[1])
  })

  it('keeps the exact body and key after an ambiguous server error and retries safely', async () => {
    const requestKeys: string[] = []
    let requestCount = 0
    addBaseHandlers(menu())
    server.use(
      http.post(`${sessionUrl}/orders`, ({ request }) => {
        requestCount += 1
        requestKeys.push(request.headers.get('Idempotency-Key') ?? '')
        if (requestCount === 1) {
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Temporary error',
              status: 503,
              code: 'SERVICE_UNAVAILABLE',
            },
            { status: 503 },
          )
        }

        return HttpResponse.json(
          {
            id: 'e91a7b3c-5d2f-4e8a-9b1c-3d5f7a9b1c2e',
            displayCode: '#042',
            status: 'PENDING_REVIEW',
            version: 1,
            createdAt: '2026-10-17T12:00:00Z',
            items: [],
            totalMinor: 485000,
          },
          { status: 201 },
        )
      }),
    )

    const rendered = renderOrderFlow()
    await screen.findByRole('heading', { name: 'Flat white' })
    fireEvent.click(screen.getByRole('button', { name: 'Agregar' }))
    fireEvent.click(screen.getByRole('button', { name: /Carrito/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar y enviar pedido' }))
    await screen.findByText('No pudimos completar la solicitud. Probá de nuevo.')

    expect(window.sessionStorage.getItem(`somphie:pending-order:${sessionId}`)).not.toBeNull()
    expect(
      screen.getByRole('button', { name: 'Reintentar con el mismo pedido' }),
    ).toBeEnabled()
    expect(
      screen.getByRole('button', { name: 'Subir cantidad' }),
    ).toBeDisabled()

    rendered.unmount()
    const rerendered = renderOrderFlow()
    fireEvent.click(await screen.findByRole('button', { name: /Carrito/ }))
    fireEvent.click(
      screen.getByRole('button', { name: 'Reintentar con el mismo pedido' }),
    )

    expect(await screen.findByText('El pedido se recibió.')).toBeInTheDocument()
    expect(requestCount).toBe(2)
    expect(requestKeys[1]).toBe(requestKeys[0])
    expect(window.sessionStorage.getItem(`somphie:pending-order:${sessionId}`)).toBeNull()
    rerendered.unmount()
  })
})
