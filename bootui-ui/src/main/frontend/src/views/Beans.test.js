import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import BeanGraph from './BeanGraph.vue'
import Beans from './Beans.vue'

// ── helpers ───────────────────────────────────────────────────────────────────

function bean(name, deps = [], classification = 'APPLICATION') {
  return {
    name,
    type: `com.example.${name}`,
    scope: 'singleton',
    classification,
    dependencies: deps
  }
}

function beanList(beans) {
  return {
    total: beans.length,
    beans,
    page: {
      total: beans.length,
      matched: beans.length,
      offset: 0,
      limit: 200,
      returned: beans.length,
      hasMore: false
    }
  }
}

/**
 * Stubs global fetch so:
 *  - GET api/beans?* (list mode paged) returns the listResponse
 *  - GET api/beans?offset=0&limit=1000 (graph mode paged load) returns the graphResponse
 *    (defaults to the same response as listResponse if not provided separately)
 */
function stubFetch(listResponse, graphResponse = null, conditionsResponse = null) {
  const graphBody = graphResponse ?? listResponse
  vi.stubGlobal(
    'fetch',
    vi.fn((input) => {
      const url = typeof input === 'string' ? input : String(input)
      if (url.includes('api/conditions?')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve(
              conditionsResponse ?? {
                positiveMatches: [],
                negativeMatches: [],
                unconditionalClasses: [],
                exclusions: []
              }
            )
        })
      }
      const body = url.includes('limit=1000') ? graphBody : listResponse
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(body)
      })
    })
  )
}

function mountBeans(platform = 'spring-boot') {
  return mount(Beans, {
    global: {
      provide: {
        panels: {value: {platform, panels: [{id: 'conditions', available: platform !== 'quarkus', enabled: true}]}}
      }
    }
  })
}

async function openGraph(wrapper) {
  await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
  await vi.dynamicImportSettled()
  await flushPromises()
}

async function openList(wrapper) {
  await wrapper.find('[aria-label="List view"]').trigger('click')
  await flushPromises()
}

// ── List mode (existing behaviour preserved) ──────────────────────────────────

describe('Beans — list mode', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders a table with beans from the API', async () => {
    stubFetch(beanList([bean('orderService', ['orderRepository']), bean('orderRepository')]))
    const wrapper = mountBeans()
    await openList(wrapper)

    expect(wrapper.text()).toContain('orderService')
    expect(wrapper.text()).toContain('orderRepository')
  })

  it('shows "No beans match" when the filtered list is empty', async () => {
    stubFetch({total: 5, beans: [], page: {total: 5, matched: 0, offset: 0, limit: 200, returned: 0, hasMore: false}})
    const wrapper = mountBeans()
    await openList(wrapper)

    expect(wrapper.text()).toContain('No beans match your filters')
  })

  it('shows total and matched counts in the subtitle', async () => {
    stubFetch({total: 42, beans: [], page: {total: 42, matched: 3, offset: 0, limit: 200, returned: 0, hasMore: false}})
    const wrapper = mountBeans()
    await openList(wrapper)

    expect(wrapper.text()).toContain('42 beans')
    expect(wrapper.text()).toContain('3 matched')
  })
})

// ── Graph mode toggle ─────────────────────────────────────────────────────────

