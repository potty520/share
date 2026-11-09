/**
 * Node ESM loader 钩子：把 'esbuild' 解析到本地桩模块（仅本次进程生效）。
 */
export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'esbuild') {
    return {
      url: new URL('./esbuild-stub.mjs', import.meta.url).href,
      shortCircuit: true,
      format: 'module'
    }
  }
  return nextResolve(specifier, context)
}
