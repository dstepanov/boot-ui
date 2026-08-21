import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import {useCopyToClipboard} from './useCopyToClipboard'

function harness() {
  let api
  const wrapper = mount({
    setup() {
      api = useCopyToClipboard(50)
      return () => null
    }
  })
  return {wrapper, api}
}

describe('useCopyToClipboard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.assign(navigator, {clipboard: {writeText: vi.fn().mockResolvedValue()}})
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('writes text and sets the copied key, then clears it', async () => {
    const {api} = harness()
    expect(await api.copyToClipboard('hello', 'k1')).toBe(true)
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('hello')
    expect(api.copiedKey.value).toBe('k1')
    vi.advanceTimersByTime(50)
    expect(api.copiedKey.value).toBe(null)
  })

  it('falls back to the text when no key is given', async () => {
    const {api} = harness()
    await api.copyToClipboard('plain')
    expect(api.copiedKey.value).toBe('plain')
  })

  it('leaves the indicator unchanged and reports failure when the clipboard rejects', async () => {
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('denied'))
    const {api} = harness()
    expect(await api.copyToClipboard('nope', 'k')).toBe(false)
    expect(api.copiedKey.value).toBe(null)
  })

  it('reports failure when the clipboard API is unavailable', async () => {
    Object.assign(navigator, {clipboard: undefined})
    const {api} = harness()
    expect(await api.copyToClipboard('nope', 'k')).toBe(false)
    expect(api.copiedKey.value).toBe(null)
  })
})