describe('Beans — graph mode toggle', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses graph mode by default', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.find('[aria-label="Dependency graph"]').attributes('aria-pressed')).toBe('true')
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.find('input[placeholder*="Search for a bean"]').exists()).toBe(true)
  })

  it('keeps list mode available without selecting it by default', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.find('[aria-label="List view"]').attributes('aria-pressed')).toBe('false')
  })

  it('filters graph starting points to application beans by default', async () => {
    stubFetch(beanList([bean('applicationBean', ['bootUiBean']), bean('bootUiBean', [], 'BOOTUI')]))
    const wrapper = mountBeans()
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(wrapper.find('#beans-graph-classification').element.value).toBe('APPLICATION')
    expect(wrapper.findAll('datalist option').map((option) => option.element.value)).toEqual(['applicationBean'])
    expect(wrapper.text()).toContain('1 application')

    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('applicationBean')
    await input.trigger('change')
    expect(wrapper.find('[aria-label*="bootUiBean"]').exists()).toBe(false)
    const dependencyFact = wrapper
      .findAll('.bean-details__facts > div')
      .find((fact) => fact.find('dt').text() === 'Dependencies')
    expect(dependencyFact.find('dd').text()).toBe('0')

    await wrapper.find('#beans-graph-classification').setValue('')
    expect(wrapper.findAll('datalist option').map((option) => option.element.value)).toEqual([
      'applicationBean',
      'bootUiBean'
    ])
    expect(wrapper.find('[aria-label*="bootUiBean"]').exists()).toBe(true)
    expect(dependencyFact.find('dd').text()).toBe('1')
  })

  it('loads the unfiltered list only after list mode is selected', async () => {
    stubFetch(beanList([bean('applicationBean')]))
    const wrapper = mountBeans()
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(fetch.mock.calls.map(([input]) => String(input))).toEqual([expect.stringContaining('offset=0&limit=1000')])

    await openList(wrapper)
    const listRequest = fetch.mock.calls.map(([input]) => String(input)).find((url) => !url.includes('limit=1000'))
    expect(new URL(listRequest, 'http://localhost').searchParams.has('classification')).toBe(false)
  })

  it('returns to list mode without reloading the cached graph inventory', async () => {
    stubFetch(beanList([bean('a'), bean('b')]))
    const wrapper = mountBeans()
    await flushPromises()

    // Switch to graph mode
    await openGraph(wrapper)
    expect(wrapper.find('table').exists()).toBe(false)

    // Switch back to list mode
    await wrapper.find('[aria-label="List view"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(true)

    await openGraph(wrapper)
    const graphRequests = fetch.mock.calls.filter(([input]) => String(input).includes('limit=1000'))
    expect(graphRequests).toHaveLength(1)
  })
})

// ── Graph mode — search and focus ─────────────────────────────────────────────

describe('Beans — graph mode focus', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the search prompt before a bean is selected', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    expect(wrapper.text()).toContain('Choose a bean to inspect')
  })

  it('populates the datalist with sorted bean names after graph load', async () => {
    stubFetch(beanList([bean('zebra'), bean('alpha')]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    const options = wrapper.findAll('datalist option')
    const values = options.map((o) => o.attributes('value'))
    // Should be sorted
    expect(values).toEqual([...values].sort((a, b) => a.localeCompare(b)))
    expect(values).toContain('alpha')
    expect(values).toContain('zebra')
  })

  it('hides the search prompt and shows the graph once a valid bean name is entered', async () => {
    const beans = [bean('orderService', ['orderRepository']), bean('orderRepository')]
    stubFetch(beanList(beans))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    // Set focus bean via the search input
    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('orderService')
    await input.trigger('change')
    // Flush twice: once for the composable reactive update, once for the async component
    await flushPromises()
    await flushPromises()

    // The empty-state "search for a bean" prompt should no longer be visible
    expect(wrapper.text()).not.toContain('Search for a bean above')
    // No loading indicator either
    expect(wrapper.text()).not.toContain('Loading bean graph')
  })
})

// ── Graph mode — loading state ─────────────────────────────────────────────────

