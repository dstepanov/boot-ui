// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Hibernate Statistics view', () => {
  test('shows live session statistics once hibernate.generate_statistics is enabled', async ({openView, page}) => {
    await openView('hibernate-statistics', 'Hibernate Statistics')

    // spring.jpa.properties.hibernate.generate_statistics=true (application.properties) keeps the panel out of
    // its "enable statistics" unavailable state and shows real Statistics-backed counters instead.
    await expect(page.getByText('Session statistics are unavailable')).toHaveCount(0, {timeout: 20_000})
    await expect(page.getByText('Sessions & transactions')).toBeVisible()
    await expect(page.getByText('Entities')).toBeVisible()
    await expect(page.getByText('Collections')).toBeVisible()
    await expect(page.getByText('Queries')).toBeVisible()
    await expect(page.getByText('Second-level cache', {exact: true})).toBeVisible()
  })
})
