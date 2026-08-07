// @ts-check
import {expect, test} from './fixtures.js'

async function useTwoHundredPercentText(page) {
  await page.addStyleTag({content: 'html { font-size: 200% !important; }'})
}

async function expectOwnedHorizontalOverflow(page, tableSelector) {
  await expect(page.locator(tableSelector).first()).toBeVisible()
  const layout = await page.evaluate((selector) => {
    const documentScroller = document.scrollingElement
    const workspace = document.querySelector('.bootui-workspace')
    const table = document.querySelector(selector)
    const scrollRegion = table?.closest('.table-responsive')
    if (!documentScroller || !workspace || !table || !scrollRegion)
      throw new Error(`Missing layout node for ${selector}`)

    return {
      documentOverflows: documentScroller.scrollWidth > documentScroller.clientWidth + 1,
      workspaceOverflows: workspace.scrollWidth > workspace.clientWidth + 1,
      tableOverflowX: getComputedStyle(scrollRegion).overflowX,
      tableScrolls: scrollRegion.scrollWidth > scrollRegion.clientWidth + 1
    }
  }, tableSelector)

  expect(layout.documentOverflows).toBe(false)
  expect(layout.workspaceOverflows).toBe(false)
  expect(layout.tableOverflowX).toBe('auto')
  expect(layout.tableScrolls).toBe(true)
}

async function expectMobileTarget(page, locator) {
  const bounds = await locator.boundingBox()
  if (!bounds) throw new Error('Expected a visible mobile interaction target')
  expect(bounds.width).toBeGreaterThanOrEqual(44)
  expect(bounds.height).toBeGreaterThanOrEqual(44)
}

test.describe('responsive accessibility', () => {
  test.use({viewport: {width: 320, height: 720}})

  test('keeps Flyway tables and actions reachable at 320px with 200% text', async ({openView, page}) => {
    await openView('flyway', 'Flyway migrations')
    await useTwoHundredPercentText(page)

    await expectOwnedHorizontalOverflow(page, '.flyway-migrations-table')
    await expectMobileTarget(page, page.getByRole('button', {name: 'Migrate'}).first())
  })

  test('keeps Liquibase tables and actions reachable at 320px with 200% text', async ({openView, page}) => {
    await openView('liquibase', 'Liquibase change sets')
    await useTwoHundredPercentText(page)

    await expectOwnedHorizontalOverflow(page, '.liquibase-changesets-table')
    await expectMobileTarget(page, page.getByRole('button', {name: 'Update'}).first())
  })

  test('keeps Spring Data methods and repository controls reachable at 320px with 200% text', async ({
    openView,
    page
  }) => {
    await openView('data', 'Spring Data repositories')
    await useTwoHundredPercentText(page)

    const repository = page.locator('.list-group-item-action', {hasText: 'ProductRepository'})
    await expectMobileTarget(page, repository)
    await repository.click()
    await expect(page.locator('.data-methods-table')).toBeVisible()
    await expectOwnedHorizontalOverflow(page, '.data-methods-table')
  })

  test('selectively removes motion while leaving busy and progress state visible', async ({page}) => {
    await page.emulateMedia({reducedMotion: 'reduce'})
    await page.goto('/bootui/')

    const motion = await page.evaluate(() => {
      const spinner = document.createElement('span')
      spinner.className = 'spinner-border'
      spinner.setAttribute('aria-hidden', 'true')
      const progress = document.createElement('div')
      progress.className = 'progress-bar'
      progress.style.width = '60%'
      document.body.append(spinner, progress)

      const sidebar = document.querySelector('.bootui-sidebar')
      return {
        reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
        sidebarTransition: sidebar ? getComputedStyle(sidebar).transitionDuration : null,
        spinnerAnimation: getComputedStyle(spinner).animationName,
        progressTransition: getComputedStyle(progress).transitionDuration,
        progressWidth: getComputedStyle(progress).width
      }
    })

    expect(motion).toMatchObject({
      reduced: true,
      sidebarTransition: '0s',
      spinnerAnimation: 'none',
      progressTransition: '0s'
    })
    expect(Number.parseFloat(motion.progressWidth)).toBeGreaterThan(0)
  })
})
