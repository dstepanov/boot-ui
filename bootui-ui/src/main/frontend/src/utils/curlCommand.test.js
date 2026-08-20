import {describe, expect, it} from 'vitest'

import {
  buildCurlCommand,
  encodeUrlText,
  placeholderQuery,
  safeCurlHeaders,
  shellQuote,
  QUERY_VALUE_PLACEHOLDER,
  SAFE_REQUEST_HEADERS
} from './curlCommand.js'

function exchange(overrides = {}) {
  return {
    id: 'exchange-1',
    method: 'GET',
    path: '/api/orders',
    query: null,
    uri: 'http://localhost:8080/api/orders',
    requestHeaders: [],
    ...overrides
  }
}

describe('shellQuote', () => {
  it('wraps plain text in single quotes', () => {
    expect(shellQuote('plain')).toBe("'plain'")
  })

  it('splices embedded single quotes so the word cannot be closed early', () => {
    expect(shellQuote("it's")).toBe(`'it'\\''s'`)
  })

  it('contains metacharacters, newlines and command substitutions', () => {
    expect(shellQuote('a; rm -rf /')).toBe("'a; rm -rf /'")
    expect(shellQuote('$(id) `id` ${HOME}')).toBe("'$(id) `id` ${HOME}'")
    expect(shellQuote('line\nnext')).toBe("'line\nnext'")
  })

  it('quotes empty and nullish values', () => {
    expect(shellQuote('')).toBe("''")
    expect(shellQuote(null)).toBe("''")
    expect(shellQuote(undefined)).toBe("''")
  })
})

describe('encodeUrlText', () => {
  it('leaves already-encoded text alone', () => {
    expect(encodeUrlText('/caf%C3%A9/orders')).toBe('/caf%C3%A9/orders')
  })

  it('percent-encodes control characters, spaces, quotes and fragments', () => {
    expect(encodeUrlText('a b')).toBe('a%20b')
    expect(encodeUrlText("a'b")).toBe('a%27b')
    expect(encodeUrlText('a\r\nb')).toBe('a%0D%0Ab')
    expect(encodeUrlText('a#b')).toBe('a%23b')
    expect(encodeUrlText('a"b`c\\d')).toBe('a%22b%60c%5Cd')
  })

  it('encodes non-ASCII text as UTF-8, including astral characters', () => {
    expect(encodeUrlText('é')).toBe('%C3%A9')
    expect(encodeUrlText('🚀')).toBe('%F0%9F%9A%80')
  })
})

describe('placeholderQuery', () => {
  it('returns nothing for missing or empty queries', () => {
    expect(placeholderQuery(null)).toEqual({query: '', count: 0, omitted: 0})
    expect(placeholderQuery('')).toEqual({query: '', count: 0, omitted: 0})
    expect(placeholderQuery('?')).toEqual({query: '', count: 0, omitted: 0})
  })

  it('keeps every name and replaces every value', () => {
    expect(placeholderQuery('page=2&size=50').query).toBe(
      `page=${QUERY_VALUE_PLACEHOLDER}&size=${QUERY_VALUE_PLACEHOLDER}`
    )
  })

  it('preserves repeated, empty, valueless and encoded parameters', () => {
    const {query, count} = placeholderQuery('tag=a&tag=b&empty=&bare&caf%C3%A9=x')
    expect(query).toBe('tag=VALUE&tag=VALUE&empty=VALUE&bare&caf%C3%A9=VALUE')
    expect(count).toBe(5)
  })

  it('drops malformed segments that carry no parameter name', () => {
    expect(placeholderQuery('&&=orphan&keep=1')).toEqual({query: 'keep=VALUE', count: 1, omitted: 1})
  })

  it('drops a valueless segment that could be a bare token rather than a parameter name', () => {
    const {query, count, omitted} = placeholderQuery(
      'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.c2lnbmF0dXJlLXZhbHVl&ok'
    )
    expect(query).toBe('ok')
    expect(count).toBe(1)
    expect(omitted).toBe(1)
  })

  it('never copies a masked parameter name', () => {
    expect(placeholderQuery('******&keep=1')).toEqual({query: 'keep=VALUE', count: 1, omitted: 1})
  })

  it('never copies a recorded value, masked or not', () => {
    expect(placeholderQuery('token=super-secret&other=******').query).toBe('token=VALUE&other=VALUE')
  })

  it('encodes metacharacters and newlines smuggled into a parameter name', () => {
    expect(placeholderQuery("a'b=1&c\nd=2").query).toBe('a%27b=VALUE&c%0Ad=VALUE')
  })
})

