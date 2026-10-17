import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../../../App'
import { getOpenSessionKey } from '../../../shared/api/openSessionKey'
import { server } from '../../../test/server'

const api = 'http://localhost:8080/api/v1'
const tableUrl = `${api}/public/tables/TABLE-7`
const table = {
  tableLabel: 'Mesa 7',
  restaurantId: '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68',
  restaurantOpen: true,
  hasActiveSession: false,
  canOpen: true,
}
const menu = {
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
          id: '9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7',
          name: 'Flat white',
          description: 'Doble espresso con leche.',
          priceMinor: 485000,
          available: true,
          station: 'BAR',
        },
      ],
    },
  ],
}

function renderTable() {
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

function publicTableHandler(response = table) {
  return http.get(tableUrl, () => HttpResponse.json(response))
}

function menuHandler() {
  return http.get(`${api}/menu`, () => HttpResponse.json(menu))
}

function currentSessionHandler() {
  return http.get(`${api}/sessions/current`, ({ request }) => {
    expect(request.headers.get('Authorization')).toBe('Bearer guest-token')
    return HttpResponse.json({
      session: {
        id: '5d3e8f10-2a4b-4c6d-9e8f-0a1b2c3d4e5f',
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
    })
  })
}

describe('table QR access', () => {
  it('opens a table with an idempotency key, retains the guest token, and loads its menu', async () => {
    Object.defineProperty(window.navigator, 'onLine', {
      configurable: true,
      value: false,
    })
    let openRequestCount = 0
    server.use(
      publicTableHandler(),
      menuHandler(),
      http.post(`${tableUrl}/sessions`, async ({ request }) => {
        openRequestCount += 1
        expect(request.headers.get('Idempotency-Key')).toMatch(
          /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
        )
        expect(await request.json()).toEqual({ displayName: 'Clara' })
        return HttpResponse.json(
          {
            session: {
              id: '5d3e8f10-2a4b-4c6d-9e8f-0a1b2c3d4e5f',
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
            guestToken: 'guest-token',
            joinCode: 'K7M2QX',
          },
          { status: 201 },
        )
      }),
      currentSessionHandler(),
    )

    renderTable()

    fireEvent.change(await screen.findByLabelText('Tu nombre'), {
      target: { value: 'Clara' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Ver menú y empezar' }))

    expect(await screen.findByRole('heading', { name: 'Flat white' })).toBeInTheDocument()
    expect(screen.getByText('K7M2QX')).toBeInTheDocument()
    expect(window.localStorage.getItem('somphie:guest-token:TABLE-7')).toBe(
      'guest-token',
    )
    expect(window.sessionStorage.getItem('somphie:open-session:TABLE-7')).toBeNull()
    expect(openRequestCount).toBe(1)
  })

  it('joins an existing table with the supplied code', async () => {
    server.use(
      publicTableHandler({ ...table, hasActiveSession: true, canOpen: false }),
      menuHandler(),
      http.post(`${api}/public/tables/TABLE-7/join`, async ({ request }) => {
        expect(await request.json()).toEqual({
          displayName: 'Sofi',
          joinCode: 'K7M2QX',
        })
        return HttpResponse.json(
          {
            session: {
              id: '5d3e8f10-2a4b-4c6d-9e8f-0a1b2c3d4e5f',
              status: 'OPEN',
              tableLabel: 'Mesa 7',
              openedAt: '2026-10-11T15:00:10Z',
              readOnly: false,
            },
            participant: {
              id: '0d1e2f3a-4b5c-4d6e-8f7a-8b9c0d1e2f3a',
              displayName: 'Sofi',
              role: 'MEMBER',
            },
            guestToken: 'guest-token',
          },
          { status: 201 },
        )
      }),
      currentSessionHandler(),
    )

    renderTable()
    fireEvent.change(await screen.findByLabelText('Tu nombre'), {
      target: { value: 'Sofi' },
    })
    fireEvent.change(screen.getByLabelText('Código de la mesa'), {
      target: { value: 'k7m2qx' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Sumarme a la mesa' }))

    expect(await screen.findByRole('heading', { name: 'Flat white' })).toBeInTheDocument()
    expect(window.localStorage.getItem('somphie:guest-token:TABLE-7')).toBe(
      'guest-token',
    )
  })

  it('reuses the opening key after a failed response so the retry remains the same attempt', async () => {
    const keys: string[] = []
    let attempt = 0
    server.use(
      publicTableHandler(),
      menuHandler(),
      http.post(`${tableUrl}/sessions`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        attempt += 1
        if (attempt === 1) {
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Temporary failure',
              status: 503,
              code: 'SERVICE_UNAVAILABLE',
            },
            { status: 503 },
          )
        }

        return HttpResponse.json(
          {
            session: {
              id: '5d3e8f10-2a4b-4c6d-9e8f-0a1b2c3d4e5f',
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
            guestToken: 'guest-token',
            joinCode: 'K7M2QX',
          },
          { status: 201 },
        )
      }),
      currentSessionHandler(),
    )

    renderTable()
    fireEvent.change(await screen.findByLabelText('Tu nombre'), {
      target: { value: 'Clara' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Ver menú y empezar' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Ver menú y empezar' }))

    expect(await screen.findByRole('heading', { name: 'Flat white' })).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
  })

  it('restores a valid guest session from the table-scoped token', async () => {
    window.localStorage.setItem('somphie:guest-token:TABLE-7', 'guest-token')
    server.use(publicTableHandler(), menuHandler(), currentSessionHandler())

    renderTable()

    expect(await screen.findByRole('heading', { name: 'Flat white' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Tu nombre')).not.toBeInTheDocument()
  })

  it('offers the join flow when opening loses a race with another guest', async () => {
    server.use(
      publicTableHandler(),
      http.post(`${tableUrl}/sessions`, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            code: 'SESSION_ALREADY_OPEN',
          },
          { status: 409 },
        ),
      ),
    )

    renderTable()
    fireEvent.change(await screen.findByLabelText('Tu nombre'), {
      target: { value: 'Clara' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Ver menú y empezar' }))

    expect(await screen.findByText('La mesa se abrió mientras tanto. Ingresá el código para sumarte.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Código de la mesa')).toBeInTheDocument())
  })

  it('keeps one key for an unchanged opening body and rotates it when the body changes', async () => {
    const first = await getOpenSessionKey('TABLE-7', { displayName: 'Clara' })
    const replayAttempt = await getOpenSessionKey('TABLE-7', { displayName: 'Clara' })
    const changedBody = await getOpenSessionKey('TABLE-7', { displayName: 'Sofi' })

    expect(replayAttempt).toBe(first)
    expect(changedBody).not.toBe(first)
  })
})
