#!/usr/bin/env node
// 内容「AI 味」体检：给 content/ 下每篇文章打一个模板化分数，输出可排序的工作清单。
//
// 为什么存在：2026-11 全库统计发现 480 篇里 79% 带「常见误区」、212 篇带「小结」，
// 表面套话几乎为零但骨架高度统一——"读得懂，但作者不在场"。单看某一篇看不出问题，
// 只有把全库的栏目命中数摊开才看得见。所以先量化，再按分数从高到低改。
//
// 用法：
//   node scripts/audit-ai-flavor.mjs                    # 全库，按分数降序打印前 60 行
//   node scripts/audit-ai-flavor.mjs --all              # 打印全部
//   node scripts/audit-ai-flavor.mjs --json worklist.json
//   node scripts/audit-ai-flavor.mjs --dir content/reading/docker --all
//
// 分数只做排序，不是质量判决：高分表示"模板特征多且没有人味信号"，
// 是否要改还得人看一遍。负分通常是好书评/源码解读那类，别动。

import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const ROOT = process.cwd();
const TEMPLATE_SECTIONS = [
  ['常见误区', 3],
  ['小结', 2],
  ['这一课你要会', 2],
  ['这一关在解决什么', 2],
  ['先把结论摆出来', 2],
  ['讲解', 1],
  ['检查一下', 1],
  ['动手', 1],
  ['带走一句', 1],
];

// 人味信号：有这些说明作者真在正文里出现过，扣分。
const HUMAN_SIGNALS = [
  [/我(第一次|当时|踩|试|遇到|以为|后来|自己|们线上|的项目)/g, 4],
  [/踩坑|踩过|翻车|白做/g, 3],
  [/实测|亲测|测过/g, 1],
];
const EVIDENCE_SIGNALS = [
  [/\b[\w./-]+\.(ts|tsx|js|java|py|go|rs|sql|yml|yaml|toml|md):\d+/g, 3],
  [/(Exception|Error:|error TS\d+|panic:|exit status \d|Caused by:)/g, 2],
];

function parseArgs(argv) {
  const options = { all: false, json: null, dir: 'content' };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--all') options.all = true;
    else if (arg === '--json') options.json = argv[++i];
    else if (arg === '--dir') options.dir = argv[++i];
  }
  return options;
}

async function* walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(full);
    else if (entry.name.endsWith('.md') && entry.name !== 'README.md') yield full;
  }
}

function score(text) {
  const reasons = [];
  let value = 0;
  for (const [needle, points] of TEMPLATE_SECTIONS) {
    const hits = text.split(needle).length - 1;
    if (hits > 0) {
      value += points;
      reasons.push(`+${points} ${needle}×${hits}`);
    }
  }
  for (const [re, points] of HUMAN_SIGNALS) {
    const hits = (text.match(re) ?? []).length;
    if (hits > 0) value -= points;
    reasons.push(`-${points} 人味命中${hits}`);
  }
  for (const [re, points] of EVIDENCE_SIGNALS) {
    const hits = (text.match(re) ?? []).length;
    if (hits > 0) value -= points;
    reasons.push(`-${points} 硬证据${hits}`);
  }
  const headings = (text.match(/^##\s+/gm) ?? []).length;
  return { value, headings, reasons };
}

const options = parseArgs(process.argv.slice(2));
const files = [];
for await (const file of walk(path.join(ROOT, options.dir))) {
  const text = await readFile(file, 'utf8');
  const { value, headings, reasons } = score(text);
  files.push({
    file: path.relative(ROOT, file).replaceAll('\\', '/'),
    score: value,
    headings,
    bytes: Buffer.byteLength(text),
    reasons: reasons.filter((reason) => !reason.endsWith('命中0')),
  });
}

files.sort((a, b) => b.score - a.score || b.bytes - a.bytes);
if (options.json) {
  await writeFile(path.join(ROOT, options.json), JSON.stringify(files, null, 2), 'utf8');
  console.log(`已写出 ${files.length} 条 → ${options.json}`);
}

const shown = options.all ? files : files.slice(0, 60);
for (const item of shown) {
  console.log(`${String(item.score).padStart(3)}  ${item.file}  (小节 ${item.headings}, ${(item.bytes / 1024).toFixed(1)}KB)`);
}
if (!options.all) console.log(`\n共 ${files.length} 篇，上面是分数最高的 ${shown.length} 篇（--all 看全部）`);
