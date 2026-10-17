import type { CreateOrderRequest, MenuResponse, Product } from '../../../shared/api/types'

export interface CartLine {
  id: string
  product: Product
  optionIds: string[]
  quantity: number
}

export interface OptionSelection {
  optionIds: string[]
  valid: boolean
  reason: 'invalid-count' | 'unavailable' | 'invalid-group' | null
}

/**
 * Replica solo la validación visible de RN-OPT-03; el backend vuelve a
 * verificar pertenencia y disponibilidad al procesar cada pedido.
 */
export function validateOptionSelection(
  product: Product,
  optionIds: string[],
): OptionSelection {
  const groups = product.optionGroups ?? []
  const selectedIds = new Set(optionIds)

  if (optionIds.length > 10 || selectedIds.size !== optionIds.length) {
    return { optionIds, valid: false, reason: 'invalid-count' }
  }

  const selectedOptions = groups.flatMap((group) =>
    group.options
      .filter((option) => selectedIds.has(option.id))
      .map((option) => ({ group, option })),
  )

  if (
    selectedOptions.length !== optionIds.length ||
    selectedOptions.some(({ option }) => !option.available)
  ) {
    return { optionIds, valid: false, reason: 'unavailable' }
  }

  const validGroups = groups.every((group) => {
    const count = selectedOptions.filter(({ group: selectedGroup }) => selectedGroup.id === group.id).length
    if (group.selectionType === 'SINGLE') {
      return (
        count <= 1 &&
        count <= group.maxSelections &&
        (!group.required || count >= group.minSelections)
      )
    }

    return count >= group.minSelections && count <= group.maxSelections
  })

  return {
    optionIds,
    valid: validGroups,
    reason: validGroups ? null : 'invalid-group',
  }
}

export function defaultOptionIds(product: Product): string[] {
  return (product.optionGroups ?? []).flatMap((group) =>
    group.options
      .filter((option) => option.available && option.isDefault)
      .map((option) => option.id),
  )
}

export function cartLineId(productId: string, optionIds: string[]): string {
  return `${productId}:${[...optionIds].sort().join(',')}`
}

export function currentProductForLine(
  line: CartLine,
  menu: MenuResponse,
): Product | undefined {
  return menu.categories
    .flatMap((category) => category.products)
    .find((product) => product.id === line.product.id)
}

export function selectedOptions(
  product: Product,
  optionIds: string[],
): Array<{ id: string; name: string; priceDeltaMinor: number }> {
  const selected = new Set(optionIds)
  return (product.optionGroups ?? []).flatMap((group) =>
    group.options
      .filter((option) => selected.has(option.id))
      .map((option) => ({
        id: option.id,
        name: option.name,
        priceDeltaMinor: option.priceDeltaMinor,
      })),
  )
}

export function estimatedUnitPriceMinor(
  product: Product,
  optionIds: string[],
): number {
  const optionTotal = selectedOptions(product, optionIds).reduce(
    (total, option) => total + BigInt(option.priceDeltaMinor),
    0n,
  )
  const total = BigInt(product.priceMinor) + optionTotal
  const asNumber = Number(total)
  if (!Number.isSafeInteger(asNumber)) {
    throw new RangeError('Estimated unit price is outside the safe integer range.')
  }
  return asNumber
}

export function estimatedCartTotalMinor(lines: CartLine[], menu: MenuResponse): number {
  const total = lines.reduce((sum, line) => {
    const product = currentProductForLine(line, menu) ?? line.product
    return sum +
      BigInt(estimatedUnitPriceMinor(product, line.optionIds)) * BigInt(line.quantity)
  }, 0n)
  const asNumber = Number(total)
  if (!Number.isSafeInteger(asNumber)) {
    throw new RangeError('Estimated cart total is outside the safe integer range.')
  }
  return asNumber
}

export interface OrderDraftResult {
  body?: CreateOrderRequest
  issue: 'empty' | 'too-many-lines' | 'product-unavailable' | 'option-unavailable' | 'invalid-options' | 'quantity' | null
}

/**
 * Prepara la forma HTTP aprobada. No manda precios unitarios: el total es una
 * estimación que el servidor recalcula dentro de la transacción.
 */
export function createOrderDraft(
  lines: CartLine[],
  menu: MenuResponse,
  acknowledgedTotalMinor: number,
): OrderDraftResult {
  if (lines.length === 0) {
    return { issue: 'empty' }
  }
  if (lines.length > 30) {
    return { issue: 'too-many-lines' }
  }

  const items: CreateOrderRequest['items'] = []
  for (const line of lines) {
    if (line.quantity < 1 || line.quantity > 20) {
      return { issue: 'quantity' }
    }
    const product = currentProductForLine(line, menu)
    if (!product || !product.available) {
      return { issue: 'product-unavailable' }
    }

    const selection = validateOptionSelection(product, line.optionIds)
    if (!selection.valid) {
      return {
        issue: selection.reason === 'unavailable' ? 'option-unavailable' : 'invalid-options',
      }
    }

    items.push({
      productId: product.id,
      quantity: line.quantity,
      ...(line.optionIds.length > 0 ? { optionIds: [...line.optionIds].sort() } : {}),
    })
  }

  return {
    body: { items, acknowledgedTotalMinor },
    issue: null,
  }
}
