import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Ai from './Ai.vue'

const viewsRoot = path.dirname(fileURLToPath(import.meta.url))
const appSource = fs.readFileSync(path.join(viewsRoot, '../App.vue'), 'utf8')
const {descriptor: appDescriptor} = parseSfc(appSource, {filename: 'App.vue'})
const appCss = appDescriptor.styles.map((style) => style.content).join('\n')

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function cssBlock(marker) {
  const markerIndex = appCss.indexOf(marker)
  if (markerIndex < 0) throw new Error(`Missing CSS block: ${marker}`)
  const openingBrace = appCss.indexOf('{', markerIndex)
  let depth = 0
  for (let index = openingBrace; index < appCss.length; index += 1) {
    if (appCss[index] === '{') depth += 1
    if (appCss[index] === '}') depth -= 1
    if (depth === 0) return appCss.slice(openingBrace + 1, index)
  }
  throw new Error(`Unclosed CSS block: ${marker}`)
}

function declarations(block) {
  return Object.fromEntries(
    [...block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((match) => [match[1], match[2].trim()])
  )
}

const lightTokens = declarations(cssBlock(':global(:root) {'))
const darkTokens = {...lightTokens, ...declarations(cssBlock(":global(:root[data-bootui-theme='dark']) {"))}

function parseColor(value) {
  const hex = value.slice(1)
  return {
    red: Number.parseInt(hex.slice(0, 2), 16),
    green: Number.parseInt(hex.slice(2, 4), 16),
    blue: Number.parseInt(hex.slice(4, 6), 16)
  }
}

function linearChannel(channel) {
  const srgb = channel / 255
  return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
}

function relativeLuminance(color) {
  return 0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)
}

function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(parseColor(foreground))
  const backgroundLuminance = relativeLuminance(parseColor(background))
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

function aiOverview() {
  return {
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
  }
}

function tokenSeries() {
  return {
    minutes: 60,
    buckets: [
      {epochMinute: 30_000_000, inputTokens: 40, outputTokens: 10, callCount: 1},
      {epochMinute: 30_000_001, inputTokens: 80, outputTokens: 20, callCount: 2}
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
          return Promise.resolve(jsonResponse(aiOverview()))
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

  it('renders chart colors through BootUI theme tokens', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(url === 'api/ai/overview' ? aiOverview() : tokenSeries())))
    )

    wrapper = mount(Ai)
    await flushPromises()

    expect(wrapper.get('.ai-chart-grid').attributes('stroke')).toBe('var(--bootui-chart-grid)')
    expect(wrapper.get('.ai-chart-grid').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('.ai-chart-axis-label').map((label) => label.attributes('fill'))).toEqual([
      'var(--bootui-chart-axis)',
      'var(--bootui-chart-axis)',
      'var(--bootui-chart-axis)'
    ])
    expect(wrapper.get('.ai-chart-input-area').attributes('fill')).toBe('var(--bootui-chart-input)')
    expect(wrapper.get('.ai-chart-output-area').attributes('fill')).toBe('var(--bootui-chart-output)')
    expect(wrapper.get('.ai-chart-output-line').attributes('stroke')).toBe('var(--bootui-chart-output)')
    expect(wrapper.get('.ai-chart-calls-line').attributes('stroke')).toBe('var(--bootui-chart-calls)')

    const chartContainer = wrapper.get('.ai-chart-container')
    chartContainer.element.getBoundingClientRect = () => ({left: 0, width: 600})
    await chartContainer.trigger('mousemove', {clientX: 600})

    expect(wrapper.get('.ai-chart-selection').attributes('stroke')).toBe('var(--bootui-chart-selection)')
    expect(wrapper.get('.ai-chart-selection').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('.ai-chart-tooltip').classes()).not.toContain('bg-white')
    expect(wrapper.get('.ai-chart-tooltip-input').text()).toBe('In: 80')
    expect(wrapper.get('.ai-chart-tooltip-output').text()).toBe('Out: 20')
    expect(wrapper.get('.ai-chart-tooltip-calls').text()).toBe('Calls: 2')
  })

  it.each([
    ['light', lightTokens],
    ['dark', darkTokens]
  ])('keeps meaningful chart text AA-compliant in the %s theme', (_theme, tokens) => {
    const tooltipBackground = tokens['--bootui-chart-tooltip-bg']
    const textPairs = [
      [tokens['--bootui-chart-axis'], tokens['--bootui-surface-solid']],
      [tokens['--bootui-chart-tooltip-text'], tooltipBackground],
      [tokens['--bootui-chart-input'], tooltipBackground],
      [tokens['--bootui-chart-output'], tooltipBackground],
      [tokens['--bootui-chart-calls'], tooltipBackground]
    ]

    for (const [foreground, background] of textPairs) {
      expect(contrastRatio(foreground, background)).toBeGreaterThanOrEqual(4.5)
    }
    expect(contrastRatio(tokens['--bootui-chart-selection'], tokens['--bootui-surface-solid'])).toBeGreaterThanOrEqual(
      3
    )
  })
})
