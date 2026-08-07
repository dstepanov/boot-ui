// @ts-check
import {expect, test} from './fixtures.js'

const chatSpanId = '1111111111111111'

function parseColor(value) {
  const channels = value.match(/[\d.]+/g)?.map(Number)
  if (!channels || channels.length < 3) throw new Error(`Unsupported computed color: ${value}`)
  return {red: channels[0], green: channels[1], blue: channels[2]}
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
  return (
    (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) /
    (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
  )
}

const overview = {
  enabled: true,
  springAiDetected: true,
  langChain4jDetected: false,
  totalChats: 1,
  totalInputTokens: 42,
  totalOutputTokens: 7,
  tokensByModel: {'qwen2.5:0.5b': 49},
  callsByModel: {'qwen2.5:0.5b': 1},
  toolCallCount: 1,
  vectorOperationCount: 1,
  embeddingCount: 0,
  recent: [
    {
      traceId: '0123456789abcdef0123456789abcdef',
      spanId: chatSpanId,
      startEpochNanos: Date.now() * 1_000_000,
      durationNanos: 250_000_000,
      provider: 'ollama',
      requestModel: 'qwen2.5:0.5b',
      responseModel: 'qwen2.5:0.5b',
      inputTokens: 42,
      outputTokens: 7,
      totalTokens: 49,
      finishReason: 'stop',
      statusCode: 'OK',
      operation: 'chat',
      toolCallCount: 1,
      vectorOperationCount: 1
    }
  ],
  contentBanner: 'Prompt and completion text is not captured by default.'
}

const tokenSeries = {
  minutes: 60,
  buckets: [
    {epochMinute: 100, inputTokens: 0, outputTokens: 0, callCount: 0},
    {epochMinute: 101, inputTokens: 42, outputTokens: 7, callCount: 1}
  ]
}

const detail = {
  summary: overview.recent[0],
  toolCalls: [
    {
      spanId: '2222222222222222',
      name: 'getWeather',
      startEpochNanos: overview.recent[0].startEpochNanos + 5_000_000,
      durationNanos: 20_000_000,
      statusCode: 'OK'
    }
  ],
  vectorOperations: [
    {
      spanId: '3333333333333333',
      operation: 'query',
      collectionName: 'docs',
      startEpochNanos: overview.recent[0].startEpochNanos + 10_000_000,
      durationNanos: 15_000_000,
      statusCode: 'OK'
    }
  ],
  attributes: [{key: 'gen_ai.system', type: 'string', value: 'ollama'}],
  events: [],
  contentCaptured: false,
  contentBanner: 'Message content is not on this span.'
}

test.describe('AI Framework view', () => {
  test('renders AI token usage, model breakdowns, and chat detail', async ({openView, page}) => {
    await stubAi(page, overview)

    await openView('ai', /AI Framework/)
    await expect(page.getByText('Spring AI detected')).toBeVisible()
    await expect(page.locator('.kpi-card-body', {hasText: 'Total tokens'}).getByText('49', {exact: true})).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Usage by model'}).getByText('qwen2.5:0.5b')).toBeVisible()
    await expect(page.getByText('Token usage (last 60 min)')).toBeVisible()

    await page.getByRole('button', {name: 'Toggle chat details'}).click()
    await expect(page.locator('.card', {hasText: `Chat ${chatSpanId}`})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('getWeather', {exact: true})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('docs', {exact: true})).toBeVisible()
    await page.locator('.chat-detail-row summary', {hasText: 'gen_ai'}).click()
    await expect(page.locator('.chat-detail-row').getByText('gen_ai.system')).toBeVisible()
  })

  test('applies accessible chart tokens in light and dark themes', async ({openView, page}) => {
    await stubAi(page, overview)
    const renderedThemes = []

    for (const theme of ['light', 'dark']) {
      await page.goto('/bootui/')
      await page.evaluate((value) => localStorage.setItem('bootui.theme', value), theme)
      await page.reload()
      await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme)
      await openView('ai', /AI Framework/)

      const chart = page.getByRole('img', {name: /Token usage over the last/})
      await chart.hover({position: {x: 500, y: 20}})
      const tooltip = page.locator('.ai-chart-tooltip')
      await expect(tooltip).toBeVisible()

      const colors = await tooltip.evaluate((tooltipElement) => {
        const rootStyle = getComputedStyle(document.documentElement)
        const resolveToken = (name) => {
          const probe = document.createElement('span')
          probe.style.color = rootStyle.getPropertyValue(name)
          document.body.append(probe)
          const color = getComputedStyle(probe).color
          probe.remove()
          return color
        }
        return {
          axis: getComputedStyle(document.querySelector('.ai-chart-axis-label')).fill,
          axisToken: resolveToken('--bootui-chart-axis'),
          input: getComputedStyle(tooltipElement.querySelector('.ai-chart-tooltip-input')).color,
          output: getComputedStyle(tooltipElement.querySelector('.ai-chart-tooltip-output')).color,
          calls: getComputedStyle(tooltipElement.querySelector('.ai-chart-tooltip-calls')).color,
          tooltipText: getComputedStyle(tooltipElement).color,
          tooltipBackground: getComputedStyle(tooltipElement).backgroundColor,
          selection: getComputedStyle(document.querySelector('.ai-chart-selection')).stroke,
          selectionToken: resolveToken('--bootui-chart-selection'),
          surface: resolveToken('--bootui-surface-solid')
        }
      })

      expect(colors.axis).toBe(colors.axisToken)
      expect(colors.selection).toBe(colors.selectionToken)
      expect(contrastRatio(colors.axis, colors.surface)).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(colors.selection, colors.surface)).toBeGreaterThanOrEqual(3)
      for (const foreground of [colors.tooltipText, colors.input, colors.output, colors.calls]) {
        expect(contrastRatio(foreground, colors.tooltipBackground)).toBeGreaterThanOrEqual(4.5)
      }
      renderedThemes.push(colors)
    }

    expect(renderedThemes[1].tooltipBackground).not.toBe(renderedThemes[0].tooltipBackground)
    expect(renderedThemes[1].axis).not.toBe(renderedThemes[0].axis)
  })

  test('shows disabled mode when telemetry is unavailable', async ({page}) => {
    await stubAi(page, {
      ...overview,
      enabled: false,
      springAiDetected: false,
      langChain4jDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('Enable BootUI telemetry capture', {exact: true})).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })

  test('shows ready empty state before the first chat is recorded', async ({page}) => {
    await stubAi(page, {
      ...overview,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      toolCallCount: 0,
      vectorOperationCount: 0,
      embeddingCount: 0,
      recent: []
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('No AI chat completions recorded yet')).toBeVisible()
    await expect(page.getByText('Telemetry ready')).toBeVisible()
    await expect(page.getByText('Enable BootUI telemetry capture')).toHaveCount(0)
    await expect(page.getByText('OTLP exporter configured')).toHaveCount(0)
  })

  test('shows unavailable mode when no AI framework is on the classpath', async ({page}) => {
    await stubAi(page, {
      ...overview,
      springAiDetected: false,
      langChain4jDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('Spring AI or LangChain4j on classpath')).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })

  test('keeps useful overview data visible when token history is partial', async ({openView, page}) => {
    await stubAi(page, overview, {tokenStatus: 503})

    await openView('ai', /AI Framework/)

    await expect(page.getByText('Partial AI usage data.')).toBeVisible()
    await expect(page.getByText(/Token history could not be refreshed \(HTTP 503\)/)).toBeVisible()
    await expect(page.getByText(/Overview data remains available/)).toBeVisible()
    await expect(page.locator('.kpi-card-body', {hasText: 'Total tokens'}).getByText('49', {exact: true})).toBeVisible()
    await expect(page.getByText('Token usage (last 60 min)')).toHaveCount(0)
  })
})

async function stubAi(page, overviewResponse, {tokenStatus = 200} = {}) {
  await stubShell(
    page,
    overviewResponse.enabled && (overviewResponse.springAiDetected || overviewResponse.langChain4jDetected)
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/overview',
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(overviewResponse)})
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/tokens',
    async (route) => {
      await route.fulfill({
        status: tokenStatus,
        contentType: 'application/json',
        body: JSON.stringify(tokenStatus === 200 ? tokenSeries : {})
      })
    }
  )
  await page.route(
    (url) => url.pathname === `/bootui/api/ai/chats/${chatSpanId}`,
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(detail)})
    }
  )
}

async function stubShell(page, aiAvailable) {
  await page.route(
    (url) => url.pathname === '/bootui/api/overview',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          bootUiVersion: 'test',
          applicationName: 'bootui-sample',
          frameworkName: 'Spring Boot',
          frameworkVersion: '4.0.6',
          javaVersion: '25',
          javaVendor: 'test',
          activeProfiles: ['dev'],
          defaultProfiles: ['default'],
          webApplicationType: 'SERVLET',
          serverPort: 8080,
          managementPort: null,
          contextPath: '',
          startupTimeMillis: 1000,
          activation: {enabled: true, localhostOnly: true, reason: 'test', warnings: []},
          openApiUrl: null
        })
      })
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/panels',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          panels: [
            {
              id: 'ai',
              title: 'AI Framework',
              available: aiAvailable,
              unavailableReason: aiAvailable ? null : 'AI usage unavailable in this test state'
            }
          ]
        })
      })
    }
  )
}
