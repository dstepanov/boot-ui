import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {useRoute} from 'vue-router'

import WebSockets from './WebSockets.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

vi.mock('vue-router', () => ({useRoute: vi.fn(() => ({query: {}}))}))

const emptyStats = {
  endpoints: 0,
  openSessions: 0,
  closedSessions: 0,
  subscriptions: 0,
  inboundFrames: 0,
  outboundFrames: 0,
  inboundBytes: 0,
  outboundBytes: 0,
  failedFrames: 0,
  capturedActivity: 0,
  evictedActivity: 0
}

const emptyReport = {
  available: true,
  unavailableReason: null,
  framework: 'Spring WebSocket',
  capturing: true,
  frameCaptureSupported: true,
  frameCaptureUnavailableReason: null,
  sessionTrackingSupported: true,
  sessionTrackingUnavailableReason: null,
  brokerPrefixes: [],
  applicationDestinationPrefixes: [],
  userDestinationPrefix: null,
  maxEndpoints: 200,
  maxSessions: 200,
  maxSubscriptions: 500,
  maxActivityEntries: 500,
  endpointsTruncated: false,
  sessionsTruncated: false,
  subscriptionsTruncated: false,
  endpoints: [],
  sessions: [],
  subscriptions: [],
  activity: [],
  stats: emptyStats,
  warnings: []
}

