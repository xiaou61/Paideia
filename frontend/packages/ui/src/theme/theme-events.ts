/**
 * 主题变更事件（WORK-079）。
 *
 * <p>为什么要有它：`<html>` 上的令牌变了，**读令牌的一方不会自动知道**。受影响的是那些
 * "不是 CSS 画的"东西——知识图谱的 sigma / 3d-force-graph 画布在挂载时把 `--tier-*` 读成具体
 * 颜色交给渲染器（`apps/app/src/components/graph/tier-style.ts` 的 `resolveVar`），之后就不再重读；
 * 换肤后页面全变了、图还是旧配色。
 *
 * <p>为什么用 DOM 事件而不是 Context：画布组件要的是"给我一堆颜色字符串"，而不是"重新 render"，
 * 而且第三方渲染器的生命周期与 React 无关。一个全局事件比把主题塞进每个画布组件的 props
 * 更小、也更稳。
 *
 * <p>明暗切换与换肤派发同一个事件：两者的后果完全一样（令牌整套换掉）。
 */
export const THEME_CHANGE_EVENT = 'paideia:themechange'

/** 订阅主题变更；返回取消订阅的函数（组件里直接 `return` 它即可）。 */
export function subscribeToThemeChange(listener: () => void): () => void {
  if (typeof document === 'undefined') {
    return () => undefined
  }
  document.addEventListener(THEME_CHANGE_EVENT, listener)
  return () => document.removeEventListener(THEME_CHANGE_EVENT, listener)
}
