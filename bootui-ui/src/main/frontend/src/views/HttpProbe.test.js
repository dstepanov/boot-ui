import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import HttpProbe from './HttpProbe.vue'

const {apiFetch, confirm} = vi.hoisted(() => ({apiFetch: vi.fn(), confirm: vi.fn()}))

vi.mock('../api.js', () => ({apiFetch}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))

function deferred() {
  let resolve
  const promise = new Promise((resolver) => {
    resolve = resolver
  })
  return {promise, resolve}
}

function response(overrides = {}) {
  return {
    status: 200,
    statusText: 'OK',
    headers: {},
    body: 'response',
    durationMs: 12,
    error: null,
    truncated: false,
    ...overrides
  }
}

describe('HttpProbe', () => {
  beforeEach(() => {
    apiFetch.mockReset()
    confirm.mockReset()
    confirm.mockResolvedValue(true)
  })

  it('describes the Spring Boot app in the subtitle by default', () => {
    const wrapper = mount(HttpProbe)
    expect(wrapper.text()).toContain('running Spring Boot app')
  })

  it('describes the Quarkus app when the platform is quarkus', () => {
    const wrapper = mount(HttpProbe, {
      global: {provide: {panels: ref({platform: 'quarkus'})}}
    })
    expect(wrapper.text()).toContain('running Quarkus app')
    expect(wrapper.text()).not.toContain('Spring Boot')
  })

  it('warns when the response body was truncated', async () => {
    apiFetch.mockResolvedValue({json: async () => response({body: 'partial response', truncated: true})})
    const wrapper = mount(HttpProbe)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Response body was truncated at the configured byte limit.')
    expect(wrapper.text()).toContain('partial response')
    expect(confirm).not.toHaveBeenCalled()
  })

  it.each(['GET', 'HEAD'])('sends safe %s probes without confirmation', async (method) => {
    apiFetch.mockResolvedValue({json: async () => response()})
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue(method)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    expect(apiFetch).toHaveBeenCalledOnce()
    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toMatchObject({method})
  })

  it('waits for confirmation before sending an unsafe probe', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    apiFetch.mockResolvedValue({json: async () => response()})
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue('POST')
    await wrapper.get('input').setValue('/api/orders')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith({
      title: 'Send POST request?',
      message: 'This request may change state in your running app. Review the method and path before continuing.',
      resource: 'POST /api/orders',
      confirmLabel: 'Send POST',
      danger: true
    })
    expect(apiFetch).not.toHaveBeenCalled()

    confirmation.resolve(true)
    await flushPromises()

    expect(apiFetch).toHaveBeenCalledOnce()
    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toMatchObject({
      method: 'POST',
      path: '/api/orders'
    })
  })

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('cancels unsafe %s probes without sending a request', async (method) => {
    confirm.mockResolvedValueOnce(false)
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue(method)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('routes Enter through one confirmation without bypassing or duplicating the request', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue('PATCH')
    const pathInput = wrapper.get('input')
    await pathInput.setValue('/api/orders/1')

    await pathInput.trigger('keyup', {key: 'Enter'})
    await pathInput.trigger('keyup', {key: 'Enter'})
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(apiFetch).not.toHaveBeenCalled()

    confirmation.resolve(false)
    await flushPromises()

    expect(apiFetch).not.toHaveBeenCalled()
  })
})
