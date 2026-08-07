import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import PanelSkeleton from './PanelSkeleton.vue'

describe('PanelSkeleton', () => {
  it('announces the specific content that is loading', () => {
    const wrapper = mount(PanelSkeleton, {props: {label: 'Loading HTTP mappings…', rows: 3}})

    expect(wrapper.attributes()).toMatchObject({
      'aria-busy': 'true',
      'aria-label': 'Loading HTTP mappings…'
    })
    expect(wrapper.findAll('.skeleton-line')).toHaveLength(4)
  })
})
