#!/usr/bin/env node
/**
 * 生成手绘图用的中文手写字体子集（WORK-051）。
 *
 * 完整小赖字体 22 MB，不可能进仓库；而 sketch 块里的标签**就写在内容里**，
 * 所以按"内容里出现过的字符"裁子集，恰好等于作者用到的集合。
 *
 * 用法：node scripts/build-sketch-font.mjs
 * 新增内容后重跑一次。漏跑不会报错——缺的字回退系统字体（见 globals.css 的 font-family 回退链）。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(path.join(ROOT, 'frontend', 'package.json'))
const subsetFont = require('subset-font')

const FULL_FONT_URL =
  'https://github.com/ChewKeanHo/visuals-fonts-xiaolai/releases/download/v1.0.0/Xiaolai-Regular.ttf'
const CACHE = path.join(os.tmpdir(), 'paideia-xiaolai-full.ttf')
const OUT = path.join(ROOT, 'frontend/packages/ui/src/markdown/renderers/sketch/Xiaolai.woff2')

/** 图里一定会用到、但内容里未必出现的字符：ASCII 与中文标点、箭头、对错号。 */
const ALWAYS =
  ' !"#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~' +
  '，。、；：？！（）《》〈〉【】「」『』…—·～＋－＝％／＼　' +
  '→←↑↓↔⇒✓✗×÷≤≥≠±'

function collectText() {
  const chars = new Set(ALWAYS)
  const walk = (dir, ext) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full, ext)
      } else if (entry.name.endsWith(ext)) {
        for (const char of fs.readFileSync(full, 'utf8')) {
          chars.add(char)
        }
      }
    }
  }
  walk(path.join(ROOT, 'content'), '.md')
  walk(path.join(ROOT, 'backend/paideia-app/src/main/resources/db/devdata'), '.sql')
  return [...chars].join('')
}

async function readFullFont() {
  if (fs.existsSync(CACHE)) {
    return fs.readFileSync(CACHE)
  }
  console.log(`下载完整字体（约 22 MB）… ${FULL_FONT_URL}`)
  const response = await fetch(FULL_FONT_URL)
  if (!response.ok) {
    throw new Error(`下载失败：HTTP ${response.status}`)
  }
  const buffer = Buffer.from(await response.arrayBuffer())
  fs.writeFileSync(CACHE, buffer)
  return buffer
}

const text = collectText()
const full = await readFullFont()
const subset = await subsetFont(full, text, { targetFormat: 'woff2' })

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, subset)

const cjk = [...text].filter((char) => char >= '\u4e00' && char <= '\u9fff').length
console.log(`字符集：共 ${[...text].length} 个（其中汉字 ${cjk}）`)
console.log(`输出：${path.relative(ROOT, OUT)}  ${(subset.length / 1024).toFixed(0)} KB`)
