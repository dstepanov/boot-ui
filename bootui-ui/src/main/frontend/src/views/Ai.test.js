import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Ai from './Ai.vue'
import FlashBanner from './components/FlashBanner.vue'

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

  it('keeps overview data visible and announces partial token history', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 1,
              totalInputTokens: 42,
              totalOutputTokens: 7,
              tokensByModel: {'qwen2.5:0.5b': 49},
              callsByModel: {'qwen2.5:0.5b': 1},
              recent: []
            })
          )
        }
        return Promise.resolve({ok: false, status: 503, json: () => Promise.resolve({})})
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.text()).toContain('Total tokens')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Partial AI usage data')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Token history could not be refreshed (HTTP 503)')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Overview data remains available')
    expect(wrapper.getComponent(FlashBanner).find('button.btn-close').exists()).toBe(false)
  })

  it('treats a rejected token request as partial when the overview succeeds', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') {
          return Promise.resolve(
            jsonResponse({
              enabled: true,
              springAiDetected: true,
              langChain4jDetected: false,
              totalChats: 1,
              totalInputTokens: 42,
              totalOutputTokens: 7,
              tokensByModel: {},
              callsByModel: {},
              recent: []
            })
          )
        }
        return Promise.reject(new TypeError('Failed to fetch'))
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.text()).toContain('Total tokens')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Partial AI usage data')
    expect(wrapper.getComponent(FlashBanner).text()).toContain('Token history could not be refreshed')
    expect(wrapper.text()).not.toContain('Unable to load AI usage data')
  })
})
