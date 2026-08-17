import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import HealthNode from './HealthNode.vue'

const nestedTree = {
  name: 'application',
  status: 'UP',
  details: {},
  components: [
    {
      name: 'db',
      status: 'UP',
      details: {database: 'PostgreSQL'},
      components: [{name: 'primary', status: 'UP', details: {validationQuery: 'isValid()'}, components: []}]
    }
  ]
}

describe('HealthNode', () => {
  // The component renders itself for every child, so using `.card` as its root
  // would nest a card inside a card at every depth of the health tree.
  it('never renders a card, so recursion cannot nest cards', () => {
    const wrapper = mount(HealthNode, {props: {node: nestedTree}})

    expect(wrapper.findAll('.card')).toHaveLength(0)
    expect(wrapper.findAll('.health-node').length).toBeGreaterThan(2)
  })

  it('labels the detail and component groups in sentence case', () => {
    const wrapper = mount(HealthNode, {props: {node: nestedTree}})
    const labels = wrapper.findAll('.health-node__section-label').map((label) => label.text())

    expect(labels).toContain('Details')
    expect(labels).toContain('Components')
    expect(wrapper.html()).not.toContain('text-uppercase')
  })

  it('renders contributor names in the monospace stack', () => {
    const wrapper = mount(HealthNode, {props: {node: nestedTree}})
    const names = wrapper.findAll('.health-node__name').map((name) => name.text())

    expect(names).toEqual(['application', 'db', 'primary'])
  })

  it('tints nested contributors so depth stays readable', () => {
    const wrapper = mount(HealthNode, {props: {node: nestedTree}})

    expect(wrapper.find('.health-node').classes()).not.toContain('health-node--nested')
    expect(wrapper.findAll('.health-node--nested').length).toBe(2)
  })
})
