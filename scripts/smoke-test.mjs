// 共享交换系统 · 端到端冒烟测试
// 用法：node scripts/smoke-test.mjs   （后端需已启动在 8080）
// 说明：逐项调用契约中的关键接口，输出 PASS / FAIL / SKIP 统计，用于集成验收。

const BASE = process.env.SHARE_BASE || 'http://127.0.0.1:8080'
const results = []
let token = ''
const ts = Date.now().toString().slice(-6)

async function call(method, path, { body, headers = {}, useToken = true, raw = false } = {}) {
  const h = { ...headers }
  if (body !== undefined) h['Content-Type'] = 'application/json'
  if (useToken && token) h['Authorization'] = `Bearer ${token}`
  const res = await fetch(BASE + path, {
    method,
    headers: h,
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body))
  })
  const text = await res.text()
  if (raw) return { status: res.status, text }
  let json
  try {
    json = text ? JSON.parse(text) : {}
  } catch (e) {
    json = { code: -1, message: '非 JSON 响应: ' + text.slice(0, 120) }
  }
  return { status: res.status, ...json }
}

function record(name, ok, detail) {
  results.push({ name, ok, detail })
  const icon = ok === true ? 'PASS' : ok === 'skip' ? 'SKIP' : 'FAIL'
  console.log(`[${icon}] ${name}${detail ? ' :: ' + String(detail).slice(0, 160) : ''}`)
}

async function test(name, fn) {
  try {
    const detail = await fn()
    record(name, true, detail)
  } catch (e) {
    if (e && e.skip) record(name, 'skip', e.message)
    else record(name, false, e?.message || e)
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg || '断言失败')
}

function expectCode(res, code = 0) {
  assert(res.code === code, `期望 code=${code}，实际 code=${res.code} msg=${res.message}`)
  return res
}

async function login(username = 'admin', password = '123456') {
  // 先尝试获取短信验证码（admin/security 需要双因素）
  let smsCode
  try {
    const sms = await call('POST', '/api/auth/v1/sms-code', { body: { username }, useToken: false })
    if (sms.code === 0 && sms.data) smsCode = sms.data.code || sms.data.smsCode
  } catch (e) { /* ignore */ }
  let res = await call('POST', '/api/auth/v1/login', {
    body: { username, password, smsCode }, useToken: false
  })
  if (res.code !== 0 && res.code === 41104) {
    throw new Error('需要短信验证码，但自动获取失败：' + res.message)
  }
  expectCode(res)
  assert(res.data && res.data.accessToken, '登录响应缺少 accessToken')
  token = res.data.accessToken
  return `${username}(${res.data.user?.roleCode}) 权限 ${res.data.permissions?.length ?? 0} 项`
}

async function loginAs(username) {
  const saved = token
  const info = await login(username, '123456')
  const t = token
  token = saved
  return { token: t, info }
}

