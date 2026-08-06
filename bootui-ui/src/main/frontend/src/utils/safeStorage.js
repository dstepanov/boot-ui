const STORAGE_ERROR_NAMES = new Set(['InvalidStateError', 'NotSupportedError', 'QuotaExceededError', 'SecurityError'])

function isStorageAccessError(error) {
  if (!error || typeof error !== 'object') return false
  const isDomException =
    (typeof DOMException !== 'undefined' && error instanceof DOMException) ||
    Object.prototype.toString.call(error) === '[object DOMException]'
  return isDomException && STORAGE_ERROR_NAMES.has(error.name)
}

function handleStorageError(error, fallback) {
  if (isStorageAccessError(error)) return fallback
  throw error
}

export function createSafeStorage(storageProvider) {
  const memory = new Map()
  const dirtyKeys = new Set()

  function storage() {
    try {
      return storageProvider?.() ?? null
    } catch (error) {
      return handleStorageError(error, null)
    }
  }

  function getItem(key) {
    const fallback = memory.get(key) ?? null
    if (dirtyKeys.has(key)) return fallback

    const target = storage()
    if (!target) return fallback

    try {
      const value = target.getItem(key)
      if (value === null) memory.delete(key)
      else memory.set(key, value)
      return value
    } catch (error) {
      return handleStorageError(error, fallback)
    }
  }

  function setItem(key, value) {
    const normalizedValue = String(value)
    memory.set(key, normalizedValue)

    const target = storage()
    if (!target) {
      dirtyKeys.add(key)
      return false
    }

    try {
      target.setItem(key, normalizedValue)
      dirtyKeys.delete(key)
      return true
    } catch (error) {
      dirtyKeys.add(key)
      return handleStorageError(error, false)
    }
  }

  function removeItem(key) {
    memory.delete(key)

    const target = storage()
    if (!target) {
      dirtyKeys.add(key)
      return false
    }

    try {
      target.removeItem(key)
      dirtyKeys.delete(key)
      return true
    } catch (error) {
      dirtyKeys.add(key)
      return handleStorageError(error, false)
    }
  }

  function getJson(key, fallback) {
    const raw = getItem(key)
    if (raw === null) return fallback

    try {
      return JSON.parse(raw)
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error
      removeItem(key)
      return fallback
    }
  }

  function setJson(key, value) {
    return setItem(key, JSON.stringify(value))
  }

  function getBoolean(key, fallback = false) {
    const raw = getItem(key)
    if (raw === 'true') return true
    if (raw === 'false') return false
    if (raw !== null) removeItem(key)
    return fallback
  }

  return {getBoolean, getItem, getJson, removeItem, setItem, setJson}
}

export const safeLocalStorage = createSafeStorage(() => (typeof window === 'undefined' ? null : window.localStorage))
