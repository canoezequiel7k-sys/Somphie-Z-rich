import { useState } from 'react'
import { productImageUrl } from '../../../shared/api/client'
import { text } from '../../../shared/i18n'
import { formatMoneyMinor } from '../../../shared/utils/formatMoneyMinor'
import type { MenuResponse, Product } from '../../../shared/api/types'
import {
  defaultOptionIds,
  estimatedUnitPriceMinor,
  validateOptionSelection,
} from '../cart/cartModel'

interface MenuCatalogProps {
  menu: MenuResponse
  canAdd: boolean
  onAdd: (product: Product, optionIds: string[]) => void
}

function ProductCard({
  product,
  currency,
  canAdd,
  onAdd,
}: {
  product: Product
  currency: string
  canAdd: boolean
  onAdd: (product: Product, optionIds: string[]) => void
}) {
  const [selectedOptionIds, setSelectedOptionIds] = useState(() =>
    defaultOptionIds(product),
  )
  const [added, setAdded] = useState(false)
  const [failedImageUrl, setFailedImageUrl] = useState<string | null>(null)
  const groups = product.optionGroups ?? []
  const selection = validateOptionSelection(product, selectedOptionIds)
  const unavailableRequiredGroup = groups.some(
    (group) =>
      group.required && !group.options.some((option) => option.available),
  )
  const orderable =
    product.available &&
    !unavailableRequiredGroup &&
    selection.valid

  function toggleOption(groupId: string, optionId: string, single: boolean) {
    setAdded(false)
    setSelectedOptionIds((current) => {
      if (single) {
        return [
          ...current.filter(
            (selectedId) =>
              !groups
                .find((group) => group.id === groupId)
                ?.options.some((option) => option.id === selectedId),
          ),
          optionId,
        ]
      }

      return current.includes(optionId)
        ? current.filter((selectedId) => selectedId !== optionId)
        : [...current, optionId]
    })
  }

  const unitPrice = estimatedUnitPriceMinor(product, selectedOptionIds)
  const imageUrl = product.image
    ? productImageUrl(product.image.path, product.image.version)
    : null

  return (
    <article className="product-card">
      {product.image && imageUrl && failedImageUrl !== imageUrl ? (
        <img
          alt={product.image.alt}
          className="product-card__image"
          height="88"
          loading="lazy"
          onError={() => setFailedImageUrl(imageUrl)}
          src={imageUrl}
          width="88"
        />
      ) : (
        <div aria-hidden="true" className="product-card__placeholder" />
      )}
      <div className="product-card__body">
        <h4 className="product-card__title">{product.name}</h4>
        {product.description && (
          <p className="product-card__description">{product.description}</p>
        )}
        <p className="product-card__price">
          {formatMoneyMinor(unitPrice, currency)}
        </p>
        {groups.length > 0 && (
          <div className="option-groups">
            <p className="option-groups__title">{text('optionsHeading')}</p>
            {groups.map((group) => (
              <fieldset className="option-group" key={group.id}>
                <legend>
                  {group.name}
                  {group.required ? ' *' : ''}
                </legend>
                {group.options.map((option) => {
                  const selected = selectedOptionIds.includes(option.id)
                  return (
                    <label className="option-choice" key={option.id}>
                      <input
                        checked={selected}
                        disabled={!option.available}
                        name={`option-${product.id}-${group.id}`}
                        onChange={() =>
                          toggleOption(
                            group.id,
                            option.id,
                            group.selectionType === 'SINGLE',
                          )
                        }
                        type={group.selectionType === 'SINGLE' ? 'radio' : 'checkbox'}
                      />
                      <span>{option.name}</span>
                      {option.priceDeltaMinor > 0 && (
                        <span className="option-choice__price">
                          +{formatMoneyMinor(option.priceDeltaMinor, currency)}
                        </span>
                      )}
                      {!option.available && (
                        <span className="product-card__unavailable">
                          {text('unavailable')}
                        </span>
                      )}
                    </label>
                  )
                })}
              </fieldset>
            ))}
            {!selection.valid && product.available && (
              <p className="product-card__unavailable" role="status">
                {selection.reason === 'unavailable'
                  ? text('optionUnavailable')
                  : text('optionRequired')}
              </p>
            )}
          </div>
        )}
        {!product.available && (
          <span className="product-card__unavailable">{text('unavailable')}</span>
        )}
        <button
          className="button button--secondary product-card__add"
          disabled={!canAdd || !orderable}
          onClick={() => {
            onAdd(product, selectedOptionIds)
            setAdded(true)
          }}
          type="button"
        >
          {added ? text('addedProduct') : text('addProduct')}
        </button>
      </div>
    </article>
  )
}

export function MenuCatalog({ menu, canAdd, onAdd }: MenuCatalogProps) {
  const productCount = menu.categories.reduce(
    (count, category) => count + category.products.length,
    0,
  )

  return (
    <section aria-labelledby="menu-title" className="menu-section">
      <div className="menu-section__heading">
        <h2 id="menu-title">{text('menuHeading')}</h2>
      </div>
      {menu.restaurantOpen === false && (
        <p className="notice">{text('restaurantClosed')}</p>
      )}
      {productCount === 0 ? (
        <p>{text('emptyMenu')}</p>
      ) : (
        menu.categories.map((category) => (
          <section
            aria-labelledby={`category-${category.id}`}
            className="menu-category"
            key={category.id}
          >
            <h3 id={`category-${category.id}`}>{category.name}</h3>
            {category.products.length === 0 ? (
              <p>{text('emptyCategory')}</p>
            ) : (
              <div className="product-list">
                {category.products.map((product) => (
                  <ProductCard
                    canAdd={canAdd}
                    currency={menu.currency}
                    key={product.id}
                    onAdd={onAdd}
                    product={product}
                  />
                ))}
              </div>
            )}
          </section>
        ))
      )}
    </section>
  )
}
