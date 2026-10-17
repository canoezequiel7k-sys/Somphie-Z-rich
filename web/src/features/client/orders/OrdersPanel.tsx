import { useQuery } from '@tanstack/react-query'
import { getSessionOrders } from '../../../shared/api/client'
import type { CurrentSession, Order } from '../../../shared/api/types'
import { text } from '../../../shared/i18n'
import type { TextKey } from '../../../shared/i18n/es-AR'
import { errorMessage } from '../../../shared/i18n/errors'
import { formatMoneyMinor } from '../../../shared/utils/formatMoneyMinor'

const orderStatusText: Record<string, TextKey> = {
  PENDING_REVIEW: 'orderPendingReview',
  ACCEPTED: 'orderAccepted',
  REJECTED: 'orderRejected',
  CANCELLED: 'orderCancelled',
  EXPIRED: 'orderExpired',
  IN_PREPARATION: 'orderInPreparation',
  READY: 'orderReady',
  DELIVERED: 'orderDelivered',
}

const itemStatusText: Record<string, TextKey> = {
  PENDING: 'itemPending',
  QUEUED: 'itemQueued',
  IN_PREPARATION: 'itemInPreparation',
  READY: 'itemReady',
  DELIVERED: 'itemDelivered',
  CANCELLED: 'itemCancelled',
}

const rejectionText: Record<string, TextKey> = {
  OUT_OF_STOCK: 'rejectionOutOfStock',
  KITCHEN_CLOSED: 'rejectionKitchenClosed',
  ITEM_UNAVAILABLE: 'rejectionItemUnavailable',
  OTHER: 'rejectionOther',
}

function statusLabel(status: string, labels: Record<string, TextKey>, fallback: TextKey) {
  return text(labels[status] ?? fallback)
}

function OrderCard({ order, currency }: { order: Order; currency: string }) {
  return (
    <article className="order-card">
      <header className="order-card__heading">
        <div>
          <h3>
            {text('orderCode')} {order.displayCode}
          </h3>
          <time dateTime={order.createdAt}>
            {new Intl.DateTimeFormat('es-AR', {
              dateStyle: 'medium',
              timeStyle: 'short',
            }).format(new Date(order.createdAt))}
          </time>
        </div>
        <span className="status-badge">
          {statusLabel(order.status, orderStatusText, 'orderStatusUnknown')}
        </span>
      </header>
      <ul className="order-items">
        {order.items.map((item) => (
          <li className="order-item" key={item.id}>
            <div>
              <span>
                {item.quantity} × {item.productName}
              </span>
              {item.options && item.options.length > 0 && (
                <ul className="cart-line__options">
                  {item.options.map((option) => (
                    <li key={option.optionId}>{option.optionName}</li>
                  ))}
                </ul>
              )}
            </div>
            <span className="status-badge status-badge--subtle">
              {statusLabel(item.status, itemStatusText, 'itemStatusUnknown')}
            </span>
          </li>
        ))}
      </ul>
      {order.rejection && (
        <p className="notice">
          <strong>{text('rejection')}:</strong>{' '}
          {order.rejection.reasonText ??
            text(rejectionText[order.rejection.reasonCode] ?? 'rejectionOther')}
        </p>
      )}
      {order.totalMinor !== undefined && (
        <p className="order-card__total">
          {formatMoneyMinor(order.totalMinor, currency)}
        </p>
      )}
    </article>
  )
}

export function OrdersPanel({
  session,
  guestToken,
  currency,
}: {
  session: CurrentSession
  guestToken: string
  currency: string
}) {
  const ordersQuery = useQuery({
    queryKey: ['session-orders', session.session.id],
    queryFn: () => getSessionOrders(guestToken),
    // En las fases 3–5 el contrato prevé REST con polling; SSE queda para la fase posterior.
    refetchInterval: session.session.status === 'OPEN' ? 8_000 : false,
    refetchOnWindowFocus: true,
  })

  if (ordersQuery.isPending) {
    return (
      <p className="loading-line" role="status">
        {text('loadingOrders')}
      </p>
    )
  }

  if (ordersQuery.isError && !ordersQuery.data) {
    return (
      <div className="notice notice--error" role="alert">
        <p>{errorMessage(ordersQuery.error)}</p>
        <button
          className="button button--secondary"
          onClick={() => void ordersQuery.refetch()}
          type="button"
        >
          {text('retry')}
        </button>
      </div>
    )
  }

  const orders = ordersQuery.data?.items ?? []
  return (
    <section aria-labelledby="orders-title" className="orders-panel">
      <div className="menu-section__heading">
        <h2 id="orders-title">{text('tabOrders')}</h2>
        <button
          className="text-button"
          disabled={ordersQuery.isFetching}
          onClick={() => void ordersQuery.refetch()}
          type="button"
        >
          {text('refreshOrders')}
        </button>
      </div>
      {ordersQuery.isError && (
        <p className="notice" role="status">
          {text('ordersRetry')}
        </p>
      )}
      {orders.length === 0 ? (
        <div className="panel">
          <h3>{text('emptyOrdersTitle')}</h3>
          <p>{text('emptyOrdersDescription')}</p>
        </div>
      ) : (
        <div className="orders-list">
          {orders.map((order) => (
            <OrderCard currency={currency} key={order.id} order={order} />
          ))}
        </div>
      )}
    </section>
  )
}