describe('Beans — graph loading state', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows a loading indicator while beans are being fetched for the graph', async () => {
    // Use a promise that never resolves to hold the loading state
    let resolveLoad
    vi.stubGlobal(
      'fetch',
      vi.fn((input) => {
        const url = typeof input === 'string' ? input : String(input)
        if (url.includes('limit=1000')) {
          return new Promise((res) => {
            resolveLoad = () => res({ok: true, status: 200, json: () => Promise.resolve(beanList([]))})
          })
        }
        return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(beanList([]))})
      })
    )

    const wrapper = mountBeans()
    await flushPromises()
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await vi.dynamicImportSettled()

    expect(wrapper.text()).toContain('Loading bean graph')

    // Resolve so the test cleanup is clean
    resolveLoad?.()
    await flushPromises()
  })

  it('shows the Quarkus reduced-fidelity explanation only on Quarkus', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans('quarkus')
    await flushPromises()
    await openGraph(wrapper)

    expect(wrapper.text()).toContain('Quarkus Arc does not expose inter-bean dependency relationships')
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('api/conditions?'), expect.anything())
  })

  it('shows a useful empty state when the graph inventory has no beans', async () => {
    stubFetch(beanList([bean('listBean')]), beanList([]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    expect(wrapper.text()).toContain('No beans available to graph')
    expect(wrapper.find('input[placeholder*="Search for a bean"]').exists()).toBe(false)
  })

  it('retries a failed graph inventory request', async () => {
    let graphAttempts = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((input) => {
        const url = typeof input === 'string' ? input : String(input)
        if (url.includes('limit=1000')) {
          graphAttempts += 1
          if (graphAttempts === 1) return Promise.resolve({ok: false, status: 500})
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(beanList([bean('recoveredBean')]))
          })
        }
        return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(beanList([bean('listBean')]))})
      })
    )
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    expect(wrapper.text()).toContain('Could not load beans for graph')
    const retry = wrapper.findAll('button').find((button) => button.text().includes('Retry'))
    await retry.trigger('click')
    await flushPromises()

    expect(graphAttempts).toBe(2)
    expect(wrapper.find('input[placeholder*="Search for a bean"]').exists()).toBe(true)
  })

  it('shows exact positive Conditions evidence for the focused bean resource', async () => {
    const orderService = {
      ...bean('orderService', ['orderRepository']),
      resource: 'class path resource [com/example/OrderAutoConfiguration.class]',
      aliases: ['orders']
    }
    stubFetch(beanList([orderService, bean('orderRepository')]), null, {
      positiveMatches: [
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration',
          condition: 'OnClassCondition',
          message: 'Required order classes were found.',
          outcome: 'MATCH'
        },
        {
          autoConfigurationClass: 'com.example.OtherAutoConfiguration',
          condition: 'OtherCondition',
          message: 'Mentions com.example.OrderAutoConfiguration only in text.',
          outcome: 'MATCH'
        },
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration#orderService',
          condition: 'OnBeanCondition',
          message: 'A supporting bean was found.',
          outcome: 'PARTIAL'
        }
      ]
    })
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('orderService')
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('Why this bean exists')
    expect(wrapper.text()).toContain('OnClassCondition')
    expect(wrapper.text()).toContain('Required order classes were found.')
    expect(wrapper.text()).toContain('PARTIAL')
    expect(wrapper.text()).not.toContain('OtherCondition')
  })

  it('counts all direct dependents even when the rendered graph reaches its node limit', async () => {
    const dependents = Array.from({length: 65}, (_, index) => bean(`dependent${index}`, ['focusBean']))
    stubFetch(beanList([bean('focusBean'), ...dependents]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('focusBean')
    await input.trigger('change')
    await flushPromises()

    const dependentFact = wrapper
      .findAll('.bean-details__facts > div')
      .find((fact) => fact.find('dt').text() === 'Dependents')
    expect(dependentFact.find('dd').text()).toBe('65')
    expect(wrapper.text()).toContain('Graph limited to 60 nodes')
  })

  it('does not invent condition provenance when the bean has no recorded resource', async () => {
    stubFetch(beanList([bean('orderService')]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('orderService')
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('No condition source can be established')
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('api/conditions?'), expect.anything())
  })

  it('focuses the only bean matching a type search on Enter', async () => {
    stubFetch(beanList([bean('orderService'), bean('orderRepository')]))
    const wrapper = mountBeans()
    await flushPromises()
    await openGraph(wrapper)

    const input = wrapper.find('input[placeholder*="Search for a bean"]')
    await input.setValue('OrderService')
    await input.trigger('keydown', {key: 'Enter'})
    await flushPromises()

    expect(input.element.value).toBe('orderService')
    expect(wrapper.text()).not.toContain('No loaded bean matches')
  })

  it('renders a missing dependency as informative but non-actionable', async () => {
    const summary = bean('orderService', ['externalDependency'])
    const wrapper = mount(BeanGraph, {
      props: {
        graph: {
          nodes: [
            {name: 'orderService', depth: 0, role: 'focus'},
            {name: 'externalDependency', depth: 1, role: 'dep'}
          ],
          edges: [{from: 'orderService', to: 'externalDependency'}],
          truncated: false
        },
        byName: new Map([['orderService', summary]]),
        definitionsByName: new Map([['orderService', [summary]]]),
        focusName: 'orderService'
      }
    })

    const missing = wrapper.find('[role="img"][aria-label^="externalDependency."]')
    expect(missing.exists()).toBe(true)
    expect(missing.attributes('tabindex')).toBe('-1')
    expect(missing.attributes('aria-label')).toContain('not present in the loaded bean inventory')
  })
})
