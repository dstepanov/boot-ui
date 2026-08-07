// @ts-check
import {expect, test} from './fixtures.js'

/**
 * Metrics renders the same shared Metrics.vue on both adapters. On Quarkus the live meters come from
 * the application's quarkus-micrometer registry (the sample app wires the Prometheus registry), so the
 * JVM binders publish meters like jvm.memory.used. These checks pin that the panel renders real meters
 * (not an unavailable shell) and that BootUI's own /bootui meters stay hidden via the shared self-filter.
 */
test.describe('Metrics view', () => {
  test('renders meter browser, measurements and live graph', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const meters = page.locator('.meter-list .list-group-item')
    await expect.poll(async () => meters.count()).toBeGreaterThan(0)

    const filteredRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('q') === 'jvm.memory.used'
    })
    await page.getByRole('textbox', {name: 'Search meters'}).fill('jvm.memory.used')
    await filteredRequest
    const meter = page.locator('.meter-list .list-group-item', {hasText: 'jvm.memory.used'}).first()
    if (await meter.count()) {
      await meter.click()
    } else {
      await page.getByRole('textbox', {name: 'Search meters'}).fill('')
      await meters.first().click()
    }

    await expect(page.locator('.card', {hasText: /Current/})).toBeVisible()
    await expect(page.locator('svg[aria-label="Live metric value graph"]')).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Samples'})).toBeVisible()
    await expect(page.locator('table tbody tr').first()).toBeVisible()
    await expect(page.getByText(/Showing \d+–\d+ of \d+/)).toBeVisible()
  })

  test('filters meters by type on the server', async ({openView, page}) => {
    await openView('metrics', 'Metrics')

    const typeSelect = page.locator('.card-body.border-bottom select')
    await expect.poll(async () => await typeSelect.locator('option').count()).toBeGreaterThan(1)
    const firstType = await typeSelect.locator('option').nth(1).getAttribute('value')
    expect(firstType).toBeTruthy()

    const filteredRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/api/metrics') && url.searchParams.get('type') === firstType
    })
    await typeSelect.selectOption(firstType)
    await filteredRequest
    const firstMeter = page.locator('.meter-list .list-group-item').first()
    await expect(firstMeter.locator('.badge')).toHaveText(firstType)
    await expect(page.getByText(/Filters run on the server/)).toBeVisible()
  })
})
