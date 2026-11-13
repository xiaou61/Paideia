import type { Plugin } from 'vite'

/*
 * 这里的相对导入必须带 .ts 扩展名，全仓唯一几处，不要"顺手统一掉"：
 * vite 配置加载器把工作区包当作外部依赖交给 Node 直接加载，而 Node 的 ESM 解析
 * 不做无扩展名的补全 —— 少了这个扩展名，任何应用启动都会报 ERR_MODULE_NOT_FOUND。
 * 该扩展名要求 tsconfig.base 打开 allowImportingTsExtensions。
 *
 * 也因此这里**只许 import `keys.ts`**：它是纯常量、没有依赖。一旦 import 了 `skins.ts`
 * （它再 import 各族的注册文件），这条链上的每个相对导入都得写扩展名，而注册表是要频繁
 * 追加的——把这条约束留在首屏脚本上不值得。
 */
import { DEFAULT_SKIN, SKIN_STORAGE_KEY, THEME_STORAGE_KEY } from './keys.ts'

/**
 * 首屏防闪烁脚本。
 *
 * <p>它必须在浏览器首次绘制之前把主题类与皮肤属性落到 `<html>` 上，所以只能是内联脚本，
 * 不能等打包后的模块执行 —— 那之间已经绘制过一帧，用户会看到默认黑白闪一下再变。
 *
 * <p>取值优先级：桌面壳的缓存快照 → localStorage → 系统偏好（只有明暗有系统偏好，
 * 皮肤没有"系统偏好"这回事，读不到就是默认皮肤）。
 * 桌面壳走快照是因为它把缓存放在主进程（渲染进程读缓存要经 IPC，是异步的，
 * 首屏脚本等不了）；Web 走 localStorage。
 *
 * <p>皮肤值在这里**只校验"是个非空字符串"**，不查注册表：注册表会随皮肤一直增加，
 * 把它拖进这里等于让首屏脚本依赖一份会长大的数据。脏 id（例如皮肤被删掉了）的后果是
 * 匹配不到任何皮肤块 → 看起来就是默认主题，而 `ThemeProvider` 挂载后会把它纠正成默认皮肤
 * 并回写存储。
 */
const THEME_INIT_SCRIPT = `
(function () {
  var THEME_KEY = 'theme';
  var SKIN_KEY = 'skin';
  var THEME_STORAGE_KEY = '${THEME_STORAGE_KEY}';
  var SKIN_STORAGE_KEY = '${SKIN_STORAGE_KEY}';
  var DEFAULT_SKIN = '${DEFAULT_SKIN}';
  var snapshot = null;
  try {
    snapshot = window.paideiaDesktop && window.paideiaDesktop.cacheSnapshot;
  } catch (error) {
    /* 快照不可用就退回 localStorage，不能因此挡住首屏 */
  }
  function read(key, storageKey) {
    try {
      var fromSnapshot = snapshot && snapshot[key];
      if (typeof fromSnapshot === 'string' && fromSnapshot !== '') {
        return fromSnapshot;
      }
    } catch (error) {
      /* 继续往下试 */
    }
    try {
      return window.localStorage.getItem(storageKey);
    } catch (error) {
      return null;
    }
  }
  var stored = read(THEME_KEY, THEME_STORAGE_KEY);
  var prefersDark = false;
  try {
    prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch (error) {
    /* 拿不到系统偏好时按浅色处理 */
  }
  var dark = stored === 'dark' || (stored !== 'light' && prefersDark);
  document.documentElement.classList.toggle('dark', dark);
  var storedSkin = read(SKIN_KEY, SKIN_STORAGE_KEY);
  document.documentElement.setAttribute(
    'data-skin',
    typeof storedSkin === 'string' && storedSkin !== '' ? storedSkin : DEFAULT_SKIN,
  );
})();
`.trim()

/**
 * 把首屏脚本注入每个应用的 HTML。由 `@paideia/ui/vite` 导出，各应用在 vite 配置里加一行。
 *
 * <p>放在 ui 包里导出，而不是在四个 index.html 里各抄一份：四份拷贝必然漂移，
 * 而漂移的症状（某个端首屏闪一下）很难被注意到。
 */
export function themeInitPlugin(): Plugin {
  return {
    name: 'paideia:theme-init',
    transformIndexHtml() {
      return [
        {
          tag: 'script',
          children: THEME_INIT_SCRIPT,
          injectTo: 'head-prepend',
        },
      ]
    },
  }
}
