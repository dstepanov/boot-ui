// @ts-check
import {expect, test} from './fixtures.js'

const POLICIES = 'table.fault-tolerance-policy-table tbody tr'
const EVENTS = 'table.fault-tolerance-event-table tbody tr'

test.describe('Fault Tolerance view (Quarkus)', () => {
  test('lists the sample SmallRye Fault Tolerance policies', async ({openView, page}) => {
    await openView('fault-tolerance', 'Fault Tolerance')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const named = page.locator(POLICIES, {hasText: 'inventory-service'}).first()
    await expect(named).toBeVisible()
    await expect(named).toContainText('circuit breaker')
    await expect(named).toContainText('SampleFaultToleranceService#charge')

    await expect(page.locator(POLICIES, {hasText: 'SampleFaultToleranceService#exportReport'})).toContainText(
      'time limiter'
    )
    await expect(page.locator(POLICIES, {hasText: 'SampleFaultToleranceService#rebuildIndex'})).toContainText(
      'bulkhead'
    )
    await expect(page.locator(POLICIES, {hasText: 'SampleFaultToleranceService#search'})).toContainText('rate limiter')
  })

  test('shows the effective MicroProfile configuration override with configured provenance', async ({
    openView,
    page
  }) => {
    await openView('fault-tolerance', 'Fault Tolerance')

    const retry = page.locator(POLICIES, {hasText: 'SampleFaultToleranceService#reserve'}).first()
    await expect(retry).toBeVisible()
    // application.properties overrides @Retry(maxRetries = 3) with 2 for this exact method.
    await expect(retry).toContainText('maxRetries: 2')
  })

  test('reports UNKNOWN rather than guessing state for a breaker SmallRye cannot name', async ({openView, page}) => {
    await openView('fault-tolerance', 'Fault Tolerance')

    const anonymous = page.locator(POLICIES, {hasText: 'SampleFaultToleranceService#settle'}).first()
    await expect(anonymous).toBeVisible()
    await expect(anonymous).toContainText('UNKNOWN')
  })

  test('captures circuit breaker state transitions triggered by a real request', async ({openView, page, request}) => {
    const triggered = await request.get('/api/sample/fault-tolerance')
    expect(triggered.ok()).toBeTruthy()

    await openView('fault-tolerance', 'Fault Tolerance')
    await expect(page.locator('text=Loading…')).toHaveCount(0)

    await expect(page.locator(EVENTS, {hasText: 'STATE_TRANSITION'}).first()).toBeVisible({timeout: 15_000})
    await expect(page.locator('main')).not.toContainText('payment gateway unreachable')
  })
})