function populatedReport(overrides = {}) {
  return {
    ...emptyReport,
    brokerPrefixes: ['/topic', '/queue'],
    applicationDestinationPrefixes: ['/app'],
    userDestinationPrefix: '/user/',
    endpoints: [
      {
        id: 'stomp:/ws',
        path: '/ws',
        kind: 'STOMP',
        handlerClass: 'org.springframework.web.socket.messaging.SubProtocolWebSocketHandler',
        subprotocols: ['v12.stomp'],
        sockJs: true,
        allowedOrigins: ['http://localhost:8080'],
        interceptors: [],
        callbacks: [
          {
            type: 'MESSAGE_MAPPING',
            destination: '/app/chat',
            declaringClass: 'ChatController',
            method: 'chat',
            messageType: null
          }
        ],
        openSessions: 1,
        inboundProcessingMode: null,
        captureInstalled: true
      },
      {
        id: 'handler:/echo',
        path: '/echo',
        kind: 'HANDLER',
        handlerClass: 'com.example.EchoHandler',
        subprotocols: [],
        sockJs: false,
        allowedOrigins: [],
        interceptors: [],
        callbacks: [],
        openSessions: 0,
        inboundProcessingMode: null,
        captureInstalled: false
      }
    ],
    sessions: [
      {
        id: 'ab12cd34ef56ab78',
        endpointId: 'stomp:/ws',
        path: '/ws',
        open: true,
        openedAt: 1700000000000,
        lastActivityAt: 1700000001000,
        subprotocol: 'v12.stomp',
        remoteAddress: '127.0.0.1',
        localAddress: '127.0.0.1:8080',
        messagesIn: 4,
        messagesOut: 2,
        bytesIn: 512,
        bytesOut: 128,
        closeStatus: null
      }
    ],
    subscriptions: [
      {
        id: 'sub-0',
        endpointId: 'stomp:/ws',
        sessionId: 'ab12cd34ef56ab78',
        destination: '/topic/orders',
        subscribedAt: 1700000000500
      }
    ],
    activity: [
      {
        id: 2,
        timestamp: 1700000001000,
        endpointId: 'stomp:/ws',
        sessionId: 'ab12cd34ef56ab78',
        direction: 'OUTBOUND',
        frameType: 'TEXT',
        destination: '/topic/orders',
        payloadBytes: 64,
        durationMillis: null,
        success: true,
        errorCategory: null
      },
      {
        id: 1,
        timestamp: 1700000000800,
        endpointId: 'stomp:/ws',
        sessionId: 'ab12cd34ef56ab78',
        direction: 'INBOUND',
        frameType: 'SUBSCRIBE',
        destination: '/topic/orders',
        payloadBytes: null,
        durationMillis: null,
        success: false,
        errorCategory: 'java.lang.IllegalStateException'
      }
    ],
    stats: {
      ...emptyStats,
      endpoints: 2,
      openSessions: 1,
      subscriptions: 1,
      inboundFrames: 4,
      outboundFrames: 2,
      inboundBytes: 512,
      outboundBytes: 128
    },
    ...overrides
  }
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('WebSockets panel', () => {
  let wrapper
  const tabFor = (label) => wrapper.findAll('[role="tab"]').find((button) => button.text().startsWith(label))

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest reports WebSockets unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(WebSockets, {
      props: {
        panel: {
          id: 'websockets',
          enabled: true,
          available: false,
          unavailableReason: 'Spring WebSocket is not on the classpath'
        }
      }
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('WebSockets are unavailable')
    expect(wrapper.text()).toContain('Spring WebSocket is not on the classpath')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('renders endpoint topology and never renders a message payload', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('/ws')
    expect(text).toContain('STOMP')
    expect(text).toContain('SockJS')
    expect(text).toContain('/echo')
    expect(text).toContain('/topic, /queue')
    expect(text).toContain('/app')
    expect(text).toContain('Message payloads are never read or stored')
    expect(text).not.toContain('payloadPreview')
    expect(text).not.toContain('body')
    expect(wrapper.findComponent(PanelHeader).props('lastFetchedLabel')).toBe('Snapshot')
    expect(wrapper.findComponent(PanelHeader).props('autoRefreshTitle')).toBe(
      'Refresh when WebSocket activity changes while this tab is visible'
    )
  })

  it('marks endpoints without a capture seam as metadata only', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets)
    await flushPromises()

    const badges = wrapper.findAll('.badge').map((badge) => badge.text())
    expect(badges).toContain('installed')
    expect(badges).toContain('metadata')
  })

  it('explains honestly when the stack cannot capture frames and hides the capture toggle', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          populatedReport({
            framework: 'Quarkus WebSockets Next',
            capturing: false,
            frameCaptureSupported: false,
            frameCaptureUnavailableReason: 'Quarkus WebSockets Next exposes no message interception SPI.'
          })
        )
      )
    )
    wrapper = mount(WebSockets)
    await flushPromises()

    expect(wrapper.text()).toContain('Quarkus WebSockets Next exposes no message interception SPI.')
    expect(wrapper.text()).toContain('metadata only')
    expect(wrapper.findAll('button.btn-outline-warning')).toHaveLength(0)
    expect(wrapper.findAll('button.btn-outline-success')).toHaveLength(0)
  })

  it('distinguishes an unsupported stack from an idle one in the empty tables', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...emptyReport,
          framework: 'Spring WebFlux WebSocket',
          frameCaptureSupported: false,
          frameCaptureUnavailableReason: 'The STOMP broker seams are servlet-only.',
          sessionTrackingSupported: false,
          sessionTrackingUnavailableReason: 'Spring WebFlux exposes no session registry.'
        })
      )
    )
    wrapper = mount(WebSockets)
    await flushPromises()

    await tabFor('Sessions').trigger('click')
    expect(wrapper.text()).toContain('exposes no seam for observing live sessions')
    expect(wrapper.text()).not.toContain('No session open yet')

    await tabFor('Activity').trigger('click')
    expect(wrapper.text()).toContain('exposes no frame capture seam')
  })

  it('says nothing has happened yet rather than blaming the stack when tracking works', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyReport)))
    wrapper = mount(WebSockets)
    await flushPromises()

    await tabFor('Sessions').trigger('click')
    expect(wrapper.text()).toContain('No session open yet')

    await tabFor('Activity').trigger('click')
    expect(wrapper.text()).toContain('No frame captured yet')
  })

  it('keeps Clear usable when only closed-session history remains to discard', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...emptyReport,
          stats: {...emptyStats, closedSessions: 3}
        })
      )
    )
    wrapper = mount(WebSockets)
    await flushPromises()

    const clear = wrapper.findAll('button').find((button) => button.text().includes('Clear'))
    expect(clear.attributes('disabled')).toBeUndefined()
  })

  it('switches between the endpoint, session, subscription and activity tabs', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets)
    await flushPromises()

    await tabFor('Sessions').trigger('click')
    expect(wrapper.text()).toContain('ab12cd34ef56ab78')
    expect(wrapper.text()).toContain('127.0.0.1')

    await tabFor('Subscriptions').trigger('click')
    expect(wrapper.text()).toContain('/topic/orders')

    await tabFor('Activity').trigger('click')
    expect(wrapper.text()).toContain('SUBSCRIBE')
    const errorBadge = wrapper.findAll('.badge').find((badge) => badge.text() === 'java.lang.IllegalStateException')
    expect(errorBadge, 'the failure category is readable text, not a hover-only title').toBeTruthy()

    await wrapper.get('select.websockets-direction-select').setValue('OUTBOUND')
    expect(wrapper.text()).not.toContain('SUBSCRIBE')
  })

  it('exposes keyboard-operable tab semantics', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets, {attachTo: document.body})
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(4)
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.get('[role="tabpanel"]').attributes('aria-labelledby')).toBe('websockets-tab-endpoints')

    await tabs[0].trigger('keydown', {key: 'ArrowRight'})

    expect(wrapper.get('#websockets-tab-sessions').attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(wrapper.get('#websockets-tab-sessions').element)
    expect(wrapper.get('[role="tabpanel"]').attributes('aria-labelledby')).toBe('websockets-tab-sessions')
  })

  it('filters endpoints and explains when nothing matches', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets)
    await flushPromises()

    await wrapper.get('input.websockets-filter-input').setValue('echo')
    expect(wrapper.text()).toContain('/echo')
    expect(wrapper.text()).not.toContain('SubProtocolWebSocketHandler')

    await wrapper.get('input.websockets-filter-input').setValue('missing')
    expect(wrapper.text()).toContain('No WebSocket endpoint matches your filter')
  })

  it('toggles frame capture through the capture action', async () => {
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/websockets/capture') {
        return Promise.resolve(jsonResponse(populatedReport({capturing: JSON.parse(init.body).enabled})))
      }
      return Promise.resolve(jsonResponse(populatedReport()))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(WebSockets)
    await flushPromises()

    await wrapper.get('button.btn-outline-warning').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/websockets/capture',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
    expect(wrapper.text()).toContain('Frame capture paused')
  })

  it('clears captured activity when confirmed', async () => {
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/websockets' && init?.method === 'DELETE') {
        return Promise.resolve(jsonResponse(emptyReport))
      }
      return Promise.resolve(jsonResponse(populatedReport()))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(WebSockets)
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/websockets', {method: 'DELETE'})
    expect(wrapper.text()).toContain('Cleared captured WebSocket activity')
  })

  it('blocks capture and clear locally in read-only mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(populatedReport()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(WebSockets, {
      props: {
        panel: {
          id: 'websockets',
          enabled: true,
          available: true,
          readOnly: true,
          readOnlyReason: 'WebSocket actions are read-only'
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Frame capture and clearing are read-only')
    expect(wrapper.get('button.btn-outline-danger').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button.btn-outline-warning').attributes('disabled')).toBeDefined()
    expect(fetchMock).not.toHaveBeenCalledWith('api/websockets', {method: 'DELETE'})
  })

  it('refreshes on an SSE update and closes the stream on unmount', async () => {
    const sources = []
    class MockEventSource {
      constructor() {
        this.listeners = {}
        this.close = vi.fn()
        sources.push(this)
      }
      addEventListener(type, listener) {
        this.listeners[type] = listener
      }
      emit(type) {
        this.listeners[type]?.()
      }
    }
    vi.stubGlobal('EventSource', MockEventSource)
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(populatedReport()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(WebSockets)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    sources[0].emit('update')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    const source = sources[0]
    wrapper.unmount()
    wrapper = null
    expect(source.close).toHaveBeenCalledOnce()
  })

  it('prefills the filter from a Live Activity deep link', async () => {
    vi.mocked(useRoute).mockReturnValueOnce({query: {q: 'echo'}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(populatedReport())))
    wrapper = mount(WebSockets)
    await flushPromises()

    expect(wrapper.get('input.websockets-filter-input').element.value).toBe('echo')
    expect(wrapper.text()).toContain('/echo')
  })
})