async function main() {
  console.log('=== 共享交换系统冒烟测试 ' + BASE + ' ===\n')

  await test('健康检查 GET /api/health', async () => {
    const r = await call('GET', '/api/health', { useToken: false })
    assert(r.code === 0 && r.data?.status === 'UP', '健康检查失败: ' + JSON.stringify(r))
    return `version=${r.data.version}`
  })

  await test('登录（admin，双因素）', async () => login('admin'))

  await test('获取当前用户 profile', async () => {
    const r = expectCode(await call('GET', '/api/auth/v1/profile'))
    return `${r.data?.user?.username || r.data?.username} perms=${(r.data?.permissions || []).length}`
  })

  // ===== 用户与组织 =====
  await test('用户列表', async () => {
    const r = expectCode(await call('GET', '/api/user/v1/users?pageNum=1&pageSize=10'))
    assert(Array.isArray(r.data), 'data 不是数组')
    assert(r.data.length >= 6, '演示用户不足: ' + r.data.length)
    return `${r.data.length} 条 / 共 ${r.page?.total}`
  })
  await test('组织树', async () => {
    const r = expectCode(await call('GET', '/api/user/v1/depts'))
    assert(Array.isArray(r.data) && r.data.length > 0, '部门为空')
    return `${r.data.length} 个根节点`
  })
  await test('角色列表', async () => {
    const r = expectCode(await call('GET', '/api/user/v1/roles'))
    assert(Array.isArray(r.data) && r.data.length >= 5, '角色不足')
    return `${r.data.length} 个角色`
  })
  await test('权限字典', async () => {
    const r = expectCode(await call('GET', '/api/user/v1/permissions'))
    return `${r.data?.length ?? 0} 个权限点`
  })

  // ===== 数据源 =====
  let datasourceId
  await test('数据源列表', async () => {
    const r = expectCode(await call('GET', '/api/datasource/v1/sources?pageNum=1&pageSize=20'))
    assert(Array.isArray(r.data) && r.data.length >= 5, '演示数据源不足: ' + (r.data?.length ?? 0))
    datasourceId = r.data[0].id
    return `${r.data.length} 个数据源`
  })
  await test('数据源类型字典', async () => {
    const r = expectCode(await call('GET', '/api/datasource/v1/types'))
    return `${r.data?.length ?? 0} 种类型`
  })
  await test('新建并测试数据源', async () => {
    const create = expectCode(await call('POST', '/api/datasource/v1/sources', {
      body: {
        sourceCode: 'DS-SMOKE-' + ts, sourceName: '冒烟测试数据源', sourceType: 'MYSQL',
        host: '127.0.0.1', port: 3306, dbName: 'demo', username: 'demo', password: 'demo123',
        deptId: 1, deptName: '市大数据局', secretLevel: 2, owner: 'admin', status: 'ENABLED'
      }
    }))
    const id = create.data?.id || create.data
    assert(id, '创建未返回 id')
    const t = expectCode(await call('POST', `/api/datasource/v1/sources/${id}/test`))
    // 端口未开放时 success 为 false 属正常，只要接口可用
    return `id=${id} test=${JSON.stringify(t.data).slice(0, 80)}`
  })

  // ===== 目录 =====
  let catalogId
  await test('目录列表（已发布）', async () => {
    const r = expectCode(await call('GET', '/api/catalog/v1/resources?pageNum=1&pageSize=20'))
    assert(Array.isArray(r.data) && r.data.length >= 5, '演示目录不足: ' + (r.data?.length ?? 0))
    catalogId = r.data[0].id
    return `${r.data.length} 条 / 共 ${r.page?.total}`
  })
  await test('目录详情（含数据项）', async () => {
    const r = expectCode(await call('GET', `/api/catalog/v1/resources/${catalogId}`))
    assert(r.data, '详情为空')
    const items = r.data.items || r.data.catalogItems || []
    return `${r.data.resourceName} items=${items.length}`
  })
  await test('目录样例预览（敏感字段脱敏）', async () => {
    const r = expectCode(await call('GET', `/api/catalog/v1/resources/${catalogId}/preview`))
    return `rows=${Array.isArray(r.data) ? r.data.length : JSON.stringify(r.data).slice(0, 60)}`
  })
  await test('分类树', async () => {
    const r = expectCode(await call('GET', '/api/catalog/v1/classifies'))
    assert(Array.isArray(r.data) && r.data.length > 0, '分类为空')
    return `${r.data.length} 个根分类`
  })
  await test('目录统计', async () => {
    const r = expectCode(await call('GET', '/api/catalog/v1/stats'))
    return JSON.stringify(r.data).slice(0, 120)
  })

  // ===== 任务 =====
  let taskId, instanceId
  await test('任务列表', async () => {
    const r = expectCode(await call('GET', '/api/task/v1/tasks?pageNum=1&pageSize=20'))
    assert(Array.isArray(r.data) && r.data.length >= 2, '演示任务不足: ' + (r.data?.length ?? 0))
    taskId = r.data[0].id
    return `${r.data.length} 个任务`
  })
  await test('任务详情', async () => {
    const r = expectCode(await call('GET', `/api/task/v1/tasks/${taskId}`))
    return r.data?.taskName
  })
  await test('手工触发任务并轮询实例状态', async () => {
    const run = expectCode(await call('POST', `/api/task/v1/tasks/${taskId}/run`))
    instanceId = run.data?.instanceId || run.data?.id
    assert(instanceId, '未返回 instanceId: ' + JSON.stringify(run.data))
    let status = ''
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      const inst = await call('GET', `/api/task/v1/instances/${instanceId}`)
      status = inst.data?.instance?.status || inst.data?.status || ''
      if (['SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'VERIFY_FAILED'].includes(status)) break
    }
    return `instanceId=${instanceId} status=${status}`
  })
  await test('实例日志', async () => {
    const r = expectCode(await call('GET', `/api/task/v1/instances/${instanceId}/logs?pageNum=1&pageSize=20`))
    return `${Array.isArray(r.data) ? r.data.length : r.data?.length ?? 0} 行`
  })
  await test('任务实例列表', async () => {
    const r = expectCode(await call('GET', '/api/task/v1/instances?pageNum=1&pageSize=10'))
    assert(Array.isArray(r.data), 'data 非数组')
    return `${r.data.length} 条`
  })
  await test('加工算子字典与试运行', async () => {
    const opsRaw = expectCode(await call('GET', '/api/task/v1/operators'))
    const opsList = Array.isArray(opsRaw.data) ? opsRaw.data : (opsRaw.data?.operators || [])
    const p = expectCode(await call('POST', '/api/task/v1/preview', {
      body: {
        sampleRows: [{ name: ' 张三 ', phone: '13812345678' }],
        mapping: [{ sourceField: 'name', targetField: 'name', transform: 'UPPER' }],
        operators: [{ type: 'TRIM', field: 'name' }]
      }
    }))
    return `operators=${opsList.length} preview=${JSON.stringify(p.data).slice(0, 60)}`
  })
  await test('质量规则与报告', async () => {
    const rules = expectCode(await call('GET', '/api/quality/v1/rules?pageNum=1&pageSize=10'))
    const reports = expectCode(await call('GET', '/api/quality/v1/reports?pageNum=1&pageSize=10'))
    return `rules=${rules.data?.length ?? 0} reports=${reports.data?.length ?? 0}`
  })

  // ===== 共享服务与审批 =====
  let apiId, appKey, appSecret
  await test('共享服务列表', async () => {
    const r = expectCode(await call('GET', '/api/share/v1/apis?pageNum=1&pageSize=10'))
    assert(Array.isArray(r.data) && r.data.length > 0, '共享服务为空')
    apiId = r.data[0].id
    return `${r.data.length} 个服务`
  })
  await test('共享服务在线调试', async () => {
    const r = expectCode(await call('POST', `/api/share/v1/apis/${apiId}/test`, { body: { params: { pageNum: 1, pageSize: 5 } } }))
    return JSON.stringify(r.data).slice(0, 120)
  })
  await test('共享服务自动文档', async () => {
    const r = expectCode(await call('GET', `/api/share/v1/apis/${apiId}/doc`))
    return JSON.stringify(r.data).slice(0, 100)
  })
  await test('凭据列表', async () => {
    const r = expectCode(await call('GET', '/api/share/v1/credentials?pageNum=1&pageSize=10'))
    const list = r.data || []
    const c = list.find((x) => x.appKey && (x.status === 'ACTIVE' || !x.status))
    if (c) { appKey = c.appKey; appSecret = c.appSecretPlain || c.appSecret }
    return `${list.length} 个凭据 ${c ? 'appKey=' + c.appKey : ''}`
  })
  await test('开放 API 调用（AppKey 鉴权）', async () => {
    const demoKeys = [
      { key: 'AKDEMOENT0001AB', secret: 'SKdemoEnterpriseSecret2026AAbbCC', code: 'ENTERPRISE_BASE_INFO' },
      { key: 'AKDEMOHRSS0002CD', secret: 'SKdemoHrssSecret2026CCddEEFFGG', code: 'HRSS_INSURANCE_QUERY' },
      { key: 'AKDEMOGRANT0104EF', secret: 'SKdemoGrantSecret2026EEffGGhhII', code: 'HRSS_INSURANCE_QUERY' }
    ]
    const apiCode = await getApiCode()
    const candidates = [...demoKeys]
    if (apiCode) candidates.unshift({ key: appKey, secret: appSecret || '', code: apiCode })
    let lastErr
    for (const c of candidates) {
      if (!c.key || !c.secret || !c.code) continue
      const h = { 'X-App-Key': c.key, 'X-App-Secret': c.secret }
      const r = await call('GET', '/api/open/v1/' + c.code, { useToken: false, headers: h })
      if (r.code === 0) {
        return `${c.code} -> ${JSON.stringify(r.data).slice(0, 90)}`
      }
      lastErr = r
    }
    throw new Error('开放调用失败: ' + JSON.stringify(lastErr || {}).slice(0, 160))
  })
  async function getApiCode() {
    const r = await call('GET', `/api/share/v1/apis/${apiId}`)
    return r.data?.apiCode
  }
  await test('申请列表与审批流配置', async () => {
    const applies = expectCode(await call('GET', '/api/approval/v1/applies?pageNum=1&pageSize=10'))
    assert((applies.data?.length ?? 0) >= 3, '管理员应能看到全部演示申请，实际 ' + (applies.data?.length ?? 0))
    const flow = expectCode(await call('GET', '/api/approval/v1/flow-config'))
    return `applies=${applies.data?.length ?? 0} flow=${JSON.stringify(flow.data).slice(0, 60)}`
  })
  await test('提交共享申请（consumer）', async () => {
    const adminToken = token
    const c = await loginAs('consumer')
    token = c.token
    try {
      const r = await call('POST', '/api/approval/v1/applies', {
        useToken: true,
        headers: { Authorization: `Bearer ${c.token}` },
        body: { catalogId, purpose: '冒烟测试申请-企业信息用于业务分析', scope: '本部门', startDate: '2026-11-10', endDate: '2026-12-10', itemScope: '全部数据项' }
      })
      expectCode(r)
      return `applyNo=${r.data?.applyNo || r.data?.id}`
    } finally {
      token = adminToken
    }
  })

  // ===== 安全 =====
  await test('分级模板与脱敏规则', async () => {
    const t = expectCode(await call('GET', '/api/security/v1/templates'))
    const rules = expectCode(await call('GET', '/api/security/v1/mask-rules?pageNum=1&pageSize=10'))
    return `templates=${t.data?.length ?? 0} rules=${rules.data?.length ?? 0}`
  })
  await test('脱敏规则试算', async () => {
    const r = expectCode(await call('POST', '/api/security/v1/mask-rules/test', {
      body: { maskType: 'MASK', param: '3,4', value: '13812345678' }
    }))
    const masked = typeof r.data === 'string' ? r.data : (r.data?.masked || JSON.stringify(r.data))
    assert(masked && masked !== '13812345678' && String(masked).includes('*'), '未发生脱敏: ' + masked)
    return String(masked).slice(0, 60)
  })
  await test('安全概览', async () => {
    const r = expectCode(await call('GET', '/api/security/v1/overview'))
    return JSON.stringify(r.data).slice(0, 120)
  })

  // ===== 文件 =====
  let fileId
  await test('文件上传与校验', async () => {
    const form = new FormData()
    const content = 'smoke test file content ' + ts
    form.append('file', new Blob([content], { type: 'text/plain' }), 'smoke-' + ts + '.txt')
    const res = await fetch(BASE + '/api/file/v1/files', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form
    })
    const json = await res.json()
    expectCode(json)
    fileId = json.data?.id || json.data
    const verify = expectCode(await call('POST', `/api/file/v1/files/${fileId}/verify`))
    return `fileId=${fileId} verify=${JSON.stringify(verify.data).slice(0, 80)}`
  })
  await test('文件列表与统计', async () => {
    const f = expectCode(await call('GET', '/api/file/v1/files?pageNum=1&pageSize=10'))
    const s = expectCode(await call('GET', '/api/file/v1/stats'))
    return `files=${f.data?.length ?? 0} stats=${JSON.stringify(s.data).slice(0, 80)}`
  })

  // ===== 审计 =====
  await test('审计日志检索/统计/异常', async () => {
    const logs = expectCode(await call('GET', '/api/audit/v1/logs?pageNum=1&pageSize=10'))
    const stats = expectCode(await call('GET', '/api/audit/v1/stats'))
    const anomaliesRaw = expectCode(await call('GET', '/api/audit/v1/anomalies'))
    const anomalies = Array.isArray(anomaliesRaw.data) ? anomaliesRaw.data : (anomaliesRaw.data?.items || [])
    assert(logs.data?.length >= 1, '无审计日志（事件监听可能未生效）')
    return `logs=${logs.data.length} anomalies=${anomalies.length}`
  })

  // ===== 监控 =====
  await test('监控总览/节点/指标/告警', async () => {
    const o = expectCode(await call('GET', '/api/monitor/v1/overview'))
    const nRaw = expectCode(await call('GET', '/api/monitor/v1/nodes'))
    const nList = Array.isArray(nRaw.data) ? nRaw.data : (nRaw.data?.list || [])
    const m = expectCode(await call('GET', '/api/monitor/v1/metrics?type=EXCHANGE'))
    const a = expectCode(await call('GET', '/api/monitor/v1/alarms?pageNum=1&pageSize=10'))
    return `overview=${JSON.stringify(o.data).slice(0, 100)} nodes=${nList.length}`
  })
  await test('运维报表', async () => {
    const r = expectCode(await call('GET', '/api/monitor/v1/reports?period=day'))
    return JSON.stringify(r.data).slice(0, 120)
  })

  // ===== 通知 =====
  await test('我的消息与公告', async () => {
    const m = expectCode(await call('GET', '/api/notify/v1/messages?pageNum=1&pageSize=10'))
    const a = expectCode(await call('GET', '/api/notify/v1/announcements?pageNum=1&pageSize=10'))
    return `messages=${m.data?.length ?? 0} announcements=${a.data?.length ?? 0}`
  })

  // ===== 前置节点 =====
  await test('前置节点列表', async () => {
    const r = expectCode(await call('GET', '/api/agent/v1/nodes?pageNum=1&pageSize=10'))
    assert(Array.isArray(r.data) && r.data.length > 0, '节点为空')
    return `${r.data.length} 个节点`
  })

  // ===== 汇总 =====
  const pass = results.filter((r) => r.ok === true).length
  const fail = results.filter((r) => r.ok === false).length
  const skip = results.filter((r) => r.ok === 'skip').length
  console.log('\n=== 结果汇总 ===')
  console.log(`PASS=${pass}  FAIL=${fail}  SKIP=${skip}  TOTAL=${results.length}`)
  if (fail) {
    console.log('\n失败项：')
    results.filter((r) => r.ok === false).forEach((r) => console.log(` - ${r.name}: ${r.detail}`))
    process.exitCode = 1
  }
}

main().catch((e) => {
  console.error('冒烟测试执行异常：', e)
  process.exitCode = 1
})
