// @ts-check
import {expect, test} from './fixtures.js'

test.describe('HTTP Probe view', () => {
  test('sends a GET request to the sample API and shows the response body', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.locator('select.form-select').selectOption('GET')
    await page.getByPlaceholder('/api/sample/hello').fill('/api/hello')
    await page.locator('button.btn-primary', {hasText: 'Send'}).click()

    const responseCard = page.locator('.card', {hasText: /^Response/})
    await expect(responseCard).toBeVisible()
    await expect(responseCard.locator('.badge', {hasText: /200 OK/})).toBeVisible()
    await expect(responseCard.locator('pre')).toContainText('Hello, world')
  })

  test('switching to POST reveals the request body editor', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await expect(page.locator('textarea')).toHaveCount(0)
    await page.locator('select.form-select').selectOption('POST')
    await expect(page.locator('textarea')).toBeVisible()
    await page.locator('textarea').fill('{"message":"hello"}')
    await expect(page.locator('.form-text', {hasText: 'Content-Type:'})).toBeVisible()
  })

  test('Enter requires confirmation before sending an unsafe request', async ({openView, page}) => {
    const probeRequests = []
    page.on('request', (request) => {
      if (request.url().endsWith('/bootui/api/http-probe')) probeRequests.push(request)
    })
    await openView('http-probe', 'HTTP Probe')
    await page.locator('select.form-select').selectOption('DELETE')
    const path = page.getByPlaceholder('/api/sample/hello')
    await path.fill('/api/hello')

    await path.press('Enter')

    await expect(page.getByRole('heading', {name: 'Send DELETE request?'})).toBeVisible()
    expect(probeRequests).toHaveLength(0)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('heading', {name: 'Send DELETE request?'})).toBeHidden()
    expect(probeRequests).toHaveLength(0)
  })

  test('clear resets the form back to defaults', async ({openView, page}) => {
    await openView('http-probe', 'HTTP Probe')

    await page.locator('select.form-select').selectOption('POST')
    await page.getByPlaceholder('/api/sample/hello').fill('/something')
    await page.locator('textarea').fill('{"k":1}')

    await page.getByRole('button', {name: /Clear/}).click()

    await expect(page.locator('select.form-select')).toHaveValue('GET')
    await expect(page.getByPlaceholder('/api/sample/hello')).toHaveValue('')
    await expect(page.locator('textarea')).toHaveCount(0)
  })
})
