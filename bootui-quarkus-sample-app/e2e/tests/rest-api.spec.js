// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The REST API advisor runs curated, project-agnostic REST design rules against the host application's
 * own JAX-RS resources. The sample app has no rule-specific markers planted (unlike Architecture/
 * Security/Hibernate), so this test proves the Quarkus backend performs a real reflective scan over the
 * sample's own resource classes (SampleResource, AdminResource, SecureResource, ...) by asserting the
 * "Controllers analysed" / handler-method counts are real positive numbers, rather than pinning a
 * specific finding.
 */
test.describe('REST API advisor (Quarkus)', () => {
  test('runs REST API checks and analyses the sample app resources', async ({openView, page}) => {
    await openView('rest-api', 'REST API')

    await page.getByRole('button', {name: 'Run REST API checks'}).click()
    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})

    const controllersMetric = page.locator('.advisor-summary__metric', {hasText: 'Controllers analysed'})
    const controllersAnalyzed = Number((await controllersMetric.locator('dd').textContent())?.trim())
    expect(controllersAnalyzed).toBeGreaterThan(0)
    await expect(controllersMetric.locator('.advisor-summary__hint')).toHaveText(/\d+ handler method\(s\)/)
  })

  /**
   * The declared error contract is captured from the build-time Jandex index on Quarkus, because Quarkus
   * exposes no runtime enumeration of resolved mappers. This test proves that capture reaches the UI for
   * both mapper flavours the sample declares — a global `@Provider ExceptionMapper` and a resource-local
   * `@ServerExceptionMapper` — and that the panel reports a `Response`-built status honestly.
   */
  test('lists the declared exception mappers without invoking any of them', async ({openView, page}) => {
    await openView('rest-api', 'REST API')

    const card = page.locator('.card', {hasText: 'Declared error contract'})
    await expect(card).toBeVisible()
    await expect(card).toContainText('Nothing is executed and no error is triggered.')

    const globalRow = card.locator('tbody tr', {hasText: 'SampleProductNotFoundException'})
    await expect(globalRow).toBeVisible({timeout: 15_000})
    await expect(globalRow).toContainText('SampleProductNotFoundMapper#toResponse')
    await expect(globalRow).toContainText('Application-wide')
    // A jakarta.ws.rs Response builds its status at runtime, so the panel must not claim one.
    await expect(globalRow).toContainText('Runtime')

    const localRow = card.locator('tbody tr', {hasText: 'SampleProductRejectedException'})
    await expect(localRow).toContainText('SampleErrorResource#handleLocally')
    await expect(localRow).toContainText('Controller-local')
  })
})
