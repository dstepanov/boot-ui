import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Cli from './Cli.vue'

const global = {stubs: {RouterLink: {template: '<a><slot /></a>'}}}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function cliStatus(overrides = {}) {
  return {
    enabled: true,
    serverName: 'bootui',
    serverVersion: 'dev',
    endpoint: '/bootui/api/cli',
    maxResults: 200,
    callCount: 4,
    totalLatencyMillis: 900,
    capacityRefusals: 0,
    timeouts: 0,
    toolCount: 2,
    tools: [
      {
        name: 'architecture_scan',
        command: 'architecture scan',
        description: 'Run the Architecture advisor.',
        panel: 'architecture',
        action: true,
        schema: 'NONE',
        arguments: [],
        panelEnabled: true,
        panelReadOnly: true
      },
      {
        name: 'get_beans',
        command: 'beans',
        description: 'Read the beans.',
        panel: 'beans',
        action: false,
        schema: 'QUERY_LIMIT',
        arguments: ['query', 'limit'],
        panelEnabled: false,
        panelReadOnly: false
      }
    ],
    ...overrides
  }
}

describe('Cli', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.querySelector('meta[name="bootui-api-path"]')?.remove()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows an unavailable state when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('Command-line endpoint status is unavailable')
  })

  it('renders the endpoint, explanation, and command catalog', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/cli', {})
    expect(wrapper.text()).toContain('Command-line access is')
    expect(wrapper.text()).toContain('enabled')
    expect(wrapper.text()).toContain('What this endpoint does')
    expect(wrapper.text()).toContain('architecture_scan')
    expect(wrapper.text()).toContain('get_beans')
    expect(wrapper.text()).toContain('/bootui/api/cli')
  })

  it('lists the command to type, with the tool it maps to as the secondary label', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('bootui architecture scan')
    expect(wrapper.text()).toContain('bootui beans')
    expect(wrapper.text()).toContain('architecture_scan')
    expect(wrapper.text()).toContain('get_beans')
  })

  it('spells out the arguments a command accepts the way the CLI does', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('--query --limit')
  })

  it('renders a required id as a positional rather than a flag', async () => {
    const status = cliStatus()
    status.tools[1].arguments = ['id']
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('<id>')
    expect(wrapper.text()).not.toContain('--id')
  })

  it('flags the backing panel state that explains a refusal', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('read-only')
    expect(wrapper.text()).toContain('panel disabled')
  })

  it('derives mean latency from the call counters', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('225 ms')
    expect(wrapper.text()).toContain('Mean latency')
  })

  it('shows no mean latency before the first call', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus({callCount: 0, totalLatencyMillis: 0}))))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('Mean latency')
    expect(wrapper.text()).not.toContain('NaN')
  })

  it('reports the disabled endpoint rather than offering a toggle', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(cliStatus({enabled: false, toolCount: 0, tools: []})))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('bootui.cli.enabled=false')
    expect(wrapper.text()).toContain('No commands are currently available')
    // The panel reports the endpoint, it never switches it: every request it makes is the status read.
    expect(fetchMock.mock.calls.every(([url, options]) => url === 'api/cli' && !options?.method)).toBe(true)
  })

  it('renders a copyable command pointed at this instance', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    const block = wrapper.get('.config-block').text()
    expect(block).toContain('jbang app install bootui@jdubois/boot-ui')
    expect(block).toContain('bootui --url ' + window.location.origin + ' tools')
    expect(block).not.toContain('--api-path')

    const copyButton = wrapper.findAll('button').find((b) => b.text().includes('Copy'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('--url')
  })

  it('spells out --api-path only when the API path is customised', async () => {
    const meta = document.createElement('meta')
    meta.setAttribute('name', 'bootui-api-path')
    meta.setAttribute('content', '/admin/api')
    document.head.appendChild(meta)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.get('.config-block').text()).toContain('--api-path /admin/api')
  })
})