describe('safeCurlHeaders', () => {
  it('returns nothing when there are no headers', () => {
    expect(safeCurlHeaders(null)).toEqual({headers: [], omitted: 0, omittedValues: 0})
    expect(safeCurlHeaders([])).toEqual({headers: [], omitted: 0, omittedValues: 0})
  })

  it('keeps allowlisted headers and canonicalizes their names', () => {
    const {headers, omitted} = safeCurlHeaders([
      {name: 'content-type', values: ['application/json'], masked: false},
      {name: 'ACCEPT', values: ['*/*'], masked: false}
    ])
    expect(headers).toEqual([
      {name: 'Accept', value: '*/*'},
      {name: 'Content-Type', value: 'application/json'}
    ])
    expect(omitted).toBe(0)
  })

  it('omits credentials, cookies, forwarding, tracing, api-key and unknown headers', () => {
    const sensitive = [
      'Authorization',
      'Proxy-Authorization',
      'Cookie',
      'X-Api-Key',
      'X-Forwarded-For',
      'Forwarded',
      'traceparent',
      'X-B3-TraceId',
      'X-Custom-Thing'
    ].map((name) => ({name, values: ['recorded-value'], masked: false}))

    const {headers, omitted} = safeCurlHeaders(sensitive)

    expect(headers).toEqual([])
    expect(omitted).toBe(sensitive.length)
  })

  it('omits allowlisted headers whose values are masked or withheld', () => {
    const {headers, omitted} = safeCurlHeaders([
      {name: 'Accept', values: ['application/json'], masked: true},
      {name: 'Content-Type', values: ['******'], masked: false},
      {name: 'User-Agent', values: [], masked: false}
    ])
    expect(headers).toEqual([])
    expect(omitted).toBe(3)
  })

  it('omits values carrying control characters instead of quoting a smuggled header', () => {
    const {headers, omitted} = safeCurlHeaders([{name: 'Accept', values: ['ok\r\nX-Evil: 1'], masked: false}])
    expect(headers).toEqual([])
    expect(omitted).toBe(1)
  })

  it('keeps every repeated value of an allowlisted header in recorded order', () => {
    const {headers} = safeCurlHeaders([{name: 'Accept', values: ['text/html', 'application/json'], masked: false}])
    expect(headers).toEqual([
      {name: 'Accept', value: 'text/html'},
      {name: 'Accept', value: 'application/json'}
    ])
  })

  it('rejects header names that are not valid HTTP tokens', () => {
    expect(safeCurlHeaders([{name: 'Accept:', values: ['x'], masked: false}])).toEqual({
      headers: [],
      omitted: 1,
      omittedValues: 0
    })
  })

  it('rejects header names inherited from Object.prototype', () => {
    const inherited = ['constructor', '__proto__', 'toString', 'valueOf', 'hasOwnProperty'].map((name) => ({
      name,
      values: ['must-not-be-copied'],
      masked: false
    }))
    const {headers, omitted} = safeCurlHeaders(inherited)
    expect(headers).toEqual([])
    expect(omitted).toBe(inherited.length)
  })

  it('counts dropped values separately from fully omitted headers', () => {
    const {headers, omitted, omittedValues} = safeCurlHeaders([
      {name: 'Accept', values: ['application/json', 'bad\r\nX-Evil: 1'], masked: false}
    ])
    expect(headers).toEqual([{name: 'Accept', value: 'application/json'}])
    expect(omitted).toBe(0)
    expect(omittedValues).toBe(1)
  })

  it('exposes a short, explicit allowlist', () => {
    expect(Object.keys(SAFE_REQUEST_HEADERS)).toEqual([
      'accept',
      'accept-language',
      'cache-control',
      'content-type',
      'user-agent'
    ])
  })
})

