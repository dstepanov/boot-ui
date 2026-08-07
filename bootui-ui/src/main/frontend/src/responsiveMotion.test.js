import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseTemplate} from '@vue/compiler-dom'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {describe, expect, it} from 'vitest'

const sourceRoot = path.dirname(fileURLToPath(import.meta.url))

function vueFiles(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return vueFiles(entryPath)
    return entry.name.endsWith('.vue') ? [entryPath] : []
  })
}

function staticClasses(node) {
  const classAttribute = node.props?.find((property) => property.type === 6 && property.name === 'class')
  return new Set(classAttribute?.value?.content.split(/\s+/).filter(Boolean) ?? [])
}

function uncontainedTables(template, lineOffset = 0) {
  const issues = []
  const ast = parseTemplate(template)

  function visit(node, ancestors = []) {
    if (node.type === 1) {
      if (node.tag === 'table' && !ancestors.some((ancestor) => staticClasses(ancestor).has('table-responsive'))) {
        issues.push(`line ${lineOffset + node.loc.start.line}: table has no responsive scroll container`)
      }
      node.children.forEach((child) => visit(child, [...ancestors, node]))
      return
    }
    node.children?.forEach((child) => visit(child, ancestors))
  }

  visit(ast)
  return issues
}

function cssBlock(source, header) {
  const headerIndex = source.indexOf(header)
  const openingBrace = source.indexOf('{', headerIndex)
  if (headerIndex < 0 || openingBrace < 0) return null

  let depth = 0
  for (let index = openingBrace; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') depth -= 1
    if (depth === 0) return source.slice(headerIndex, index + 1)
  }
  return null
}

describe('responsive and motion contracts', () => {
  it('contains every data table in an owned responsive scroll region', () => {
    const issues = vueFiles(sourceRoot).flatMap((file) => {
      const {descriptor} = parseSfc(fs.readFileSync(file, 'utf8'), {filename: file})
      if (!descriptor.template) return []
      return uncontainedTables(descriptor.template.content, descriptor.template.loc.start.line - 1).map(
        (issue) => `${path.relative(sourceRoot, file)}:${issue}`
      )
    })

    expect(issues).toEqual([])
  })

  it('uses selective reduced-motion rules instead of blanket near-zero timings', () => {
    const sources = vueFiles(sourceRoot).map((file) => fs.readFileSync(file, 'utf8'))
    const combined = sources.join('\n')
    const appSource = fs.readFileSync(path.join(sourceRoot, 'App.vue'), 'utf8')

    expect(combined).not.toMatch(/0\.0*1ms/)
    expect(appSource).not.toMatch(/\*\s*,\s*\*::before\s*,\s*\*::after/)
    expect(appSource).toContain(':global(.spinner-border)')
    expect(appSource).toContain(':global(.progress-bar)')
    expect(appSource).toContain('scroll-behavior: auto !important')
  })

  it('keeps desktop density while defining mobile-only 44px interaction targets', () => {
    const appSource = fs.readFileSync(path.join(sourceRoot, 'App.vue'), 'utf8')
    const mobileRules = cssBlock(appSource, '@media (max-width: 575.98px)')

    expect(mobileRules).not.toBeNull()
    expect(mobileRules).toContain('min-block-size: 44px')
    expect(mobileRules).toContain('min-inline-size: 44px')
    expect(appSource.replace(mobileRules, '')).not.toContain('44px')
  })
})
