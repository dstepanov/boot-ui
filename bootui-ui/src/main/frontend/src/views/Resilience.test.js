import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

const routeQuery = {}

vi.mock('vue-router', () => ({useRoute: () => ({query: routeQuery})}))

import Resilience from './Resilience.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function metrics(overrides = {}) {
  return {
    successfulCalls: null,
    failedCalls: null,
    retriedCalls: null,
    rejectedCalls: null,
    timeoutCalls: null,
    shortCircuitedCalls: null,
    failureRatePercent: null,
    bufferedCalls: null,
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    resiliencePresent: true,
    unavailableReason: null,
    captureEnabled: true,
    providers: ['RESILIENCE4J'],
    totalPolicies: 2,
    policies: [
      {
        name: 'paymentGateway',
        type: 'CIRCUIT_BREAKER',
        provider: 'RESILIENCE4J',
        source: 'REGISTRY',
        target: 'com.example.PaymentClient#charge',
        state: 'OPEN',
        settings: [
          {name: 'failureRateThreshold', value: '50', provenance: 'CONFIGURED'},
          {name: 'slidingWindowSize', value: '100', provenance: 'DEFAULT'}
        ],
        metrics: metrics({successfulCalls: 12, failedCalls: 3, failureRatePercent: 20})
      },
      {
        name: 'inventoryRetry',
        type: 'RETRY',
        provider: 'SPRING_RETRY',
        source: 'ANNOTATION',
        target: 'com.example.InventoryService#reserve',
        state: null,
        settings: [{name: 'maxAttempts', value: '3', provenance: 'ANNOTATION'}],
        metrics: metrics()
      }
    ],
    policyCountsByType: {CIRCUIT_BREAKER: 1, RETRY: 1},
    events: [
      {
        id: 'r-1',
        timestamp: '2024-05-01T10:15:30Z',
        policyName: 'inventoryRetry',
        policyType: 'RETRY',
        provider: 'SPRING_RETRY',
        target: 'com.example.InventoryService#reserve',
        outcome: 'RETRY',
        attempt: 2,
        durationMillis: 42,
        failureCategory: 'java.net.SocketTimeoutException',
        state: null,
        traceId: null
      }
    ],
    maxEvents: 200,
    warnings: [],
    ...overrides
  }
}

describe('Resilience', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    routeQuery.q = undefined
  })

  it('renders the policy inventory, live breaker state and captured events', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('paymentGateway')
    expect(text).toContain('OPEN')
    expect(text).toContain('inventoryRetry')
    expect(text).toContain('com.example.InventoryService#reserve')
    expect(text).toContain('java.net.SocketTimeoutException')
    expect(text).toContain('RESILIENCE4J')
  })

  it('states honestly when a library exposes no counters instead of showing zeros', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.text()).toContain('Not exposed by this library')
  })

  it('explains that the panel is unavailable when no resilience library is present', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            resiliencePresent: false,
            unavailableReason: 'No supported resilience library is present',
            captureEnabled: false,
            providers: [],
            totalPolicies: 0,
            policies: [],
            policyCountsByType: {},
            events: [],
            maxEvents: 200,
            warnings: []
          })
        )
      )
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.text()).toContain('Resilience is unavailable.')
    expect(wrapper.text()).toContain('No supported resilience library is present')
  })

  it('says event capture is disabled while still listing policies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report({captureEnabled: false, events: []}))))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.text()).toContain('Event capture is currently disabled')
    expect(wrapper.text()).toContain('paymentGateway')
  })

  it('surfaces backend warnings without hiding the rest of the report', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report({warnings: ['Resilience4j registry read failed']}))))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.text()).toContain('Resilience4j registry read failed')
    expect(wrapper.text()).toContain('paymentGateway')
  })

  it('prefills the filter box from the ?q= deep-link query parameter', async () => {
    routeQuery.q = 'paymentGateway'
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.get('input.resilience-filter-input').element.value).toBe('paymentGateway')
    expect(wrapper.text()).toContain('paymentGateway')
    expect(wrapper.text()).not.toContain('com.example.InventoryService#reserve · ')
  })

  it('filters the policy table by type', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    await wrapper.get('select.resilience-type-select').setValue('RETRY')

    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('1 / 2 policies')
  })

  it('promises that no arguments, payloads or exception messages are recorded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Resilience)
    await flushPromises()

    expect(wrapper.text()).toContain(
      'never records method arguments, return values, payloads or raw exception messages'
    )
  })
})
