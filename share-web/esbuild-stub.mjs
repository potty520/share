/**
 * esbuild 桩模块：沙箱禁止子进程管道，esbuild 无法 spawn 服务进程。
 * Vite 构建期间只在「define 替换」等少量场景调用 esbuild.transform，
 * 这里用等价的纯 JS 字符串替换实现，供本地验证构建使用。
 * 注意：仅用于本沙箱内验证，正式构建仍使用 node build.mjs（若环境允许）
 * 或 Lead 执行 npm run build。
 */

function serialize(value) {
  if (typeof value === 'string') return value
  if (value === undefined) return 'undefined'
  if (typeof value === 'object' && value !== null) return `(${JSON.stringify(value)})`
  return String(value)
}

export async function transform(code, options = {}) {
  let out = code
  const define = options.define || {}
  const keys = Object.keys(define).sort((a, b) => b.length - a.length)
  for (const key of keys) {
    if (!out.includes(key)) continue
    out = out.split(key).join(serialize(define[key]))
  }
  return { code: out, map: '', warnings: [] }
}

export async function build() {
  return { errors: [], warnings: [], outputFiles: [] }
}

export async function formatMessages(messages = []) {
  return messages.map((m) => (typeof m === 'string' ? m : m.text || JSON.stringify(m)))
}

export const version = '0.21.5-stub'

export default { transform, build, formatMessages, version }
