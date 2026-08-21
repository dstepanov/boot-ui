// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The REST API panel's declared error contract is a pure declaration read: BootUI lists the exception
 * handlers the application declares without instantiating an advice, invoking a handler, or triggering a
 * failure. The Spring sample app declares a global `@RestControllerAdvice` (SampleGlobalErrorHandler) and a
 * controller-local `@ExceptionHandler` (SampleErrorController), so this test proves the panel reads the
 * host application's real declarations, distinguishes application-wide from controller-local scope, and
 * reports a runtime-built status honestly rather than inventing one.
 */
test.describe('REST API declared error contract (Spring MVC)', () => {
  test('lists the sample application declared handlers with honest scope and status', async ({openView, page}) => {
    await openView('rest-api', 'REST API')

    const card = page.locator('.card', {hasText: 'Declared error contract'})
    await expect(card).toBeVisible()
    await expect(card).toContainText('Nothing is executed and no error is triggered.')

    const globalRow = card.locator('tbody tr', {hasText: 'SampleOrderNotFoundException'})
    await expect(globalRow).toBeVisible({timeout: 15_000})
    await expect(globalRow).toContainText('SampleGlobalErrorHandler#handleOrderNotFound')
    await expect(globalRow).toContainText('Application-wide')
    await expect(globalRow).toContainText('404')
    await expect(globalRow).toContainText('Problem detail')

    // A ResponseEntity handler cannot declare its status, so the panel says so instead of guessing,
    // while the media type it does declare is shown.
    const dynamicRow = card.locator('tbody tr', {hasText: 'SampleOrderConflictException'})
    await expect(dynamicRow).toContainText('Runtime')
    await expect(dynamicRow).toContainText('application/json')

    // A controller-local handler is reported as such, which is what makes it outrank global advice.
    const localRow = card.locator('tbody tr', {hasText: 'SampleOrderRejectedException'})
    await expect(localRow).toContainText('SampleErrorController#handleLocally')
    await expect(localRow).toContainText('Controller-local')
  })

  test('filters the catalogue on the server and can be cleared', async ({openView, page}) => {
    await openView('rest-api', 'REST API')

    const card = page.locator('.card', {hasText: 'Declared error contract'})
    const filter = card.getByLabel('Filter declared error contract')
    await expect(filter).toBeVisible({timeout: 15_000})

    await filter.fill('SampleOrderRejectedException')
    await expect(card.locator('tbody tr')).toHaveCount(1)
    await expect(card.locator('tbody tr')).toContainText('SampleErrorController#handleLocally')

    await filter.fill('nothingmatchesthisxyz123')
    await expect(card).toContainText('No declared handler matches')
    await card.getByRole('button', {name: 'Clear filter'}).click()
    await expect(card.locator('tbody tr').first()).toBeVisible()
  })

  test('a captured failure links from the Exceptions panel to its declared handler', async ({openView, page}) => {
    // The sample endpoint fails with an exception the global advice declares a handler for, so the
    // Exceptions panel can attribute the retained failure to exactly one declaration.
    await page.request.get('/api/errors/not-found')

    await openView('exceptions', 'Exceptions')
    const row = page.locator('tbody tr', {hasText: 'SampleOrderNotFoundException'}).first()
    await expect(row).toBeVisible({timeout: 15_000})
    await expect(row).toContainText('Handled by')

    await row.getByRole('link', {name: /SampleGlobalErrorHandler#handleOrderNotFound/}).click()

    // The REST API panel opens with the catalogue already narrowed to the component that declares the
    // handler, so its whole declared contract is in view and the controller-local handler is filtered out.
    const card = page.locator('.card', {hasText: 'Declared error contract'})
    await expect(card).toBeVisible({timeout: 15_000})
    await expect(card.locator('tbody tr')).toHaveCount(2)
    await expect(card).toContainText('SampleGlobalErrorHandler#handleOrderNotFound')
    await expect(card).not.toContainText('SampleErrorController#handleLocally')
  })
})
