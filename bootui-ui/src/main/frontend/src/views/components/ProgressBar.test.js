import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import ProgressBar from './ProgressBar.vue'

describe('ProgressBar', () => {
  it('exposes a labelled progress range and clamps the visual fill', () => {
    const wrapper = mount(ProgressBar, {
      props: {label: 'Heap memory used', value: 125, valueText: '125% reported'}
    })

    const progress = wrapper.get('[role="progressbar"]')
    expect(progress.attributes()).toMatchObject({
      'aria-label': 'Heap memory used',
      'aria-valuemin': '0',
      'aria-valuemax': '100',
      'aria-valuenow': '100',
      'aria-valuetext': '125% reported'
    })
    expect(progress.get('.progress-bar').attributes('style')).toContain('width: 100%')
  })

  it('can preserve a minimum visible fill without changing the semantic value', () => {
    const wrapper = mount(ProgressBar, {
      props: {label: 'Tool share', value: 1, minVisiblePercent: 4}
    })

    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('1')
    expect(wrapper.get('.progress-bar').attributes('style')).toContain('width: 4%')
  })
})
