import {describe, expect, it} from 'vitest'

import {
  buildDocumentTitle,
  createPanelLookup,
  resolveRouteTitle,
  routeAvailabilityLabel,
  routeNavigationGroup,
  routePanelState,
  routeUnavailable
} from './panelNavigation.js'

const springRoute = {
  name: 'spring',
  meta: {
    group: 'advisors',
    title: 'Spring',
    titleByPlatform: {quarkus: 'Quarkus'}
  }
}

describe('panel navigation', () => {
  it('resolves platform-aware labels and browser titles with safe fallbacks', () => {
    expect(resolveRouteTitle(springRoute, 'spring-boot')).toBe('Spring')
    expect(resolveRouteTitle(springRoute, 'quarkus')).toBe('Quarkus')
    expect(buildDocumentTitle(springRoute, 'quarkus', 'orders')).toBe('Quarkus · orders · BootUI')
    expect(buildDocumentTitle({}, null, '  ')).toBe('BootUI · Application · BootUI')
  })

  it('moves disabled and unavailable panels into the same explicit group used by the sidebar', () => {
    const lookup = createPanelLookup({
      panels: [
        {
          id: 'spring',
          available: true,
          enabled: false
        }
      ]
    })

    expect(routeUnavailable(springRoute, lookup)).toBe(true)
    expect(routeNavigationGroup(springRoute, lookup)).toBe('Disabled / unavailable')
    expect(routePanelState(springRoute, lookup)).toMatchObject({
      kind: 'disabled',
      label: 'Disabled',
      icon: 'bi-slash-circle'
    })
    expect(routeAvailabilityLabel(springRoute, lookup, 'quarkus')).toBe(
      'Quarkus - disabled: Panel is disabled via bootui.panels.spring.enabled=false'
    )
  })
})
