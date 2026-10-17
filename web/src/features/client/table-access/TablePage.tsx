import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  createOrder,
  getCurrentSession,
  getMenu,
  getPublicTable,
  joinSession,
  openSession,
  type ApiError as ApiErrorType,
} from '../../../shared/api/client'
import { clearIdempotencyKey, getIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { guestTokenStorage } from '../../../shared/api/guestTokenStorage'
import { clearOpenSessionKey, getOpenSessionKey } from '../../../shared/api/openSessionKey'
import type { CreateOrderRequest, CurrentSession, PublicTable, SessionAccessResponse } from '../../../shared/api/types'
import { text } from '../../../shared/i18n'
import type { TextKey } from '../../../shared/i18n/es-AR'
import { errorMessage } from '../../../shared/i18n/errors'
import { CartPanel, type PriceReview } from '../cart/CartPanel'
import { useCartStore } from '../cart/cartStore'
import { createOrderDraft, estimatedCartTotalMinor, type CartLine } from '../cart/cartModel'
import { MenuCatalog } from '../menu/MenuCatalog'
import { OrdersPanel } from '../orders/OrdersPanel'
import {
  clearPendingOrderAttempt,
  readPendingOrderAttempt,
  savePendingOrderAttempt,
  type PendingOrderAttempt,
} from '../orders/orderAttempt'

interface TablePageProps {
  tableCode: string
}

type CustomerView = 'menu' | 'cart' | 'orders'

// Un selector de Zustand debe devolver la misma referencia mientras el carrito siga vacío.
const EMPTY_CART: CartLine[] = []

const issueText: Record<NonNullable<ReturnType<typeof createOrderDraft>['issue']>, TextKey> = {
  empty: 'emptyCartDescription',
  'too-many-lines': 'cartLineLimit',
  'product-unavailable': 'productUnavailable',
  'option-unavailable': 'optionUnavailable',
  'invalid-options': 'optionRequired',
  quantity: 'quantityLimit',
}

function isInvalidGuestToken(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    ['TOKEN_EXPIRED', 'TOKEN_REVOKED', 'UNAUTHENTICATED'].includes(error.code)
  )
}

function LoadingState({ message }: { message: string }) {
  return (
    <p className="loading-line" role="status">
      {message}
    </p>
  )
}

function ErrorNotice({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => void
}) {
  return (
    <div aria-live="polite" className="notice notice--error" role="alert">
      <p>{message}</p>
      <button className="button button--secondary" onClick={onRetry} type="button">
        {text('retry')}
      </button>
    </div>
  )
}

function AccessForm({
  table,
  joining,
  displayName,
  joinCode,
  submitting,
  submitError,
  onDisplayNameChange,
  onJoinCodeChange,
  onSubmit,
}: {
  table: PublicTable
  joining: boolean
  displayName: string
  joinCode: string
  submitting: boolean
  submitError: string | null
  onDisplayNameChange: (value: string) => void
  onJoinCodeChange: (value: string) => void
  onSubmit: (event: React.FormEvent<HTMLFormElement>) => void
}) {
  if (!joining && !table.canOpen) {
    return (
      <section aria-labelledby="access-title" className="panel">
        <h2 id="access-title">{text('openHeading')}</h2>
        <p>{text('tableClosed')}</p>
      </section>
    )
  }

  return (
    <section aria-labelledby="access-title" className="panel">
      <h2 id="access-title">{text(joining ? 'joinHeading' : 'openHeading')}</h2>
      <p>{text(joining ? 'joinDescription' : 'openDescription')}</p>
      {submitError && (
        <p className="notice notice--error" role="alert">
          {submitError}
        </p>
      )}
      <form className="form" onSubmit={onSubmit}>
        <label className="field">
          {text('displayName')}
          <input
            autoComplete="name"
            maxLength={30}
            name="displayName"
            onChange={(event) => onDisplayNameChange(event.currentTarget.value)}
            required
            value={displayName}
          />
        </label>
        {joining && (
          <label className="field">
            {text('joinCode')}
            <input
              autoCapitalize="characters"
              autoComplete="one-time-code"
              maxLength={6}
              minLength={6}
              name="joinCode"
              onChange={(event) => onJoinCodeChange(event.currentTarget.value.toUpperCase())}
              required
              value={joinCode}
            />
          </label>
        )}
        <button className="button" disabled={submitting} type="submit">
          {submitting
            ? text('submitting')
            : text(joining ? 'joinSession' : 'openSession')}
        </button>
      </form>
    </section>
  )
}

