// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The standalone Hibernate Statistics panel (Database group) reads live session/query-cache counters via
 * the Quarkus SessionFactory's Statistics API, independent of the Hibernate advisor above.
 */
test.describe('Hibernate Statistics view (Quarkus)', () => {
  test('shows live session statistics once quarkus.hibernate-orm.statistics is enabled', async ({openView, page}) => {
    await openView('hibernate-statistics', 'Hibernate Statistics')

    // quarkus.hibernate-orm.statistics=true (application.properties) keeps the panel out of its "enable
    // statistics" unavailable state and shows real Statistics-backed counters instead.
    await expect(page.getByText('Session statistics are unavailable')).toHaveCount(0, {timeout: 20_000})
    await expect(page.getByText('Sessions & transactions')).toBeVisible()
    await expect(page.getByText('Entities')).toBeVisible()
    await expect(page.getByText('Collections')).toBeVisible()
    await expect(page.getByText('Queries')).toBeVisible()
    await expect(page.getByText('Second-level cache')).toBeVisible()
  })
})
