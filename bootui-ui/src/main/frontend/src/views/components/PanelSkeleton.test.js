import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import PanelSkeleton from './PanelSkeleton.vue'

const componentRoot = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(componentRoot, 'PanelSkeleton.vue'), 'utf8')
const {descriptor} = parseSfc(source, {filename: 'PanelSkeleton.vue'})
const css = descriptor.styles.map((style) => style.content).join('\n')

describe('PanelSkeleton', () => {
  it('announces the specific content that is loading', () => {
    const wrapper = mount(PanelSkeleton, {props: {label: 'Loading HTTP mappings…', rows: 3}})

    expect(wrapper.attributes()).toMatchObject({
      'aria-busy': 'true',
      'aria-label': 'Loading HTTP mappings…'
    })
    expect(wrapper.findAll('.skeleton-line')).toHaveLength(4)
  })

  it('keeps a visible static gradient when reduced motion is requested', () => {
    const reducedMotionRule = css.match(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/)?.[1]

    expect(reducedMotionRule).toContain('animation: none')
    expect(reducedMotionRule).toContain('background-position: 50% 0')
  })
})
