import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {describe, expect, it} from 'vitest'

const sourceRoot = path.dirname(fileURLToPath(import.meta.url))
const appSource = fs.readFileSync(path.join(sourceRoot, 'App.vue'), 'utf8')
const {descriptor} = parseSfc(appSource, {filename: 'App.vue'})
const css = descriptor.styles.map((style) => style.content).join('\n')
const commandPaletteSource = fs.readFileSync(path.join(sourceRoot, 'views/components/CommandPalette.vue'), 'utf8')
const {descriptor: commandPaletteDescriptor} = parseSfc(commandPaletteSource, {filename: 'CommandPalette.vue'})
const commandPaletteCss = commandPaletteDescriptor.styles.map((style) => style.content).join('\n')
const skinCss = (theme) => fs.readFileSync(path.join(sourceRoot, `assets/theme-${theme}.css`), 'utf8')

function cssBlock(marker, source = css) {
  const css = source
  const markerIndex = css.indexOf(marker)
  if (markerIndex < 0) throw new Error(`Missing CSS block: ${marker}`)
  const openingBrace = css.indexOf('{', markerIndex)
  let depth = 0
  for (let index = openingBrace; index < css.length; index += 1) {
    if (css[index] === '{') depth += 1
    if (css[index] === '}') depth -= 1
    if (depth === 0) return css.slice(openingBrace + 1, index)
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
  if (value.startsWith('#')) {
    let hex = value.slice(1)
    if (hex.length === 3 || hex.length === 4) {
      hex = [...hex].map((digit) => digit.repeat(2)).join('')
    }
    if (hex.length !== 6 && hex.length !== 8) throw new Error(`Unsupported hex color: ${value}`)
    return {
      red: Number.parseInt(hex.slice(0, 2), 16),
      green: Number.parseInt(hex.slice(2, 4), 16),
      blue: Number.parseInt(hex.slice(4, 6), 16),
      alpha: hex.length === 8 ? Number.parseInt(hex.slice(6, 8), 16) / 255 : 1
    }
  }

  const rgba = value.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$/)
  if (!rgba) throw new Error(`Unsupported CSS color: ${value}`)
  return {
    red: Number(rgba[1]),
    green: Number(rgba[2]),
    blue: Number(rgba[3]),
    alpha: rgba[4] === undefined ? 1 : Number(rgba[4])
  }
}

