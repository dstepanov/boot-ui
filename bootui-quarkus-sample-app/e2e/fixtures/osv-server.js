import {createServer} from 'node:http'

const PORT = Number(process.env.BOOTUI_OSV_FIXTURE_PORT || 18080)
const CRITICAL_ADVISORY_ID = 'GHSA-BOOTUI-CRITICAL'
const LOW_ADVISORY_ID = 'GHSA-BOOTUI-LOW'

let affectedPackage = null

const server = createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/health') {
      return json(response, 200, {status: 'ready'})
    }

    if (request.method === 'POST' && request.url === '/v1/querybatch') {
      const body = await readJson(request)
      const queries = body?.queries
      if (!Array.isArray(queries) || queries.length === 0 || !queries.every(isValidMavenQuery)) {
        return json(response, 400, {error: 'Expected non-empty Maven package queries with names and versions'})
      }

      affectedPackage = queries[0].package.name
      const results = queries.map((_, index) =>
        index === 0 ? {vulns: [{id: LOW_ADVISORY_ID}, {id: CRITICAL_ADVISORY_ID}]} : {vulns: []}
      )
      return json(response, 200, {results})
    }

    if (request.method === 'GET' && request.url === `/v1/vulns/${CRITICAL_ADVISORY_ID}`) {
      return json(response, 200, advisory(CRITICAL_ADVISORY_ID, 'Synthetic critical advisory', 'CRITICAL'))
    }

    if (request.method === 'GET' && request.url === `/v1/vulns/${LOW_ADVISORY_ID}`) {
      return json(response, 200, advisory(LOW_ADVISORY_ID, 'Synthetic low advisory', 'LOW'))
    }

    return json(response, 404, {error: 'Not found'})
  } catch (error) {
    return json(response, 500, {error: error instanceof Error ? error.message : String(error)})
  }
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Deterministic OSV fixture listening on http://127.0.0.1:${PORT}`)
})

function isValidMavenQuery(query) {
  return (
    query?.package?.ecosystem === 'Maven' &&
    typeof query.package.name === 'string' &&
    query.package.name.length > 0 &&
    typeof query.version === 'string' &&
    query.version.length > 0
  )
}

function advisory(id, summary, severity) {
  if (!affectedPackage) {
    throw new Error('Advisory detail requested before querybatch')
  }

  const severityFields =
    severity === 'CRITICAL'
      ? {
          severity: [
            {
              type: 'CVSS_V3',
              score: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H'
            }
          ]
        }
      : {database_specific: {severity}}

  return {
    id,
    summary,
    details: 'A deterministic advisory served by the BootUI Quarkus browser fixture.',
    ...severityFields,
    references: [{type: 'ADVISORY', url: `https://example.test/advisories/${id}`}],
    affected: [
      {
        package: {ecosystem: 'Maven', name: affectedPackage},
        ranges: [{type: 'ECOSYSTEM', events: [{introduced: '0'}, {fixed: '9999.0.0'}]}]
      }
    ]
  }
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) {
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function json(response, status, body) {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
    Connection: 'close'
  })
  response.end(payload)
}

function shutdown() {
  server.close(() => process.exit(0))
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
