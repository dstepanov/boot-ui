import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Ai from './Ai.vue'

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('Ai', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('uses native buttons and aria-sort for sortable table headers', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 2,
              errorCount: 0,
              totalInputTokens: 120,
              totalOutputTokens: 30,
              averageDurationNanos: 1_000_000,
              tokensByModel: {zeta: 100, alpha: 50},
              callsByModel: {zeta: 1, alpha: 5},
              recent: []
            })
          )
        }
        return Promise.resolve(jsonResponse({buckets: []}))
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const modelHeader = wrapper.findAll('th').find((header) => header.text().trim() === 'Model')
    const sortButton = modelHeader.get('button.sort-button')
    expect(sortButton.element.tagName).toBe('BUTTON')
    expect(modelHeader.attributes('aria-sort')).toBe('none')

    await sortButton.trigger('click')
    expect(modelHeader.attributes('aria-sort')).toBe('descending')
    expect(wrapper.get('table tbody tr code').text()).toBe('zeta')

    await sortButton.trigger('click')
    expect(modelHeader.attributes('aria-sort')).toBe('ascending')
    expect(wrapper.get('table tbody tr code').text()).toBe('alpha')
    expect(wrapper.get('[role="progressbar"][aria-label="alpha token share"]').attributes('aria-valuetext')).toBe(
      '50% of tokens'
    )
  })
})
