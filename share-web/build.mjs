/**
 * 沙箱内本地验证构建脚本（等价 npm run build，输出 share-web/dist）。
 *
 * 背景：本机 DSH 沙箱禁止子进程管道创建，真正的 esbuild 服务无法启动（spawn EPERM）：
 *   - Vite 5 在 Windows 首次 realpath 时执行 `net use` 探测网络映射盘；
 *   - 构建期间 Vite 用 esbuild.transform 做 define 替换。
 * 这里分别打桩 child_process.exec（让探测静默失败）并把 'esbuild' 解析到纯 JS 桩模块，
 * 其余流程（@vitejs/plugin-vue、rollup、CSS 提取）保持真实。仅用于沙箱内自查，
 * 正式构建由 Lead 执行 scripts/build-web.cmd（真实 esbuild）。
 *
 * 用法：node build.mjs
 */
import cp from 'node:child_process'
import { register } from 'node:module'

// 1) 静默 `net use` 探测（exec 无法创建管道时会同步抛错）
function stubChildProcess(...args) {
  const cb = args.find((a) => typeof a === 'function')
  if (typeof cb === 'function') {
    process.nextTick(() => cb(new Error('sandbox: child process exec disabled'), '', ''))
  }
  return { on() {}, once() {}, kill() {}, stdout: null, stderr: null, pid: -1 }
}
cp.exec = stubChildProcess
cp.execFile = stubChildProcess

// 2) 把 'esbuild' 重定向到纯 JS 桩模块
register(new URL('./esbuild-stub-hooks.mjs', import.meta.url))

const { build } = await import('vite')
const { default: vue } = await import('@vitejs/plugin-vue')
const path = await import('node:path')
const { fileURLToPath } = await import('node:url')

const root = path.dirname(fileURLToPath(import.meta.url))

await build({
  root,
  configFile: false,
  logLevel: 'info',
  plugins: [
    vue(),
    {
      name: 'inject-runtime-defines',
      transformIndexHtml(html) {
        return html.replace(
          '<head>',
          `<head>\n    <script>window.process=window.process||{env:{NODE_ENV:"production"}};window.__VUE_OPTIONS_API__=true;window.__VUE_PROD_DEVTOOLS__=false;window.__VUE_PROD_HYDRATION_MISMATCH_DETAILS__=false;</script>`
        )
      }
    }
  ],
  resolve: { alias: { '@': path.resolve(root, 'src') } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    minify: false,
    target: 'esnext',
    chunkSizeWarningLimit: 4000
  }
})
