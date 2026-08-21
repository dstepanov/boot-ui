// @ts-check
import {expect, test} from './fixtures.js'

test.describe('WebSockets view', () => {
  test('lists the STOMP and native handler endpoints declared by the sample app', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    // Real endpoints registered by SampleStompConfiguration and SampleWebSocketHandlerConfiguration,
    // discovered from the already-registered handler mappings - no handshake is performed to list them.
    const stompRow = page.locator('table tbody tr', {hasText: '/ws'}).first()
    await expect(stompRow).toBeVisible()
    await expect(stompRow).toContainText('STOMP')
    await expect(stompRow).toContainText('SockJS')

    const echoRow = page.locator('table tbody tr', {hasText: '/echo'}).first()
    await expect(echoRow).toBeVisible()
    await expect(echoRow).toContainText('HANDLER')
  })

  test('states that payloads are never captured and offers frame capture on the servlet stack', async ({
    openView,
    page
  }) => {
    await openView('websockets', 'WebSockets')

    await expect(page.getByText(/Message payloads are never read or stored/i)).toBeVisible()

    // The servlet stack has a sanctioned decoration seam, so the capture toggle must be offered.
    const pause = page.getByRole('button', {name: 'Pause'})
    await expect(pause).toBeVisible()

    await pause.click()
    await expect(page.locator('.alert-success')).toBeVisible()

    const resume = page.getByRole('button', {name: 'Resume'})
    await expect(resume).toBeVisible()

    // Restore capture so the rest of the suite sees the default state.
    await resume.click()
    await expect(page.getByRole('button', {name: 'Pause'})).toBeVisible()
  })

  test('switches between the endpoint, session, subscription and activity tabs', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    for (const label of ['Sessions', 'Subscriptions', 'Activity', 'Endpoints']) {
      const tab = page.getByRole('tab', {name: new RegExp(`^${label}`)})
      await tab.click()
      await expect(tab).toHaveAttribute('aria-selected', 'true')
    }

    const endpoints = page.getByRole('tab', {name: /^Endpoints/})
    await endpoints.press('ArrowRight')
    await expect(page.getByRole('tab', {name: /^Sessions/})).toBeFocused()
    await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'websockets-tab-sessions')
  })

  test('filtering by path narrows the endpoint table', async ({openView, page}) => {
    await openView('websockets', 'WebSockets')

    const filter = page.getByPlaceholder(/Filter by path, destination, handler, or session/)
    const rows = page.locator('table tbody tr')

    await filter.fill('/echo')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('/echo')

    await filter.fill('no-such-websocket-endpoint-xyz')
    await expect(rows.first()).toContainText('No WebSocket endpoint matches your filter.')
  })
})
