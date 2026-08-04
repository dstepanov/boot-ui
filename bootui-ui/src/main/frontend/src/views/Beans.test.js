import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import BeanGraph from './BeanGraph.vue'
import Beans from './Beans.vue'

// ── helpers ───────────────────────────────────────────────────────────────────

function bean(name, deps = []) {
  return {
    name,
    type: `com.example.${name}`,
    scope: 'singleton',
    classification: 'APPLICATION',
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
function stubFetch(listResponse, graphResponse = null) {
  const graphBody = graphResponse ?? listResponse
  vi.stubGlobal(
    'fetch',
    vi.fn((input) => {
      const url = typeof input === 'string' ? input : String(input)
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
        panels: {value: {platform}}
      }
    }
  })
}

// ── List mode (existing behaviour preserved) ──────────────────────────────────

describe('Beans — list mode', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders a table with beans from the API', async () => {
    stubFetch(beanList([bean('orderService', ['orderRepository']), bean('orderRepository')]))
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.text()).toContain('orderService')
    expect(wrapper.text()).toContain('orderRepository')
  })

  it('shows "No beans match" when the filtered list is empty', async () => {
    stubFetch({total: 5, beans: [], page: {total: 5, matched: 0, offset: 0, limit: 200, returned: 0, hasMore: false}})
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.text()).toContain('No beans match your filters')
  })

  it('shows total and matched counts in the subtitle', async () => {
    stubFetch({total: 42, beans: [], page: {total: 42, matched: 3, offset: 0, limit: 200, returned: 0, hasMore: false}})
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.text()).toContain('42 beans')
    expect(wrapper.text()).toContain('3 matched')
  })
})

// ── Graph mode toggle ─────────────────────────────────────────────────────────

describe('Beans — graph mode toggle', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('has a graph-mode toggle button', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans()
    await flushPromises()

    // The graph button icon should be present
    expect(wrapper.find('[aria-label="Dependency graph"]').attributes('aria-pressed')).toBe('false')
  })

  it('has a list-mode toggle button', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans()
    await flushPromises()

    expect(wrapper.find('[aria-label="List view"]').attributes('aria-pressed')).toBe('true')
  })

  it('switches to graph mode on graph-button click and shows the focus search', async () => {
    stubFetch(beanList([bean('a', ['b']), bean('b')]))
    const wrapper = mountBeans()
    await flushPromises()

    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

    // The focus search input should appear
    expect(wrapper.find('input[placeholder*="Search for a bean"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Dependency graph"]').attributes('aria-pressed')).toBe('true')
    // The bean table should be gone
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('returns to list mode when list button is clicked', async () => {
    stubFetch(beanList([bean('a'), bean('b')]))
    const wrapper = mountBeans()
    await flushPromises()

    // Switch to graph mode
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(false)

    // Switch back to list mode
    await wrapper.find('[aria-label="List view"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(true)
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
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Search for a bean above')
  })

  it('populates the datalist with sorted bean names after graph load', async () => {
    stubFetch(beanList([bean('zebra'), bean('alpha')]))
    const wrapper = mountBeans()
    await flushPromises()
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

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
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

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
    // Do NOT flush promises — the graph load is pending

    expect(wrapper.text()).toContain('Loading bean graph')

    // Resolve so the test cleanup is clean
    resolveLoad?.()
    await flushPromises()
  })

  it('shows the Quarkus reduced-fidelity explanation only on Quarkus', async () => {
    stubFetch(beanList([bean('a')]))
    const wrapper = mountBeans('quarkus')
    await flushPromises()
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Quarkus Arc does not expose inter-bean dependency relationships')
  })

  it('focuses the only bean matching a type search on Enter', async () => {
    stubFetch(beanList([bean('orderService'), bean('orderRepository')]))
    const wrapper = mountBeans()
    await flushPromises()
    await wrapper.find('[aria-label="Dependency graph"]').trigger('click')
    await flushPromises()

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
