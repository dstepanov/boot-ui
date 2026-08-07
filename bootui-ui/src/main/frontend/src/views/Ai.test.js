import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Ai from './Ai.vue'
import FlashBanner from './components/FlashBanner.vue'

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function deferred() {
  let resolve
  const promise = new Promise((res) => {
    resolve = res
  })
  return {promise, resolve}
}

function overviewWithChats() {
  return {
    enabled: true,
    springAiDetected: true,
    langChain4jDetected: false,
    totalChats: 2,
    errorCount: 0,
    totalInputTokens: 30,
    totalOutputTokens: 12,
    tokensByModel: {},
    callsByModel: {},
    recent: [
      {spanId: 'first', provider: 'test', requestModel: 'first-model', statusCode: 'OK'},
      {spanId: 'second', provider: 'test', requestModel: 'second-model', statusCode: 'OK'}
    ]
  }
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

  it('ignores delayed token JSON after the selected time window changes', async () => {
    const staleSeries = deferred()
    let token60Requests = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') {
          token60Requests += 1
          if (token60Requests === 1) {
            return Promise.resolve(
              jsonResponse({
                minutes: 60,
                buckets: [{epochMinute: 1, inputTokens: 1, outputTokens: 1, callCount: 1}]
              })
            )
          }
          return Promise.resolve({ok: true, status: 200, json: () => staleSeries.promise})
        }
        if (url === 'api/ai/tokens?minutes=15') {
          return Promise.resolve(
            jsonResponse({
              minutes: 15,
              buckets: [{epochMinute: 1, inputTokens: 2, outputTokens: 1, callCount: 1}]
            })
          )
        }
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()
    await wrapper.get('select[aria-label="Token usage time window"]').setValue('15')
    await flushPromises()
    expect(wrapper.text()).toContain('Token usage (last 15 min)')

    staleSeries.resolve({
      minutes: 60,
      buckets: [{epochMinute: 1, inputTokens: 100, outputTokens: 50, callCount: 10}]
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Token usage (last 15 min)')
    expect(wrapper.text()).not.toContain('Token usage (last 60 min)')
  })

  it('ignores a stale chat detail response after another chat is selected', async () => {
    const firstDetail = deferred()
    const secondDetail = deferred()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') return Promise.resolve(jsonResponse({buckets: []}))
        if (url === 'api/ai/chats/first') return firstDetail.promise
        if (url === 'api/ai/chats/second') return secondDetail.promise
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const detailButtons = wrapper.findAll('button[aria-label="Toggle chat details"]')
    await detailButtons[0].trigger('click')
    await detailButtons[1].trigger('click')

    secondDetail.resolve(jsonResponse({summary: {requestModel: 'second-detail'}}))
    await flushPromises()
    expect(wrapper.text()).toContain('second-detail')

    firstDetail.resolve(jsonResponse({summary: {requestModel: 'stale-first-detail'}}))
    await flushPromises()
    expect(wrapper.text()).toContain('second-detail')
    expect(wrapper.text()).not.toContain('stale-first-detail')
  })

  it('does not reopen chat detail after the drawer is closed while loading', async () => {
    const firstDetail = deferred()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/ai/overview') return Promise.resolve(jsonResponse(overviewWithChats()))
        if (url === 'api/ai/tokens?minutes=60') return Promise.resolve(jsonResponse({buckets: []}))
        if (url === 'api/ai/chats/first') return firstDetail.promise
        throw new Error(`Unexpected URL: ${url}`)
      })
    )

    wrapper = mount(Ai)
    await flushPromises()

    const detailButton = wrapper.findAll('button[aria-label="Toggle chat details"]')[0]
    await detailButton.trigger('click')
    await detailButton.trigger('click')

    firstDetail.resolve(jsonResponse({summary: {requestModel: 'closed-detail'}}))
    await flushPromises()
    expect(wrapper.text()).not.toContain('closed-detail')
    expect(wrapper.find('.chat-detail-row').exists()).toBe(false)
  })
})
