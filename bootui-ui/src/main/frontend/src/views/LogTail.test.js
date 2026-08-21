import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LogTail from './LogTail.vue'

class EventSourceStub {
  static instances = []

  constructor(url) {
    this.url = url
    this.listeners = new Map()
    EventSourceStub.instances.push(this)
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener)
  }

  close() {}
}

describe('Log Tail', () => {
  afterEach(() => {
    EventSourceStub.instances = []
    vi.unstubAllGlobals()
  })

  it('uses the shared panel header and exposes streamed lines as a live log', async () => {
    vi.stubGlobal('EventSource', EventSourceStub)

    const wrapper = mount(LogTail)
    await flushPromises()

    expect(wrapper.get('.panel-header__title').text()).toBe('Log Tail')
    expect(EventSourceStub.instances).toHaveLength(1)

    const log = wrapper.get('[role="log"]')
    expect(log.attributes('aria-live')).toBe('polite')
    expect(log.attributes('aria-relevant')).toBe('additions')

    EventSourceStub.instances[0].listeners.get('log')({
      data: JSON.stringify({
        timestamp: '2026-08-21T10:00:00Z',
        level: 'INFO',
        logger: 'com.example.Application',
        message: 'Started'
      })
    })
    await flushPromises()

    expect(log.text()).toContain('com.example.Application')
    expect(log.text()).toContain('Started')
  })
})
