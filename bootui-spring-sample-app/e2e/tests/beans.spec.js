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
    await page.getByRole('button', {name: 'List view'}).click()

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    // Name filter narrows the list.
    await page.getByPlaceholder(/Filter by name or type/).fill('productRepository')
    await expect(page.locator('table tbody')).toContainText('productRepository')
    await expect.poll(async () => rows.count()).toBeLessThan(10)

    const productGraphLink = page.getByRole('button', {name: 'Show dependency graph for productRepository'})
    await productGraphLink.focus()
    await productGraphLink.press('Enter')
    await expect(page.getByRole('button', {name: 'Dependency graph'})).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByPlaceholder(/Search for a bean/)).toBeFocused()
    await expect(page.getByPlaceholder(/Search for a bean/)).toHaveValue('productRepository')
    await expect(page.locator('[aria-label*="productRepository"][aria-pressed="true"]')).toBeVisible()

    // Classification filter restricts to a single category (BootUI internals are hidden by default).
    await page.getByRole('button', {name: 'List view'}).click()
    await page.getByPlaceholder(/Filter by name or type/).fill('')
    await page.locator('select.form-select').selectOption('FRAMEWORK')
    const badges = page.locator('table tbody tr td:nth-child(4) .badge')
    await expect
      .poll(async () => {
        const values = await badges.allInnerTexts()
        return values.length > 0 && values.every((c) => c === 'FRAMEWORK')
      })
      .toBeTruthy()
    await expect(page.locator('select.form-select option[value="BOOTUI"]')).toHaveCount(0)
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
    await page.getByRole('button', {name: 'List view'}).click()

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

    test('opens in graph mode with application beans selected', async ({openView, page}) => {
      const requestedLimits = []
      await page.route('**/bootui/api/beans?*', (route) => {
        requestedLimits.push(new URL(route.request().url()).searchParams.get('limit'))
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      })
      await openView('beans', 'Beans')

      const graphBtn = page.getByRole('button', {name: 'Dependency graph'})
      const listBtn = page.getByRole('button', {name: 'List view'})
      await expect(graphBtn).toBeVisible()
      await expect(graphBtn).toHaveText(/Graph/)
      await expect(listBtn).toHaveText(/List/)
      await expect(graphBtn).toHaveAttribute('aria-pressed', 'true')
      await expect(page.locator('table')).not.toBeVisible()
      await expect(page.getByPlaceholder(/Search for a bean/)).toBeVisible()
      await expect.poll(() => requestedLimits).toContain('1000')
      await expect(page.locator('#beans-graph-classification')).toHaveValue('APPLICATION')
      await expect(page.locator('#beans-graph-datalist option')).toHaveCount(3)
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
      const graphScroll = page.locator('.bean-graph-scroll')
      await expect(graphSvg).toBeVisible()
      const defaultWidth = Number(await graphSvg.getAttribute('width'))
      const defaultViewportHeight = await graphScroll.evaluate((element) => element.clientHeight)
      const defaultScrollHeight = await graphScroll.evaluate((element) => element.scrollHeight)

      await page.getByRole('button', {name: 'Zoom in'}).click()
      await expect(page.getByRole('button', {name: 'Reset zoom'})).toHaveText('120%')
      await expect.poll(async () => Number(await graphSvg.getAttribute('width'))).toBeGreaterThan(defaultWidth)
      await expect.poll(async () => graphScroll.evaluate((element) => element.clientHeight)).toBe(defaultViewportHeight)
      await expect
        .poll(async () => graphScroll.evaluate((element) => element.scrollHeight))
        .toBeGreaterThan(defaultScrollHeight)
      await page.getByRole('button', {name: 'Reset zoom'}).click()
      await expect.poll(async () => Number(await graphSvg.getAttribute('width'))).toBe(defaultWidth)

      // The focused bean node should be present with aria label
      const focusNode = page.locator('[aria-label*="orderService"][aria-pressed="true"]')
      await expect(focusNode).toBeVisible()

      // The dependency node should also be visible
      await expect(page.locator('[aria-label*="orderRepository"]')).toBeVisible()
      await expect(page.locator('[aria-label*="dataSource"]')).not.toBeVisible()

      await page.locator('#beans-graph-classification').selectOption('')
      await expect(page.locator('[aria-label*="dataSource"]')).toBeVisible()
    })

    test('shows exact positive condition evidence for a focused bean', async ({openView, page}) => {
      const beansWithResource = graphBeans.map((bean) =>
        bean.name === 'orderService'
          ? {
              ...bean,
              resource: 'class path resource [com/example/OrderAutoConfiguration.class]'
            }
          : bean
      )
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(beansWithResource))
        })
      )
      await page.route('**/bootui/api/conditions?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            positiveMatches: [
              {
                autoConfigurationClass: 'com.example.OrderAutoConfiguration',
                condition: 'OnClassCondition',
                message: 'Required order classes were found.',
                outcome: 'MATCH'
              },
              {
                autoConfigurationClass: 'com.example.OtherAutoConfiguration',
                condition: 'OtherCondition',
                message: 'Only mentions com.example.OrderAutoConfiguration.',
                outcome: 'MATCH'
              }
            ],
            negativeMatches: [],
            unconditionalClasses: [],
            exclusions: [],
            page: {total: 2, matched: 2, offset: 0, limit: 1000, returned: 2, hasMore: false}
          })
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()

      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('orderService')
      await focusInput.press('Enter')

      await expect(page.getByRole('heading', {name: 'Why this bean exists'})).toBeVisible()
      await expect(page.getByText('Required order classes were found.')).toBeVisible()
      await expect(page.getByText('OtherCondition')).not.toBeVisible()
    })

    test('shows an explicit empty state when no beans are available to graph', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse([]))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()

      await expect(page.getByRole('heading', {name: 'No beans available to graph'})).toBeVisible()
      await expect(page.getByPlaceholder(/Search for a bean/)).not.toBeVisible()
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

    test('supports roving keyboard navigation and focus selection', async ({openView, page}) => {
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()
      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('orderService')
      await focusInput.press('Enter')

      const focusNode = page.locator('.bg-node[aria-pressed="true"]')
      await focusNode.focus()
      await focusNode.press('ArrowRight')
      const movedNode = page.locator('.bg-node:focus')
      await expect(movedNode).not.toHaveAttribute('aria-pressed', 'true')
      const movedName = (await movedNode.getAttribute('aria-label')).split('.')[0]
      await movedNode.press('Enter')
      await expect(page.locator(`[aria-label^="${movedName}"][aria-pressed="true"]`)).toBeFocused()
    })

    test('uses a motion-free graph when reduced motion is requested', async ({openView, page}) => {
      await page.emulateMedia({reducedMotion: 'reduce'})
      await page.route('**/bootui/api/beans?*', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(beanListResponse(graphBeans))
        })
      )
      await openView('beans', 'Beans')
      await page.getByRole('button', {name: 'Dependency graph'}).click()
      const focusInput = page.getByPlaceholder(/Search for a bean/)
      await focusInput.fill('orderService')
      await focusInput.press('Enter')

      const node = page.locator('.bg-node').first()
      await expect(node).toBeVisible()
      expect(await node.evaluate((element) => getComputedStyle(element).animationName)).toBe('none')
      expect(await node.evaluate((element) => getComputedStyle(element).transitionDuration)).toBe('0s')
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
