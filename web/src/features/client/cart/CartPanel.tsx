import type { MenuResponse } from '../../../shared/api/types'
import { text } from '../../../shared/i18n'
import { formatMoneyMinor } from '../../../shared/utils/formatMoneyMinor'
import type { TextKey } from '../../../shared/i18n/es-AR'
import {
  currentProductForLine,
  estimatedCartTotalMinor,
  estimatedUnitPriceMinor,
  selectedOptions,
  validateOptionSelection,
  type CartLine,
} from './cartModel'

export interface PriceReview {
  orderItemsFingerprint: string
  serverTotalMinor: number
}

interface CartPanelProps {
  lines: CartLine[]
  menu: MenuResponse
  canSubmit: boolean
  submitting: boolean
  pendingAttempt: boolean
  issue: TextKey | null
  priceReview: PriceReview | null
  onQuantityChange: (lineId: string, quantity: number) => void
  onRemove: (lineId: string) => void
  onSubmit: () => void
  onConfirmServerTotal: () => void
}

export function CartPanel({
  lines,
  menu,
  canSubmit,
  submitting,
  pendingAttempt,
  issue,
  priceReview,
  onQuantityChange,
  onRemove,
  onSubmit,
  onConfirmServerTotal,
}: CartPanelProps) {
  if (lines.length === 0) {
    return (
      <section aria-labelledby="empty-cart-title" className="panel">
        <h2 id="empty-cart-title">{text('emptyCartTitle')}</h2>
        <p>{text('emptyCartDescription')}</p>
      </section>
    )
  }

  const hasChangedLines =
    lines.length > 30 ||
    lines.some((line) => {
      const product = currentProductForLine(line, menu)
      return (
        line.quantity < 1 ||
        line.quantity > 20 ||
        !product ||
        !product.available ||
        !validateOptionSelection(product, line.optionIds).valid
      )
    })
  const total = estimatedCartTotalMinor(lines, menu)
  const currentFingerprint = JSON.stringify(
    lines.map((line) => ({
      productId: line.product.id,
      quantity: line.quantity,
      ...(line.optionIds.length > 0
        ? { optionIds: [...line.optionIds].sort() }
        : {}),
    })),
  )
  const serverTotalCanBeConfirmed =
    priceReview !== null &&
    priceReview.orderItemsFingerprint === currentFingerprint
  const controlsLocked = pendingAttempt || submitting

  return (
    <section aria-labelledby="cart-title" className="cart-panel">
      <h2 id="cart-title">{text('tabCart')}</h2>
      {lines.length > 30 && (
        <p className="notice notice--error" role="alert">
          {text('cartLineLimit')}
        </p>
      )}
      {lines.map((line) => {
        const product = currentProductForLine(line, menu)
        const displayProduct = product ?? line.product
        const selection = validateOptionSelection(displayProduct, line.optionIds)
        const unavailable =
          !product || !product.available || !selection.valid
        const options = selectedOptions(displayProduct, line.optionIds)
        const unitPrice = estimatedUnitPriceMinor(displayProduct, line.optionIds)

        return (
          <article className="cart-line" key={line.id}>
            <div className="cart-line__content">
              <div>
                <h3>{displayProduct.name}</h3>
                {options.length > 0 && (
                  <ul className="cart-line__options">
                    {options.map((option) => (
                      <li key={option.id}>{option.name}</li>
                    ))}
                  </ul>
                )}
                {!product && (
                  <p className="product-card__unavailable">{text('staleProduct')}</p>
                )}
                {product && !product.available && (
                  <p className="product-card__unavailable">{text('productUnavailable')}</p>
                )}
                {product && product.available && !selection.valid && (
                  <p className="product-card__unavailable">
                    {text('optionUnavailable')}
                  </p>
                )}
                <p className="cart-line__price">
                  {formatMoneyMinor(unitPrice, menu.currency)}
                </p>
              </div>
              <div aria-label={text('quantity')} className="quantity-control">
                <button
                  aria-label={text('decreaseQuantity')}
                  disabled={controlsLocked || line.quantity <= 1}
                  onClick={() => onQuantityChange(line.id, line.quantity - 1)}
                  type="button"
                >
                  −
                </button>
                <output aria-live="polite">{line.quantity}</output>
                <button
                  aria-label={text('increaseQuantity')}
                  disabled={controlsLocked || line.quantity >= 20}
                  onClick={() => onQuantityChange(line.id, line.quantity + 1)}
                  type="button"
                >
                  +
                </button>
              </div>
              {unavailable && (
                <button
                  className="text-button"
                  disabled={controlsLocked}
                  onClick={() => onRemove(line.id)}
                  type="button"
                >
                  {text('removeStaleLine')}
                </button>
              )}
              {!unavailable && (
                <button
                  className="text-button"
                  disabled={controlsLocked}
                  onClick={() => onRemove(line.id)}
                  type="button"
                >
                  {text('removeLine')}
                </button>
              )}
            </div>
          </article>
        )
      })}

      {issue && (
        <p className="notice notice--error" role="alert">
          {text(issue)}
        </p>
      )}

      <div className="cart-summary">
        <span>{text('estimatedSubtotal')}</span>
        <strong>{formatMoneyMinor(total, menu.currency)}</strong>
      </div>
      <p className="cart-disclaimer">{text('estimatedCartDisclaimer')}</p>

      {priceReview && serverTotalCanBeConfirmed && (
        <section aria-labelledby="price-review-title" className="price-review">
          <h3 id="price-review-title">{text('priceChangedTitle')}</h3>
          <p>{text('priceChangedDescription')}</p>
          <p className="cart-summary">
            <span>{text('serverCurrentTotal')}</span>
            <strong>{formatMoneyMinor(priceReview.serverTotalMinor, menu.currency)}</strong>
          </p>
          <button
            className="button"
            disabled={!canSubmit || submitting || hasChangedLines}
            onClick={onConfirmServerTotal}
            type="button"
          >
            {text('confirmCurrentTotal')}
          </button>
        </section>
      )}

      <button
        className="button cart-submit"
        disabled={
          !canSubmit ||
          submitting ||
          hasChangedLines ||
          lines.length > 30 ||
          serverTotalCanBeConfirmed
        }
        onClick={onSubmit}
        type="button"
      >
        {submitting
          ? text('sendingOrder')
          : pendingAttempt
            ? text('retryOrder')
            : text('confirmOrder')}
      </button>
    </section>
  )
}
