import {describe, expect, it, vi} from 'vitest'

import {createSafeStorage} from './safeStorage.js'

function storageException(name = 'SecurityError') {
  return new DOMException('Browser storage is unavailable', name)
}

describe('safe storage', () => {
  it('degrades to memory when the localStorage getter is denied or storage is missing', () => {
    const denied = createSafeStorage(() => {
      throw storageException()
    })
    const missing = createSafeStorage(() => null)

    expect(denied.getItem('key')).toBeNull()
    expect(denied.setItem('key', 'value')).toBe(false)
    expect(denied.getItem('key')).toBe('value')
    expect(denied.removeItem('key')).toBe(false)
    expect(denied.getItem('key')).toBeNull()

    expect(missing.setItem('key', 'value')).toBe(false)
    expect(missing.getItem('key')).toBe('value')
  })

  it('keeps the last current-page value when reads and writes start failing', () => {
    const values = new Map([['key', 'persisted']])
    let denyReads = false
    let denyWrites = false
    const target = {
      getItem(key) {
        if (denyReads) throw storageException()
        return values.get(key) ?? null
      },
      setItem(key, value) {
        if (denyWrites) throw storageException('QuotaExceededError')
        values.set(key, value)
      },
      removeItem(key) {
        if (denyWrites) throw storageException()
        values.delete(key)
      }
    }
    const storage = createSafeStorage(() => target)

    expect(storage.getItem('key')).toBe('persisted')
    denyReads = true
    expect(storage.getItem('key')).toBe('persisted')

    denyReads = false
    denyWrites = true
    expect(storage.setItem('key', 'in-memory')).toBe(false)
    expect(storage.getItem('key')).toBe('in-memory')
    expect(values.get('key')).toBe('persisted')
  })

  it('reports persistence only after storage recovers', () => {
    const values = new Map()
    let unavailable = true
    const target = {
      getItem: (key) => values.get(key) ?? null,
      setItem(key, value) {
        if (unavailable) throw storageException('QuotaExceededError')
        values.set(key, value)
      },
      removeItem(key) {
        if (unavailable) throw storageException()
        values.delete(key)
      }
    }
    const storage = createSafeStorage(() => target)

    expect(storage.setItem('key', 'first')).toBe(false)
    expect(storage.getItem('key')).toBe('first')

    unavailable = false
    expect(storage.setItem('key', 'second')).toBe(true)
    expect(values.get('key')).toBe('second')
    expect(storage.removeItem('key')).toBe(true)
    expect(values.has('key')).toBe(false)
  })

  it('falls back for malformed JSON and boolean values even when cleanup is denied', () => {
    const target = {
      getItem: vi.fn((key) => (key === 'json' ? '{bad' : 'sometimes')),
      setItem: vi.fn(),
      removeItem: vi.fn(() => {
        throw storageException()
      })
    }
    const storage = createSafeStorage(() => target)

    expect(storage.getJson('json', {default: true})).toEqual({default: true})
    expect(storage.getBoolean('boolean', true)).toBe(true)
    expect(target.removeItem).toHaveBeenCalledTimes(2)
  })

  it('does not swallow unrelated application errors', () => {
    const getterError = new Error('programming error')
    const methodError = new TypeError('broken adapter')

    expect(() =>
      createSafeStorage(() => {
        throw getterError
      }).getItem('key')
    ).toThrow(getterError)
    expect(() =>
      createSafeStorage(() => ({
        getItem() {
          throw methodError
        }
      })).getItem('key')
    ).toThrow(methodError)
  })
})
