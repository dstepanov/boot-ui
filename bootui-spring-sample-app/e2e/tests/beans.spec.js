// @ts-check
import {expect, test} from './fixtures.js'

/** Build a minimal BeanList response for route mocking. */
function beanListResponse(beans, extra = {}) {
  return {
    total: beans.length,
    beans,
    page: {
      total: beans.length,
      matched: beans.length,
      offset: 0,
      limit: 2000,
      returned: beans.length,
      hasMore: false,
      ...extra
    }
  }
}

test.describe('Beans view', () => {
  test('lists beans and supports filtering by name and classification', async ({openView}) => {
    const page = await openView('beans', 'Beans')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // Name filter narrows the list.
    await page.getByPlaceholder(/Filter by name or type/).fill('productRepository')
    await expect(page.locator('table tbody')).toContainText('productRepository')
    await expect.poll(async () => rows.count()).toBeLessThan(10)

    // Classification filter restricts to a single category (BootUI internals are hidden by default).
    await page.getByPlaceholder(/Filter by name or type/).fill('')
    await page.locator('select.form-select').selectOption('FRAMEWORK')
    const badges = page.locator('table tbody tr td:nth-child(4) .badge')
    await expect
      .poll(async () => {
        const values = await badges.allInnerTexts()
        return values.length > 0 && values.every((c) => c === 'FRAMEWORK')
      })
      .toBeTruthy()
  })

  test('keeps large bean lists responsive while filters search the full set', async ({openView, page}) => {
    const beans = Array.from({length: 205}, (_, index) => ({
      name: `demoBean${index}`,
      type: `com.example.DemoBean${index}`,
      scope: 'singleton',
      classification: 'APPLICATION',
      dependencies: []
    }))

    await page.route('**/bootui/api/beans?*', (route) => {
      const url = new URL(route.request().url())
      const query = (url.searchParams.get('q') || '').toLowerCase()
      const offset = Number(url.searchParams.get('offset') || 0)
      const limit = Number(url.searchParams.get('limit') || 200)
      const matched = query
        ? beans.filter((bean) => bean.name.toLowerCase().includes(query) || bean.type.toLowerCase().includes(query))
        : beans
      const pageBeans = matched.slice(offset, offset + limit)

      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          total: beans.length,
          beans: pageBeans,
          page: {
            total: beans.length,
            matched: matched.length,
            offset,
            limit,
            returned: pageBeans.length,
            hasMore: offset + pageBeans.length < matched.length
          }
        })
      })
    })

    await openView('beans', 'Beans')

    const rows = page.locator('table tbody tr')
    await expect(rows).toHaveCount(200)
    await expect(page.getByText(/Showing 200 of 205 beans/)).toBeVisible()
    await expect(page.getByRole('button', {name: /Load next 5/})).toBeVisible()

    await page.getByPlaceholder(/Filter by name or type/).fill('demoBean204')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('demoBean204')
  })

  test.describe('dependency graph', () => {
    /** Minimal bean set: orderService depends on orderRepository and dataSource. */
    const graphBeans = [
      {
        name: 'orderService',
        type: 'com.example.OrderService',
        scope: 'singleton',
        classification: 'APPLICATION',
        dependencies: ['orderRepository', 'dataSource']
      },
      {
        name: 'orderRepository',
        type: 'com.example.OrderRepository',
        scope: 'singleton',
        classification: 'APPLICATION',
        dependencies: ['dataSource']
      },
      {
        name: 'dataSource',
        type: 'com.zaxxer.hikari.HikariDataSource',
        scope: 'singleton',
        classification: 'FRAMEWORK',
        dependencies: []
      },
      {
        name: 'productService',
        type: 'com.example.ProductService',
        scope: 'singleton',
        classification: 'APPLICATION',
        dependencies: ['orderRepository']
      }
    ]

    test('shows the graph mode toggle and switches to graph view', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')

      // The graph toggle button should be present
      const graphBtn = page.getByRole('button', {name: 'Dependency graph'})
      await expect(graphBtn).toBeVisible()

      // Activate graph mode
      await graphBtn.click()

      // The list table should be gone; the focus search should appear
      await expect(page.locator('table')).not.toBeVisible()
      await expect(page.getByPlaceholder(/Search for a bean/)).toBeVisible()
    })

    test('renders the neighbourhood graph after focusing a bean', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()

      // Wait for beans to load (loading text disappears)
      await expect(page.getByText(/Loading bean graph/)).not.toBeVisible({timeout: 5000})

      // Type a bean name into the search
      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('orderService')
      await focusInput.dispatchEvent('change')

      // The SVG graph should appear
      const graphSvg = page.locator('svg.bean-graph-svg')
      await expect(graphSvg).toBeVisible()

      // The focused bean node should be present with aria label
      const focusNode = page.locator('[aria-label*="orderService"][aria-pressed="true"]')
      await expect(focusNode).toBeVisible()

      // The dependency node should also be visible
      await expect(page.locator('[aria-label*="orderRepository"]')).toBeVisible()
    })

    test('allows navigating to a neighbour by clicking its node', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()
      await expect(page.getByText(/Loading bean graph/)).not.toBeVisible({timeout: 5000})

      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('orderService')
      await focusInput.dispatchEvent('change')
      await expect(page.locator('svg.bean-graph-svg')).toBeVisible()

      // Click on the orderRepository neighbour node to re-focus
      await page.locator('[aria-label*="orderRepository"]').first().click()

      // orderRepository should now be the focus node (aria-pressed="true")
      await expect(page.locator('[aria-label*="orderRepository"][aria-pressed="true"]')).toBeVisible()
    })

    test('returns to list view when the list toggle is clicked', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()

      await page.getByRole('button', {name: 'List view'}).click()

      // The table should be visible again
      await expect(page.locator('table')).toBeVisible()
    })

    test('shows a message for beans with no dependency data (reduced fidelity)', async ({openView, page}) => {
      const emptyDepBeans = [
        {
          name: 'isolatedBean',
          type: 'com.example.IsolatedBean',
          scope: 'ApplicationScoped',
          classification: 'APPLICATION',
          dependencies: []
        }
      ]
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(emptyDepBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()
      await expect(page.getByText(/Loading bean graph/)).not.toBeVisible({timeout: 5000})

      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('isolatedBean')
      await focusInput.dispatchEvent('change')
      await expect(page.locator('svg.bean-graph-svg')).not.toBeVisible()

      // The no-neighbours message should appear
      await expect(page.getByText(/has no recorded dependencies/)).toBeVisible()
    })
  })
})