describe('buildCurlCommand', () => {
  it('builds a minimal GET command without an explicit method', () => {
    const {command, unavailableReason} = buildCurlCommand(exchange())
    expect(unavailableReason).toBeNull()
    expect(command).toBe("curl --globoff 'http://localhost:8080/api/orders'")
  })

  it('emits an explicit method for every other verb', () => {
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']) {
      expect(buildCurlCommand(exchange({method})).command).toContain(`curl --globoff -X '${method}' `)
    }
  })

  it('uses -I for HEAD so the command cannot block waiting for a body', () => {
    const {command} = buildCurlCommand(exchange({method: 'HEAD'}))
    expect(command).toBe("curl --globoff -I 'http://localhost:8080/api/orders'")
    expect(command).not.toContain('-X')
  })

  it('uppercases a lowercase recorded method and refuses to guess a missing one', () => {
    expect(buildCurlCommand(exchange({method: 'post'})).command).toContain("-X 'POST'")
    const {command, unavailableReason} = buildCurlCommand(exchange({method: null}))
    expect(command).toBeNull()
    expect(unavailableReason).toBe(
      'This exchange has no recognizable HTTP method, so BootUI cannot build a reliable command.'
    )
  })

  it('normalizes the authority, dropping default ports, userinfo and fragments', () => {
    expect(buildCurlCommand(exchange({uri: 'HTTP://user:secret@LOCALHOST:80/api/orders#top'})).command).toBe(
      "curl --globoff 'http://localhost/api/orders'"
    )
    expect(buildCurlCommand(exchange({uri: 'https://api.example.test:443/orders'})).command).toBe(
      "curl --globoff 'https://api.example.test/orders'"
    )
  })

  it('keeps a non-default port', () => {
    expect(buildCurlCommand(exchange({uri: 'http://localhost:8081/api/orders'})).command).toBe(
      "curl --globoff 'http://localhost:8081/api/orders'"
    )
  })

  it('keeps encoded path segments as recorded', () => {
    expect(buildCurlCommand(exchange({uri: 'http://localhost/api/caf%C3%A9/orders'})).command).toBe(
      "curl --globoff 'http://localhost/api/caf%C3%A9/orders'"
    )
  })

  it('keeps the recorded path instead of resolving dot segments to another resource', () => {
    expect(buildCurlCommand(exchange({uri: 'http://localhost/api/a/%2e%2e/%2e%2e/admin'})).command).toBe(
      "curl --globoff 'http://localhost/api/a/%2e%2e/%2e%2e/admin'"
    )
    expect(buildCurlCommand(exchange({uri: 'http://localhost/api/a/../../admin'})).command).toBe(
      "curl --globoff 'http://localhost/api/a/../../admin'"
    )
    expect(buildCurlCommand(exchange({uri: 'http://localhost//api//orders'})).command).toBe(
      "curl --globoff 'http://localhost//api//orders'"
    )
  })

  it('disables curl globbing so recorded brackets stay literal and fire one request', () => {
    const {command} = buildCurlCommand(
      exchange({query: 'ids[]=1&range=x', uri: 'http://localhost/api/items[1-3]?ids[]=1&range=x'})
    )
    expect(command).toBe("curl --globoff 'http://localhost/api/items[1-3]?ids[]=VALUE&range=VALUE'")
  })

  it('prefers the DTO query field and replaces every value', () => {
    const {command, notes} = buildCurlCommand(
      exchange({
        query: 'token=******&page=1&page=2',
        uri: 'http://localhost:8080/api/orders?token=******&page=1&page=2'
      })
    )
    expect(command).toBe("curl --globoff 'http://localhost:8080/api/orders?token=VALUE&page=VALUE&page=VALUE'")
    expect(notes).toContain('3 query parameter names are kept, but every value is replaced with the VALUE placeholder.')
  })

  it('falls back to the query recorded inside the URI', () => {
    expect(buildCurlCommand(exchange({query: null, uri: 'http://localhost/api/orders?page=7'})).command).toBe(
      "curl --globoff 'http://localhost/api/orders?page=VALUE'"
    )
  })

  it('adds one quoted -H argument per copied header value', () => {
    const {command} = buildCurlCommand(
      exchange({
        requestHeaders: [
          {name: 'Accept', values: ['application/json'], masked: false},
          {name: 'Authorization', values: ['Bearer top-secret'], masked: true},
          {name: 'Cookie', values: ['SESSION=abc'], masked: true},
          {name: 'User-Agent', values: ['BootUI/1.0'], masked: false}
        ]
      })
    )
    expect(command).toBe(
      [
        "curl --globoff 'http://localhost:8080/api/orders' \\",
        "  -H 'Accept: application/json' \\",
        "  -H 'User-Agent: BootUI/1.0'"
      ].join('\n')
    )
    expect(command).not.toContain('Bearer')
    expect(command).not.toContain('SESSION')
  })

  it('cannot be escaped by shell metacharacters recorded in metadata', () => {
    const {command} = buildCurlCommand(
      exchange({
        uri: "http://localhost/a';rm -rf /;'/b",
        query: "q=1&$(id)=2&'x'=3",
        requestHeaders: [{name: 'User-Agent', values: ["curl/8 $(id) `id` 'quoted' && echo pwned"], masked: false}]
      })
    )
    expect(command).toBe(
      [
        "curl --globoff 'http://localhost/a%27;rm%20-rf%20/;%27/b?q=VALUE&$(id)=VALUE&%27x%27=VALUE' \\",
        `  -H 'User-Agent: curl/8 $(id) \`id\` '\\''quoted'\\'' && echo pwned'`
      ].join('\n')
    )
    // Every quoted word opens and closes an even number of times, so nothing escapes its argument.
    expect(command.split("'").length % 2).toBe(1)
  })

  it('cannot be turned into an extra option by an option-like value', () => {
    const {command} = buildCurlCommand(
      exchange({requestHeaders: [{name: 'Accept', values: ['--output /tmp/pwned'], masked: false}]})
    )
    expect(command).toContain("-H 'Accept: --output /tmp/pwned'")
  })

  it('explains that no body is ever copied and asks for one on body-carrying methods', () => {
    expect(buildCurlCommand(exchange()).notes).toContain(
      'BootUI never captures request bodies, so the command sends no body, form data, or file.'
    )
    expect(buildCurlCommand(exchange({method: 'POST'})).notes).toContain(
      'A POST request may have carried a body BootUI did not record: add your own --data before running the command.'
    )
    expect(buildCurlCommand(exchange({method: 'GET'})).notes.some((note) => note.includes('--data'))).toBe(false)
  })

  it('says so when no query parameter is copied, so a hidden query is never mistaken for none', () => {
    expect(buildCurlCommand(exchange({query: null})).notes).toContain(
      'No query parameter is copied. Parameters hidden by masking or by the value exposure setting never reach the command.'
    )
  })

  it('reports omitted query parameters and dropped header values', () => {
    const {notes} = buildCurlCommand(
      exchange({
        query: '******&keep=1',
        requestHeaders: [{name: 'Accept', values: ['*/*', 'bad\r\nX-Evil: 1'], masked: false}]
      })
    )
    expect(notes).toContain('1 query parameter was omitted because BootUI could not copy its name safely.')
    expect(notes).toContain('1 header value was dropped because it was masked or contained control characters.')
  })

  it('reports how many request headers were omitted', () => {
    const {notes} = buildCurlCommand(
      exchange({
        requestHeaders: [
          {name: 'Accept', values: ['*/*'], masked: false},
          {name: 'Authorization', values: ['******'], masked: true}
        ]
      })
    )
    expect(notes).toContain(
      '1 request header was omitted. Only Accept, Accept-Language, Cache-Control, Content-Type, and User-Agent are copied, and never when their values are masked or hidden.'
    )
  })

  it('copies nothing at all when values are withheld by the exposure policy', () => {
    const {command, notes} = buildCurlCommand(
      exchange({
        query: null,
        uri: 'http://localhost:8080/api/orders',
        requestHeaders: [
          {name: 'Accept', values: [], masked: false},
          {name: 'Authorization', values: [], masked: true}
        ]
      })
    )
    expect(command).toBe("curl --globoff 'http://localhost:8080/api/orders'")
    expect(notes.some((note) => note.startsWith('2 request headers were omitted.'))).toBe(true)
  })

  it('refuses to guess when the recorded URL is missing, relative or not http(s)', () => {
    for (const uri of [null, '', '   ', '/api/orders', 'not a url', 'ftp://localhost/api', 'file:///etc/passwd']) {
      const {command, unavailableReason} = buildCurlCommand(exchange({uri}))
      expect(command).toBeNull()
      expect(unavailableReason).toBe(
        'This exchange has no recorded absolute http(s) request URL, so BootUI cannot build a reliable command.'
      )
    }
  })

  it('refuses to build a command for an unrecognizable method', () => {
    for (const method of ['--data', 'GET /x', 'G;ET', 'A'.repeat(21)]) {
      const {command, unavailableReason} = buildCurlCommand(exchange({method}))
      expect(command).toBeNull()
      expect(unavailableReason).toBe(
        'This exchange has no recognizable HTTP method, so BootUI cannot build a reliable command.'
      )
    }
  })

  it('tolerates a missing or malformed exchange', () => {
    expect(buildCurlCommand(null).command).toBeNull()
    expect(buildCurlCommand({}).command).toBeNull()
    expect(buildCurlCommand({method: 'GET', uri: 'http://h/x', requestHeaders: 'nope'}).command).toBe(
      "curl --globoff 'http://h/x'"
    )
  })

  it('is a pure function of the DTO, so every adapter produces byte-identical text', () => {
    const dto = exchange({
      method: 'PUT',
      query: 'a=1&b=2',
      uri: 'http://localhost:8080/api/orders?a=1&b=2',
      requestHeaders: [
        {name: 'content-type', values: ['application/json'], masked: false},
        {name: 'ACCEPT', values: ['application/json'], masked: false}
      ]
    })
    const springLikeCasing = buildCurlCommand(dto).command
    const quarkusLikeCasing = buildCurlCommand({
      ...dto,
      requestHeaders: [
        {name: 'Accept', values: ['application/json'], masked: false},
        {name: 'Content-Type', values: ['application/json'], masked: false}
      ]
    }).command
    expect(quarkusLikeCasing).toBe(springLikeCasing)
    expect(springLikeCasing).toBe(
      [
        "curl --globoff -X 'PUT' 'http://localhost:8080/api/orders?a=VALUE&b=VALUE' \\",
        "  -H 'Accept: application/json' \\",
        "  -H 'Content-Type: application/json'"
      ].join('\n')
    )
  })
})