function composite(foreground, background) {
  const alpha = foreground.alpha + background.alpha * (1 - foreground.alpha)
  return {
    red: (foreground.red * foreground.alpha + background.red * background.alpha * (1 - foreground.alpha)) / alpha,
    green: (foreground.green * foreground.alpha + background.green * background.alpha * (1 - foreground.alpha)) / alpha,
    blue: (foreground.blue * foreground.alpha + background.blue * background.alpha * (1 - foreground.alpha)) / alpha,
    alpha
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
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

function backgroundStops(tokens) {
  return [...tokens['--bootui-bg-body'].matchAll(/#[\da-f]{6}/gi)].map((match) => parseColor(match[0]))
}

function relevantSurfaces(tokens) {
  const surface = parseColor(tokens['--bootui-surface'])
  const surfaceSolid = parseColor(tokens['--bootui-surface-solid'])
  const surfaceAlt = parseColor(tokens['--bootui-surface-alt'])
  const sidebar = parseColor(tokens['--bootui-sidebar-bg'])
  const selectedTint = parseColor(tokens['--bootui-nav-hover-bg'])
  const surfaces = []

  for (const [index, background] of backgroundStops(tokens).entries()) {
    const raised = composite(surface, background)
    const sunken = composite(surfaceAlt, raised)
    const inputOnBody = composite(surfaceAlt, background)
    const sidebarSurface = composite(sidebar, background)
    surfaces.push(
      [`body stop ${index + 1}`, background],
      [`raised surface on body stop ${index + 1}`, raised],
      [`sunken surface on body stop ${index + 1}`, sunken],
      [`input surface on body stop ${index + 1}`, inputOnBody],
      [`sidebar surface on body stop ${index + 1}`, sidebarSurface],
      [`selected raised surface on body stop ${index + 1}`, composite(selectedTint, raised)],
      [`selected sunken surface on body stop ${index + 1}`, composite(selectedTint, sunken)],
      [`selected sidebar surface on body stop ${index + 1}`, composite(selectedTint, sidebarSurface)]
    )
  }

  surfaces.push(
    ['solid card surface', surfaceSolid],
    ['selected solid card surface', composite(selectedTint, surfaceSolid)]
  )
  return surfaces
}

describe.each([
  ['light', lightTokens],
  ['dark', darkTokens]
])('%s theme text contrast', (_theme, tokens) => {
  it.each(['--bootui-text-muted', '--bootui-text-subtle'])('%s clears WCAG AA on every relevant surface', (token) => {
    const foreground = parseColor(tokens[token])
    const failures = relevantSurfaces(tokens)
      .map(([name, background]) => ({name, ratio: contrastRatio(foreground, background)}))
      .filter(({ratio}) => ratio < 4.5)

    expect(failures).toEqual([])
  })
})

it('routes standard and command-palette placeholders through the accessible subtle token', () => {
  expect(css).toMatch(/:global\(\.form-control::placeholder\)\s*\{[^}]*color:\s*var\(--bootui-text-subtle\)/s)
  expect(commandPaletteCss).toMatch(/\.cp-input::placeholder\s*\{[^}]*color:\s*var\(--bootui-text-subtle/s)
})

/* Every opt-in skin repaints the body layer, and several of them make it a bare
   backdrop that never carries copy. So rather than walking the default theme's
   gradient stops, each skin is held to the three surfaces that actually carry
   text: its solid card chrome, its input field, and its hovered nav row. Those
   three must be declared as opaque colors in the skin's own token block, which
   is also what makes this check possible without a browser. */
describe.each(['graphite', 'cyberpunk', 'dsfr', 'minimal', 'win95'])('%s theme text contrast', (theme) => {
  /* Resolved per test rather than once per file, so a skin that is mid-authoring
     fails only its own assertions instead of breaking collection for the rest. */
  function skin() {
    const tokens = declarations(cssBlock(`html[data-bootui-theme='${theme}'] {`, skinCss(theme)))
    const literal = (value) => parseColor(value.startsWith('var(') ? tokens[value.slice(4, -1)] : value)
    return {
      tokens,
      literal,
      surfaces: [
        ['card chrome', literal(tokens['--bootui-surface-solid'])],
        ['input field', literal(tokens['--bootui-surface-alt'])],
        ['hovered nav row', literal(tokens['--bootui-nav-hover-bg'])]
      ]
    }
  }

  it.each([
    ['--bootui-text', 4.5],
    ['--bootui-text-muted', 4.5],
    ['--bootui-text-subtle', 4.5],
    ['--bootui-danger-text', 4.5],
    ['--bootui-warning-text-strong', 4.5],
    ['--bootui-info-text', 4.5],
    ['--bootui-warning-text', 3]
  ])('%s clears its WCAG AA floor on every chrome surface', (token, floor) => {
    const {tokens, literal, surfaces} = skin()
    const foreground = literal(tokens[token])
    const failures = surfaces
      .map(([name, background]) => ({name, ratio: contrastRatio(foreground, background)}))
      .filter(({ratio}) => ratio < floor)

    expect(failures).toEqual([])
  })

  it('keeps machine output legible in its code pane', () => {
    const {tokens, literal} = skin()
    expect(
      contrastRatio(literal(tokens['--bootui-code-pane-text']), literal(tokens['--bootui-code-pane-bg']))
    ).toBeGreaterThanOrEqual(4.5)
  })
})

it('keeps the Windows 95 title-bar caption legible', () => {
  const tokens = declarations(cssBlock("html[data-bootui-theme='win95'] {", skinCss('win95')))
  expect(contrastRatio(parseColor('#ffffff'), parseColor(tokens['--w95-title']))).toBeGreaterThanOrEqual(4.5)
})
