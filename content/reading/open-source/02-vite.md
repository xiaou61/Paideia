---
title: Vite：为什么开发服务器能瞬间启动
category: open-source
order: 2
minutes: 6
level: beginner
status: published
---

## 一句话定位

**Vite 把"开发时"和"打包时"拆成两套东西**：开发时用浏览器原生 ESM 按需编译，打包时用 Rollup 做真正的打包。

```sketch
{
  "title": "开发一套，打包另一套",
  "rows": [
    [
      { "id": "dev", "title": "开发：浏览器原生 ESM", "sub": "改哪个文件编译哪个", "tone": "blue" },
      { "id": "build", "title": "打包：Rollup", "sub": "真正摇树、分块、压缩", "tone": "blue" },
      { "id": "why", "title": "所以开发热更新快", "sub": "但产物和开发时不是一条路", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "dev", "to": "build" },
    { "from": "build", "to": "why" }
  ]
}
```

## 它解决什么问题

Webpack 时代的痛点是：**项目越大，`npm start` 越慢。** 一个中型项目启动要 30 秒到几分钟，改一行代码热更新又要等几秒。等待时间随着项目规模增长——这很荒诞：你只想改一个按钮的颜色，却要等整个应用重新构建。

根因是 Webpack 的模式：**启动时把所有模块打包成一个 bundle**。哪怕你只改了 1% 的代码，它也要重新组织整个依赖图。

## 核心机制

1. **开发时不打包。** Vite 的 dev server 只做一件事：浏览器请求哪个文件，它就把那个文件编译好返回。浏览器原生支持 `import`，所以"打包"这件事在开发阶段根本不需要。
2. **依赖预构建（esbuild）。** 第三方依赖（`node_modules` 里那些 CommonJS 包）会被 esbuild 预先打成少量 ESM 文件，只做一次、缓存在 `node_modules/.vite`。这是它启动快的另一半：不是"什么都不做"，而是**把贵的部分做一次并缓存**。
3. **热更新走原生 HMR 边界。** 改一个文件，只让那道模块边界上的代码失效，不重建整个图。所以热更新的耗时与项目规模**基本无关**——这与 Webpack 的决定性差异就在这里。
4. **生产构建用 Rollup。** 开发时靠 ESM 省时间，生产时仍需要 tree-shaking、代码分割、压缩。Vite 不假装"ESM 就够了"——它把两件事分给两套工具，各做自己擅长的。
5. **配置文件就是 `vite.config.ts`**，不是"在一个大对象里配几十个 loader 和 plugin"。约定优于配置：`index.html` 是入口、`src/` 是源码、静态资源放 `public/`。

## 什么时候该用

- 任何新的前端项目（Vue/React/Svelte/纯 TS 都行）
- 已有 Webpack 项目想提速（迁移成本通常低于预期）
- 需要 SSR 时用它的上层框架（Nuxt/SvelteKit/Remix 都基于 Vite）
- 库开发：`vite build --lib` 能出 ESM + CJS 双格式

## 什么时候别用

- **需要 Webpack 生态的特定 loader**。老的 `file-loader`/自定义 loader 链在 Vite 里要改写成 plugin。
- **目标是极老的浏览器**（IE11）。Vite 的 dev 模式依赖原生 ESM，`@vitejs/plugin-legacy` 只能解决生产产物，dev 模式不支持。
- **构建产物要求单文件**。Vite 默认按 chunk 分割，强求单文件要额外配置。
- **项目已经跑得很好**。如果 Webpack 构建只要 3 秒，迁移的收益抵不上风险。

## 替代品

| 场景 | 替代 |
| --- | --- |
| 想更激进、不要打包器 | 直接上 ESM + importmap（适合极小项目或内部工具） |
| 需要更细的 chunk 控制 | Webpack、Rspack（Rust 重写，配置兼容 Webpack） |
| 库打包 | tsup、unbuild（都基于 esbuild/Rollup，配置更少） |
| 全栈框架 | Next.js（自带打包，不基于 Vite）、Nuxt（基于 Vite） |

## 出处

- **许可证**：MIT
- **仓库**：https://github.com/vitejs/vite
- **对应版本**：5.x（`rolldown-vite` 分支在 6.x 引入 Rust 打包器，行为有差异）

## 一个值得注意的边界

Vite 的"快"是**开发时**的快。生产构建依然要走完整打包，大项目的 `vite build` 一样可能要几十秒。把"启动 200ms"理解成"构建也快"是常见误读——这两件事在 Vite 里由不同的工具负责，速度特性完全不同。

本仓库的前端就是这个模型：`pnpm dev` 几乎瞬时，`pnpm build` 要几十秒（WORK-045 实测约 11–49 秒，取决于是否有 PWA 清单要生成）。
