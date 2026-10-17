import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MenuResponse } from '../../../shared/api/types'
import { MenuCatalog } from './MenuCatalog'

const menu: MenuResponse = {
  catalogVersion: 1,
  locale: 'es-AR',
  currency: 'ARS',
  restaurantOpen: true,
  categories: [
    {
      id: 'a1b2c3d4-e5f6-4789-8123-456789abcdef',
      name: 'Café',
      sortOrder: 1,
      products: [
        {
          id: 'b2c3d4e5-f6a7-4890-9234-56789abcdef0',
          name: 'Flat white',
          priceMinor: 485000,
          available: true,
          station: 'BAR',
          image: {
            path: 'menu/flat-white.webp',
            version: 3,
            alt: 'Flat white en taza',
          },
        },
      ],
    },
  ],
}

describe('menu catalog', () => {
  it('shows the placeholder when a product image cannot be loaded', () => {
    const { container } = render(
      <MenuCatalog menu={menu} canAdd onAdd={vi.fn()} />,
    )
    const image = screen.getByRole('img', { name: 'Flat white en taza' })

    fireEvent.error(image)

    expect(screen.queryByRole('img', { name: 'Flat white en taza' })).toBeNull()
    expect(container.querySelector('.product-card__placeholder')).not.toBeNull()
  })
})
