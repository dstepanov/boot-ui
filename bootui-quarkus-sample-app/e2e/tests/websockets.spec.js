// @ts-check
import {expect, test} from './fixtures.js'

test.describe('WebSockets view (Quarkus)', () => {
  test('lists the sample WebSockets Next endpoint discovered at build time', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    // Real @WebSocket endpoint from the sample app, indexed by Jandex at build time -- listing it
    // never opens a connection.
    const row = page.locator('table tbody tr', {hasText: '/ws/echo'}).first()
    await expect(row).toBeVisible()
    await expect(row).toContainText('ENDPOINT')
    await expect(row).toContainText('SampleEchoWebSocket')
  })

  test('is honest that Quarkus WebSockets Next exposes no frame capture seam', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    // Quarkus WebSockets Next has no message-interception SPI, so BootUI must say so rather than
    // pretend capture is available, and must not render a capture toggle.
    await expect(page.getByText(/message.interception SPI/i)).toBeVisible()
    await expect(page.getByRole('button', {name: 'Pause'})).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Resume'})).toHaveCount(0)
    await expect(page.getByText(/Message payloads are never read or stored/i)).toBeVisible()
  })

  test('filtering by path narrows the endpoint table', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    const filter = page.getByPlaceholder(/Filter by path, destination, handler, or session/)
    const rows = page.locator('table tbody tr')

    await filter.fill('/ws/echo')
    await expect(rows.first()).toContainText('/ws/echo')

    await filter.fill('no-such-websocket-endpoint-xyz')
    await expect(rows.first()).toContainText('No WebSocket endpoint matches your filter.')
  })
})