function readServerCurrentTotal(error: ApiErrorType): number | null {
  const currentTotal = error.extra.currentTotalMinor
  return typeof currentTotal === 'number' && Number.isSafeInteger(currentTotal)
    ? currentTotal
    : null
}

function itemsFingerprint(body: CreateOrderRequest): string {
  return JSON.stringify(body.items)
}

function readStoredOrderAttempt(sessionId: string): {
  attempt: PendingOrderAttempt | null
  invalid: boolean
} {
  try {
    return { attempt: readPendingOrderAttempt(sessionId), invalid: false }
  } catch {
    return { attempt: null, invalid: true }
  }
}

function storedOrderAttempt(sessionId: string): PendingOrderAttempt | null {
  return sessionId ? readStoredOrderAttempt(sessionId).attempt : null
}

export function TablePage({ tableCode }: TablePageProps) {
  const queryClient = useQueryClient()
  const [guestToken, setGuestToken] = useState(() => guestTokenStorage.get(tableCode))
  const [displayName, setDisplayName] = useState('')
  const [joinCode, setJoinCode] = useState('')
  const [forceJoin, setForceJoin] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [activeView, setActiveView] = useState<CustomerView>('menu')
  const [priceReview, setPriceReview] = useState<PriceReview | null>(null)
  const [orderSuccess, setOrderSuccess] = useState(false)
  const [draftIssue, setDraftIssue] = useState<TextKey | null>(null)

  const tableQuery = useQuery({
    queryKey: ['public-table', tableCode],
    queryFn: () => getPublicTable(tableCode),
  })
  const menuQuery = useQuery({
    enabled: tableQuery.data !== undefined,
    queryKey: ['menu', tableQuery.data?.restaurantId],
    queryFn: () => getMenu(tableQuery.data?.restaurantId),
  })
  const sessionQuery = useQuery({
    enabled: guestToken !== null,
    queryKey: ['current-session', tableCode],
    queryFn: () => getCurrentSession(guestToken!),
    retry: false,
  })

  useEffect(() => {
    if (guestToken && isInvalidGuestToken(sessionQuery.error)) {
      guestTokenStorage.remove(tableCode)
    }
  }, [guestToken, sessionQuery.error, tableCode])

  const session: CurrentSession | undefined = sessionQuery.data
  const sessionId = session?.session.id ?? ''
  const cartLines = useCartStore((state) => state.carts[sessionId] ?? EMPTY_CART)
  const addProduct = useCartStore((state) => state.addProduct)
  const removeLine = useCartStore((state) => state.removeLine)
  const setQuantity = useCartStore((state) => state.setQuantity)
  const clearCart = useCartStore((state) => state.clearCart)
  const storedAttempt = sessionId ? readStoredOrderAttempt(sessionId) : { attempt: null, invalid: false }
  const pendingAttempt = storedAttempt.attempt

  async function handleAccessSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!tableQuery.data) {
      return
    }

    setSubmitting(true)
    setSubmitError(null)
    const joining = tableQuery.data.hasActiveSession || forceJoin

    try {
      let access: SessionAccessResponse
      if (joining) {
        access = await joinSession(tableCode, { displayName, joinCode })
      } else {
        const requestBody = { displayName }
        const idempotencyKey = await getOpenSessionKey(tableCode, requestBody)
        access = await openSession(tableCode, requestBody, idempotencyKey)
        guestTokenStorage.set(tableCode, access.guestToken)
        clearOpenSessionKey(tableCode)
      }

      if (joining) {
        guestTokenStorage.set(tableCode, access.guestToken)
      }
      setGuestToken(access.guestToken)
      setForceJoin(false)
      setJoinCode('')
      await queryClient.invalidateQueries({ queryKey: ['current-session', tableCode] })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SESSION_ALREADY_OPEN') {
        clearOpenSessionKey(tableCode)
        setForceJoin(true)
        await queryClient.invalidateQueries({ queryKey: ['public-table', tableCode] })
      }
      setSubmitError(errorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  async function sendOrder(acknowledgedTotalMinor?: number) {
    if (!session || !guestToken || !menuQuery.data || session.session.readOnly) {
      return
    }

    setSubmitting(true)
    setSubmitError(null)
    setDraftIssue(null)

    try {
      const pending = storedOrderAttempt(session.session.id)
      let attempt: PendingOrderAttempt
      if (pending) {
        // Ante resultado ambiguo se repite el cuerpo guardado; nunca se fabrica una segunda orden.
        attempt = pending
      } else {
        const total =
          acknowledgedTotalMinor ??
          estimatedCartTotalMinor(cartLines, menuQuery.data)
        const draft = createOrderDraft(cartLines, menuQuery.data, total)
        if (!draft.body || draft.issue) {
          setDraftIssue(issueText[draft.issue ?? 'empty'])
          return
        }
        const scope = `create-order:${session.session.id}`
        const idempotencyKey = await getIdempotencyKey(scope, draft.body)
        attempt = { body: draft.body, idempotencyKey }
        savePendingOrderAttempt(session.session.id, attempt)
        setPriceReview(null)
      }

      try {
        await createOrder(guestToken, attempt.body, attempt.idempotencyKey)
      } catch (error) {
        if (error instanceof ApiError && error.code === 'PRICE_CHANGED') {
          const serverTotal = readServerCurrentTotal(error)
          clearPendingOrderAttempt(session.session.id)
          clearIdempotencyKey(`create-order:${session.session.id}`)
          if (serverTotal !== null) {
            setPriceReview({
              orderItemsFingerprint: itemsFingerprint(attempt.body),
              serverTotalMinor: serverTotal,
            })
            void queryClient.invalidateQueries({
              queryKey: ['menu', tableQuery.data?.restaurantId],
            })
          } else {
            setSubmitError(text('priceChangedMissingTotal'))
          }
        } else if (
          error instanceof ApiError &&
          error.status >= 400 &&
          error.status < 500
        ) {
          clearPendingOrderAttempt(session.session.id)
          clearIdempotencyKey(`create-order:${session.session.id}`)
          setSubmitError(errorMessage(error))
        } else {
          // Red/timeout o respuesta ilegible: conservamos clave, cuerpo y carrito para reintentar igual.
          setSubmitError(errorMessage(error))
        }
        return
      }

      clearPendingOrderAttempt(session.session.id)
      clearIdempotencyKey(`create-order:${session.session.id}`)
      clearCart(session.session.id)
      setPriceReview(null)
      setDraftIssue(null)
      setSubmitError(null)
      setOrderSuccess(true)
      setActiveView('orders')
      await queryClient.invalidateQueries({
        queryKey: ['session-orders', session.session.id],
      })
    } catch (error) {
      setSubmitError(errorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  if (tableQuery.isPending) {
    return (
      <main className="page">
        <LoadingState message={text('loadingTable')} />
      </main>
    )
  }

  if (tableQuery.isError || !tableQuery.data) {
    return (
      <main className="page">
        <ErrorNotice
          message={errorMessage(tableQuery.error)}
          onRetry={() => void tableQuery.refetch()}
        />
      </main>
    )
  }

  const table = tableQuery.data
  const invalidToken = isInvalidGuestToken(sessionQuery.error)

  if (guestToken && sessionQuery.isPending) {
    return (
      <main className="page">
        <LoadingState message={text('loadingSession')} />
      </main>
    )
  }

  if (guestToken && sessionQuery.isError && !invalidToken) {
    return (
      <main className="page">
        <ErrorNotice
          message={errorMessage(sessionQuery.error)}
          onRetry={() => void sessionQuery.refetch()}
        />
      </main>
    )
  }

  const joining = table.hasActiveSession || forceJoin
  const hasSession = session !== undefined
  const menu = menuQuery.data
  // navigator.onLine no confirma que el backend responda; cada acción intenta la API y muestra su error real.
  const canWrite =
    session?.session.status === 'OPEN' &&
    !session.session.readOnly &&
    menu?.restaurantOpen === true
  const cartLineCount = cartLines.length

  return (
    <main className="page">
      <header className="page__heading">
        <p className="eyebrow">{text('currentSession')}</p>
        <h1>{table.tableLabel}</h1>
        <p className="page__subtitle">
          {session ? session.participant.displayName : table.tableLabel}
        </p>
      </header>

      {!hasSession && (
        <AccessForm
          displayName={displayName}
          joinCode={joinCode}
          joining={joining}
          onDisplayNameChange={setDisplayName}
          onJoinCodeChange={setJoinCode}
          onSubmit={(event) => void handleAccessSubmit(event)}
          submitError={submitError}
          submitting={submitting}
          table={table}
        />
      )}

      {session?.joinCode && session.session.status === 'OPEN' && (
        <section aria-label={text('hostJoinCode')} className="panel">
          <p>{text('hostJoinCode')}</p>
          <span className="join-code">{session.joinCode}</span>
        </section>
      )}

      {session?.session.readOnly && (
        <p className="notice" role="status">
          {text('readOnlySession')}
        </p>
      )}

      {hasSession && (
        <nav aria-label={text('customerNavigation')} className="customer-tabs">
          {(['menu', 'cart', 'orders'] as const).map((view) => (
            <button
              aria-current={activeView === view ? 'page' : undefined}
              disabled={submitting && view !== activeView}
              key={view}
              onClick={() => setActiveView(view)}
              type="button"
            >
              {text(
                view === 'menu'
                  ? 'tabMenu'
                  : view === 'cart'
                    ? 'tabCart'
                    : 'tabOrders',
              )}
              {view === 'cart' && cartLineCount > 0 ? ` (${cartLineCount})` : ''}
            </button>
          ))}
        </nav>
      )}

      {hasSession && menuQuery.isPending && (
        <LoadingState message={text('loadingMenu')} />
      )}
      {hasSession && menuQuery.isError && !menu && (
        <ErrorNotice
          message={text('menuRetry')}
          onRetry={() => void menuQuery.refetch()}
        />
      )}
      {hasSession && menuQuery.isError && menu && (
        <p className="notice" role="status">
          {text('menuStale')}
        </p>
      )}

      {hasSession && menu && activeView === 'menu' && (
        <MenuCatalog
          canAdd={canWrite === true && !pendingAttempt && !storedAttempt.invalid && cartLineCount < 30}
          menu={menu}
          onAdd={(product, optionIds) => {
            if (session && canWrite && !pendingAttempt && cartLineCount < 30) {
              addProduct(session.session.id, product, optionIds)
            }
          }}
        />
      )}

      {hasSession && menu && activeView === 'cart' && (
        <>
          {storedAttempt.invalid && (
            <p className="notice notice--error" role="alert">
              {text('pendingOrderStorageError')}
            </p>
          )}
          {submitError && (
            <p className="notice notice--error" role="alert">
              {submitError}
            </p>
          )}
          <CartPanel
            canSubmit={canWrite === true && !storedAttempt.invalid}
            issue={draftIssue}
            lines={cartLines}
            menu={menu}
            onConfirmServerTotal={() => {
              if (priceReview) {
                void sendOrder(priceReview.serverTotalMinor)
              }
            }}
            onQuantityChange={(lineId, quantity) => {
              if (session && !pendingAttempt && !submitting) {
                setQuantity(session.session.id, lineId, quantity)
                setPriceReview(null)
              }
            }}
            onRemove={(lineId) => {
              if (session && !pendingAttempt && !submitting) {
                removeLine(session.session.id, lineId)
                setPriceReview(null)
              }
            }}
            onSubmit={() => void sendOrder()}
            pendingAttempt={pendingAttempt !== null}
            priceReview={priceReview}
            submitting={submitting}
          />
        </>
      )}

      {hasSession && activeView === 'orders' && session && guestToken && (
        <>
          {orderSuccess && (
            <p className="notice" role="status">
              {text('orderSent')}
            </p>
          )}
          <OrdersPanel
            currency={menu?.currency ?? 'ARS'}
            guestToken={guestToken}
            session={session}
          />
        </>
      )}
    </main>
  )
}
