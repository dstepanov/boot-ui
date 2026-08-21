// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('Cache view', () => {
  test('lists caches, cache annotations, and clears a cache', async ({openView, page}) => {
    await openView('cache', 'Cache')

    const cacheSection = page.locator('section', {hasText: 'Caches'}).first()
    await expect(cacheSection).toContainText('sample-products')
    await expect(cacheSection).toContainText('sample-greetings')

    const productsRow = cacheSection.locator('tbody tr', {hasText: 'sample-products'}).first()
    // The cache provider depends on how the sample app was started: Caffeine in the default Docker-free
    // (`dev`) profile, or Redis with the full Docker (`docker`) profile.
    await expect(productsRow).toContainText(/Redis|Caffeine/)
    await expect(productsRow.getByRole('button', {name: 'Clear'})).toBeEnabled()

    // Both profiles record native statistics (Caffeine recordStats / Redis enable-statistics), so the
    // panel must label where the counters came from rather than presenting them as anonymous numbers.
    await expect(productsRow).toContainText('Provider statistics')
    await expect(productsRow).toContainText(/hits \d/)

    const operationsSection = page.locator('section', {hasText: 'Annotation operations'}).first()
    await expect(operationsSection).toContainText('@Cacheable')
    await expect(operationsSection).toContainText('@CacheEvict')
    await expect(operationsSection).toContainText('sample-products')

    await productsRow.getByRole('button', {name: 'Clear'}).click()
    await acceptConfirm(page)
    await expect(page.locator('.alert-success')).toContainText('Cleared cache')
  })

  test('discloses the backing tiers of a cache', async ({openView, page}) => {
    await openView('cache', 'Cache')

    const cacheSection = page.locator('section', {hasText: 'Caches'}).first()
    const productsRow = cacheSection.locator('tbody tr', {hasText: 'sample-products'}).first()
    const toggle = productsRow.getByRole('button', {name: /tier/})

    // Tier detail is collapsed by default: the caches table stays scannable until it is asked for.
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const tierRow = page.locator(`#${await toggle.getAttribute('aria-controls')}`)
    await expect(tierRow).toBeVisible()
    // Caffeine in `dev` is local; Redis in `docker` is remote. Both must state which, never guess.
    await expect(tierRow).toContainText(/In this JVM|Remote/)

    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(tierRow).toBeHidden()
  })
})
