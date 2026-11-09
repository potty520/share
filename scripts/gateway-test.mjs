const BASE = process.env.SHARE_BASE || 'http://127.0.0.1:8080'
let token = ''
const results = []
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail })
  console.log(`${cond ? '[PASS]' : '[FAIL]'} ${name}${detail ? ' :: ' + detail : ''}`)
}
async function api(method, path, body) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = 'Bearer ' + token
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  let json = {}
  try { json = await res.json() } catch { /* ignore */ }
  return { status: res.status, json }
}
async function openCall(code, appKey, appSecret, version) {
  const headers = { 'X-App-Key': appKey, 'X-App-Secret': appSecret }
  if (version) headers['X-Api-Version'] = version
  const res = await fetch(`${BASE}/api/open/v1/${code}?pageNum=1&pageSize=1`, { headers })
  let json = {}
  try { json = await res.json() } catch { /* ignore */ }
  return { status: res.status, json }
}
async function issue(apiId, name, ipWhitelist, ipBlacklist) {
  const r = await api('POST', '/api/share/v1/credentials', {
    apiId, consumerName: name, expireDays: 1, grantLevel: 1, ipWhitelist, ipBlacklist
  })
  if (r.json.code !== 0) throw new Error('签发凭据失败: ' + JSON.stringify(r.json))
  return r.json.data
}

const sms = await api('POST', '/api/auth/v1/sms-code', { username: 'admin' })
const login = await api('POST', '/api/auth/v1/login', {
  username: 'admin', password: '123456', smsCode: sms.json.data?.code || sms.json.data?.smsCode
})
token = login.json.data?.accessToken
if (!token) throw new Error('登录失败: ' + JSON.stringify(login.json))

const ts = Date.now().toString().slice(-6)
const tmpCode = 'TMP_GW_' + ts
let apiId = null
const credentials = []
try {
  const seed = (await api('GET', '/api/share/v1/apis/1')).json.data
  const base = {
    apiCode: seed.apiCode, catalogId: seed.catalogId, catalogName: seed.catalogName,
    sourceId: seed.sourceId, httpMethod: seed.httpMethod, paramsJson: null,
    returnFields: 'entName,creditCode', maskFields: seed.maskFields, qpsLimit: 1000,
    owner: seed.owner, deptId: seed.deptId, remark: 'gateway-test'
  }
  const created = await api('POST', '/api/share/v1/apis', {
    ...base, apiCode: tmpCode, apiName: '网关测试-' + ts, sourceTable: 'shr_demo_enterprise'
  })
  if (created.json.code !== 0) throw new Error('创建临时 API 失败: ' + JSON.stringify(created.json))
  apiId = created.json.data.id

  await api('POST', `/api/share/v1/apis/${apiId}/online`)
  const v1 = await api('GET', `/api/share/v1/apis/${apiId}/versions`)
  ok('上线自动生成版本快照', v1.json.data?.[0]?.versionLabel === 'v1', JSON.stringify(v1.json.data?.[0]))

  const published = await api('POST', `/api/share/v1/apis/${apiId}/versions`, { note: '自动测试发布' })
  ok('手工发布新版本', published.json.data?.versionLabel === 'v2', JSON.stringify(published.json.data))

  await api('PUT', `/api/share/v1/apis/${apiId}`, {
    ...base, apiCode: tmpCode, apiName: '改名后-' + ts, sourceTable: 'shr_demo_enterprise'
  })
  const rolled = await api('POST', `/api/share/v1/apis/${apiId}/versions/1/rollback`)
  const afterRollback = (await api('GET', `/api/share/v1/apis/${apiId}`)).json.data
  ok('回滚恢复历史配置', rolled.json.code === 0 && afterRollback.apiName === '网关测试-' + ts,
    `name=${afterRollback.apiName} versionNo=${afterRollback.versionNo}`)

  const denyIp = await issue(apiId, 'IP拒绝-' + ts, '10.0.0.0/8', null); credentials.push(denyIp)
  let call = await openCall(tmpCode, denyIp.appKey, denyIp.appSecret)
  ok('白名单外 IP 拒绝', call.json.code === 42406, `code=${call.json.code}`)

  const allowIp = await issue(apiId, 'IP放行-' + ts, '0.0.0.0/0', null); credentials.push(allowIp)
  call = await openCall(tmpCode, allowIp.appKey, allowIp.appSecret)
  ok('白名单内 IP 放行', call.json.code === 0, `code=${call.json.code}`)

  const black = await issue(apiId, '黑名单-' + ts, null, '0.0.0.0/0'); credentials.push(black)
  call = await openCall(tmpCode, black.appKey, black.appSecret)
  ok('黑名单优先拒绝', call.json.code === 42406, `code=${call.json.code}`)

  const rot = await issue(apiId, '轮换-' + ts, null, null); credentials.push(rot)
  const rotated = await api('POST', `/api/share/v1/credentials/${rot.id}/rotate?graceHours=24`)
  const newSecret = rotated.json.data?.appSecret
  const newCall = await openCall(tmpCode, rot.appKey, newSecret)
  const oldCall = await openCall(tmpCode, rot.appKey, rot.appSecret)
  ok('轮换后新密钥生效', newCall.json.code === 0, `code=${newCall.json.code}`)
  ok('轮换后旧密钥宽限期可用', oldCall.json.code === 0, `code=${oldCall.json.code}`)

  await api('PUT', `/api/share/v1/apis/${apiId}`, {
    ...base, apiCode: tmpCode, apiName: '熔断测试-' + ts, sourceTable: 'no_such_table_xyz'
  })
  const cb = await issue(apiId, '熔断-' + ts, null, null); credentials.push(cb)
  const codes = []
  for (let i = 0; i < 6; i++) {
    codes.push((await openCall(tmpCode, cb.appKey, cb.appSecret)).json.code)
  }
  ok('连续失败触发熔断', codes[codes.length - 1] === 42405, 'codes=' + codes.join(','))
  const status = await api('GET', '/api/share/v1/gateway/status')
  ok('熔断状态可观测', status.json.data?.circuits?.[tmpCode]?.open === true,
    JSON.stringify(status.json.data?.circuits?.[tmpCode]))
  await api('POST', '/api/share/v1/gateway/circuit/reset?apiCode=' + tmpCode)
  const status2 = await api('GET', '/api/share/v1/gateway/status')
  ok('熔断应急复位', status2.json.data?.circuits?.[tmpCode]?.open !== true,
    JSON.stringify(status2.json.data?.circuits?.[tmpCode]))
} finally {
  if (apiId) {
    await api('POST', `/api/share/v1/apis/${apiId}/offline`)
    await api('DELETE', `/api/share/v1/apis/${apiId}`)
  }
  for (const c of credentials) {
    await api('PUT', `/api/share/v1/credentials/${c.id}/revoke`, { reason: 'gateway-test cleanup' })
  }
}
const failed = results.filter((r) => !r.pass)
console.log(`\n=== 网关增强测试 ===\nPASS=${results.length - failed.length} FAIL=${failed.length} TOTAL=${results.length}`)
if (failed.length) process.exit(1)
