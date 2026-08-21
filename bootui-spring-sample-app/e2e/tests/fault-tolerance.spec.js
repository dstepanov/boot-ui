// @ts-check
import {expect, test} from './fixtures.js'

const POLICIES = 'table.fault-tolerance-policy-table tbody tr'
const EVENTS = 'table.fault-tolerance-event-table tbody tr'

test.describe('Fault Tolerance view', () => {
  test('lists the sample Resilience4j and Spring Retry policies', async ({openView, page}) => {
    await openView('fault-tolerance', 'Fault Tolerance')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const breaker = page.locator(POLICIES, {hasText: 'inventory-service'}).first()
    await expect(breaker).toBeVisible()
    await expect(breaker).toContainText('circuit breaker')

    await expect(page.locator(POLICIES, {hasText: 'catalog-api'})).toContainText('rate limiter')
    await expect(page.locator(POLICIES, {hasText: 'report-export'})).toContainText('bulkhead')
    await expect(page.locator(POLICIES, {hasText: 'slow-backend'})).toContainText('time limiter')

    // Spring Retry is discovered from the @Retryable annotation, not from a registry.
    const retryable = page.locator(POLICIES, {hasText: 'FlakyInventoryClient#reserve'}).first()
    await expect(retryable).toBeVisible()
    await expect(retryable).toContainText('retry')
  })

  test('filters the policy inventory by name and by type', async ({openView, page}) => {
    await openView('fault-tolerance', 'Fault Tolerance')

    const filter = page.locator('input.fault-tolerance-filter-input')
    const rows = page.locator(POLICIES)

    await filter.fill('catalog-api')
    await expect(rows).toHaveCount(1)

    await filter.fill('no-such-policy-xyz')
    await expect(rows).toHaveCount(0)

    await filter.fill('')
    await page.locator('select.fault-tolerance-type-select').selectOption('BULKHEAD')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('report-export')
  })

  test('captures retries and circuit breaker transitions triggered by a real request', async ({
    openView,
    page,
    request
  }) => {
    const triggered = await request.get('/api/sample/fault-tolerance')
    expect(triggered.ok()).toBeTruthy()

    await openView('fault-tolerance', 'Fault Tolerance')
    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const events = page.locator(EVENTS)
    await expect(events.filter({hasText: 'STATE_TRANSITION'}).first()).toBeVisible()
    await expect(events.filter({hasText: 'RETRY'}).first()).toBeVisible()
    // The Spring Retry event names the policy exactly as the inventory does, so filtering correlates.
    await expect(events.filter({hasText: 'FlakyInventoryClient#reserve'}).first()).toBeVisible()

    // Capture stays metadata-only: the panel says so, and no exception message is shown.
    await expect(page.locator('main')).toContainText(
      'never records method arguments, return values, payloads or raw exception messages'
    )
    await expect(page.locator('main')).not.toContainText('inventory service temporarily unavailable')
  })

  test('surfaces fault tolerance events in Live Activity with a deep link back to this panel', async ({
    page,
    request
  }) => {
    const triggered = await request.get('/api/sample/fault-tolerance')
    expect(triggered.ok()).toBeTruthy()

    await page.goto('/bootui/#/activity')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /Live Activity/})
        .first()
    ).toBeVisible()

    const table = page.locator('.activity-table')
    await expect(table).toContainText('FAULT_TOLERANCE', {timeout: 15_000})
    await expect(table).toContainText('inventory-service')
  })
})
