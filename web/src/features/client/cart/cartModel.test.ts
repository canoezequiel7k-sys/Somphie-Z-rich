import { describe, expect, it } from 'vitest'
import type { MenuResponse, Product } from '../../../shared/api/types'
import {
  cartLineId,
  createOrderDraft,
  defaultOptionIds,
  estimatedCartTotalMinor,
  estimatedUnitPriceMinor,
  validateOptionSelection,
  type CartLine,
} from './cartModel'

const product = {
  id: '9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7',
  name: 'Flat white',
  priceMinor: 485000,
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
          id: 'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70',
          name: 'Chico',
          priceDeltaMinor: 0,
          available: true,
          isDefault: true,
        },
        {
          id: 'e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081',
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
          id: 'f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192',
          name: 'Avena',
          priceDeltaMinor: 60000,
          available: true,
          isDefault: false,
        },
        {
          id: 'a7b8c9d0-e1f2-4a3b-8c4d-5e6f70819203',
          name: 'Almendra',
          priceDeltaMinor: 40000,
          available: true,
          isDefault: false,
        },
        {
          id: 'b8c9d0e1-f2a3-4b5c-8d6e-7f8091a2b304',
          name: 'Soja',
          priceDeltaMinor: 30000,
          available: false,
          isDefault: false,
        },
      ],
    },
  ],
} satisfies Product

const menu: MenuResponse = {
  catalogVersion: 42,
  locale: 'es-AR',
  currency: 'ARS',
  restaurantOpen: true,
  categories: [
    {
      id: '5c0f2a7e-3d41-4b8a-9a52-7e1d6f0a2b11',
      name: 'Café',
      sortOrder: 1,
      products: [product],
    },
  ],
}

function line(optionIds = [product.optionGroups![0]!.options[0]!.id], quantity = 1): CartLine {
  return {
    id: cartLineId(product.id, optionIds),
    product,
    optionIds,
    quantity,
  }
}

describe('cart model', () => {
  it('starts with available default options and validates selection rules from the API', () => {
    const defaults = defaultOptionIds(product)
    expect(defaults).toEqual(['d4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70'])
    expect(validateOptionSelection(product, defaults).valid).toBe(true)
    expect(validateOptionSelection(product, []).valid).toBe(false)
    expect(
      validateOptionSelection(product, [
        defaults[0]!,
        'e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081',
      ]).valid,
    ).toBe(false)
    expect(
      validateOptionSelection(product, [
        defaults[0]!,
        'b8c9d0e1-f2a3-4b5c-8d6e-7f8091a2b304',
      ]).reason,
    ).toBe('unavailable')
  })

  it('sums base and option prices as integer minor units', () => {
    const options = [
      'e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081',
      'f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192',
    ]
    expect(estimatedUnitPriceMinor(product, options)).toBe(625000)
    expect(
      estimatedCartTotalMinor([line(options, 2)], menu),
    ).toBe(1_250_000)
  })

  it('keeps separate cart lines for different option combinations', () => {
    const regular = cartLineId(product.id, ['one', 'two'])
    const reordered = cartLineId(product.id, ['two', 'one'])
    const different = cartLineId(product.id, ['one'])

    expect(reordered).toBe(regular)
    expect(different).not.toBe(regular)
  })

  it('builds only contract fields and refuses stale or invalid menu lines', () => {
    const draft = createOrderDraft(
      [line(['d4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70'], 2)],
      menu,
      970000,
    )
    expect(draft).toEqual({
      body: {
        items: [
          {
            productId: product.id,
            quantity: 2,
            optionIds: ['d4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70'],
          },
        ],
        acknowledgedTotalMinor: 970000,
      },
      issue: null,
    })

    expect(
      createOrderDraft([line([], 1)], menu, 485000).issue,
    ).toBe('invalid-options')
    expect(
      createOrderDraft([line([], 21)], menu, 485000).issue,
    ).toBe('quantity')
    expect(
      createOrderDraft([line()], { ...menu, categories: [] }, 485000).issue,
    ).toBe('product-unavailable')
  })
})
