#!/usr/bin/env node
/**
 * 生成**结构性的**依赖图：后端 Maven 模块、前端 pnpm 包。
 *
 * **这类图不让 AI 画**：有几个模块、谁依赖谁，答案在 pom 与 package.json 里，不在语义里。
 * 让模型凭印象复述一定会漏会错，而且错得很像真的。脚本产出的图永远等于事实。
 * 叙事性的图（请求链路、状态机、业务流程）才由 AI 写 mermaid。
 *
 * 用法：
 *   node scripts/gen-arch-diagram.mjs          # 重写四张结构图（01/04/05/06）
 *   node scripts/gen-arch-diagram.mjs --check  # 只比对：四张结构图 + README 的计数（防漂移）
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND = join(ROOT, 'backend');
const FRONTEND = join(ROOT, 'frontend');
const ARCH = join(ROOT, 'docs', 'architecture');
const OWN_GROUP = 'io.github.xiaou61';

const block = (xml, tag) => {
  const m = xml.match(new RegExp(`<${tag}>[\\s\\S]*?</${tag}>`));
  return m ? m[0] : '';
};

/** 只取 <dependencies> 里的本仓模块，且排除 dependencyManagement / build 里的同名块。 */
function moduleDeps(pomPath, self) {
  let xml;
  try {
    xml = readFileSync(pomPath, 'utf8');
  } catch {
    return [];
  }
  // 移除会被误当成"模块依赖"的区块（dependencyManagement 是声明、build 是插件依赖）
  xml = xml.replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '');
  xml = xml.replace(/<build>[\s\S]*?<\/build>/g, '');
  const deps = new Set();
  for (const dep of block(xml, 'dependencies').matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const body = dep[1];
    const group = body.match(/<groupId>([^<]+)<\/groupId>/)?.[1]?.trim();
    const artifact = body.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1]?.trim();
    if (group === OWN_GROUP && artifact && artifact !== self) {
      // 同一模块常被声明两次（正式依赖 + test-jar 测试夹具）。依赖图上是一条边。
      deps.add(artifact);
    }
  }
  return [...deps];
}

const HEADER = (source, hint = '依赖声明') =>
  [
    '<!--',
    '  本文件由 scripts/gen-arch-diagram.mjs 生成，**不要手改**。',
    `  手改会让 \`node scripts/gen-arch-diagram.mjs --check\` 失败（那是刻意的：这张图必须等于${source}的事实）。`,
    `  要改图就改${hint}或改生成脚本，然后重跑生成。`,
    '-->',
  ].join('\n');

// ── 后端：Maven 模块 ────────────────────────────────────────────────────────
function backendDoc() {
  const rootPom = readFileSync(join(BACKEND, 'pom.xml'), 'utf8');
  // 模块声明顺序本身就是"由底向上"的（platform 在前、app 在最后），保留它让布局贴近分层
  const modules = [...block(rootPom, 'modules').matchAll(/<module>([^<]+)<\/module>/g)].map((m) => m[1].trim());
  if (modules.length === 0) {
    console.error('backend/pom.xml 里没有 <modules>，无法生成。');
    process.exit(2);
  }

  const edges = [];
  const rows = [];
  for (const module of modules) {
    const deps = moduleDeps(join(BACKEND, module, 'pom.xml'), module);
    for (const dep of deps) {
      edges.push([module, dep]);
    }
    rows.push({ module, deps });
  }

  const id = (module) => module.replace(/^paideia-/, '');
  const lines = [];
  lines.push(HEADER(' pom '));
  lines.push('');
  lines.push('# 后端模块依赖');
  lines.push('');
  lines.push(`由 \`backend/pom.xml\` 的 ${modules.length} 个模块生成。箭头方向是**依赖方 → 被依赖方**，`);
  lines.push('所以箭头指向的模块可以不知道调用者的存在（依赖只许单向下行，`spring-modulith` 在构建期挡循环）。');
  lines.push('');
  lines.push('```mermaid');
  lines.push('flowchart TB');
  for (const { module } of rows) {
    lines.push(`  ${id(module)}["${module}"]`);
  }
  for (const [from, to] of edges) {
    lines.push(`  ${id(from)} --> ${id(to)}`);
  }
  lines.push('```');
  lines.push('');
  lines.push('## 各模块的直接依赖');
  lines.push('');
  lines.push('| 模块 | 直接依赖 |');
  lines.push('| --- | --- |');
  for (const { module, deps } of rows) {
    lines.push(`| \`${module}\` | ${deps.length === 0 ? '（无）' : deps.map((d) => `\`${d}\``).join('、')} |`);
  }
  lines.push('');
  return { output: lines.join('\n'), summary: `${modules.length} 个模块 / ${edges.length} 条依赖`,
    readme: { label: '后端模块数', pattern: /后端 (\d+) 个 Maven 模块/, values: [modules.length] } };
}

// ── 前端：pnpm workspace 包 ─────────────────────────────────────────────────
function frontendDoc() {
  const readPkg = (path) => JSON.parse(readFileSync(path, 'utf8'));
  // 顺序固定为 packages 先、apps 后，让布局接近"底层库在上、应用在下"
  const dirs = [];
  for (const dir of ['packages', 'apps']) {
    for (const name of readdirSync(join(FRONTEND, dir))) {
      const pkgPath = join(FRONTEND, dir, name, 'package.json');
      if (!existsSync(pkgPath)) {
        continue;
      }
      dirs.push({ dir, name, pkg: readPkg(pkgPath) });
    }
  }

  const edges = [];
  const rows = [];
  for (const { dir, name, pkg } of dirs) {
    const self = pkg.name;
    const deps = new Set();
    for (const key of ['dependencies', 'devDependencies', 'peerDependencies']) {
      for (const dep of Object.keys(pkg[key] ?? {})) {
        // 只保留 workspace 内互相依赖（版本写成 workspace:*）；外部包不画
        if (String(pkg[key][dep]).startsWith('workspace:') && dep !== self) {
          deps.add(dep);
        }
      }
    }
    for (const dep of deps) {
      edges.push([self, dep]);
    }
    rows.push({ self, dir, deps: [...deps].sort() });
  }

  const known = new Set(rows.map((r) => r.self));
  const missing = edges.filter(([, to]) => !known.has(to));
  if (missing.length > 0) {
    console.error('存在指向未收录包的依赖（pnpm-workspace 可能漏了目录）：', missing.map(([f, t]) => `${f} -> ${t}`).join(', '));
  }

  const id = (name) => name.replace(/^@paideia\//, '').replace(/[^a-zA-Z0-9]/g, '_');
  const lines = [];
  lines.push(HEADER(' package.json '));
  lines.push('');
  lines.push('# 前端包依赖');
  lines.push('');
  lines.push(`由 \`frontend/\` 的 ${rows.length} 个 workspace 包生成（\`packages/*\` 是库，\`apps/*\` 是可运行端）。`);
  lines.push('只画**包之间**的依赖，外部依赖（React、Vite 等）不画。箭头方向是**依赖方 → 被依赖方**。');
  lines.push('');
  lines.push('```mermaid');
  lines.push('flowchart TB');
  lines.push('  subgraph 库 packages');
  for (const r of rows.filter((r) => r.dir === 'packages')) {
    lines.push(`    ${id(r.self)}["${r.self.replace('@paideia/', '')}"]`);
  }
  lines.push('  end');
  lines.push('  subgraph 可运行端 apps');
  for (const r of rows.filter((r) => r.dir === 'apps')) {
    lines.push(`    ${id(r.self)}["${r.self.replace('@paideia/', '')}"]`);
  }
  lines.push('  end');
  for (const [from, to] of edges) {
    lines.push(`  ${id(from)} --> ${id(to)}`);
  }
  lines.push('```');
  lines.push('');
  lines.push('## 各包的直接依赖');
  lines.push('');
  lines.push('| 包 | 目录 | 直接依赖 |');
  lines.push('| --- | --- | --- |');
  for (const { self, dir, deps } of rows) {
    lines.push(`| \`${self}\` | \`${dir}\` | ${deps.length === 0 ? '（无）' : deps.map((d) => `\`${d}\``).join('、')} |`);
  }
  lines.push('');
  lines.push('`@paideia/desktop` 是 Tauri 2 外壳，它的"依赖"是构建期的 app 产物（脚本里 `--filter @paideia/app build:desktop`），');
  lines.push('不是包依赖，所以图上没有边。');
  lines.push('');
  return { output: lines.join('\n'), summary: `${rows.length} 个包 / ${edges.length} 条依赖`,
    readme: { label: '前端包数', pattern: /前端 (\d+) 个 workspace 包/, values: [rows.length] } };
}

// ── 数据库：表与外键 ────────────────────────────────────────────────────────
function databaseDoc() {
  const MIG_DIR = join(BACKEND, 'paideia-app', 'src', 'main', 'resources', 'db', 'migration');
  const files = readdirSync(MIG_DIR).filter((f) => f.endsWith('.sql')).sort();

  const tables = new Map(); // 表名 -> 定义它的迁移
  const fks = [];
  for (const file of files) {
    const sql = readFileSync(join(MIG_DIR, file), 'utf8');
    for (const m of sql.matchAll(/CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?/gi)) {
      if (!tables.has(m[1])) {
        tables.set(m[1], file);
      }
    }
    // 外键可能跨行写（CONSTRAINT ... FOREIGN KEY (...) \n REFERENCES ...）
    for (const m of sql.matchAll(/FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES\s+`?(\w+)`?/gi)) {
      // 归属表 = 同一文件里这段之前最近的 CREATE TABLE
      const before = sql.slice(0, m.index);
      const owners = [...before.matchAll(/CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?/gi)];
      const from = owners.length > 0 ? owners[owners.length - 1][1] : '?';
      fks.push({ from, column: m[1].replace(/`/g, '').trim(), to: m[2] });
    }
  }

  const byTable = new Map();
  for (const fk of fks) {
    if (!byTable.has(fk.from)) {
      byTable.set(fk.from, []);
    }
    byTable.get(fk.from).push(fk);
  }

  const connected = [...byTable.keys()].sort();
  const lines = [];
  lines.push(HEADER('迁移文件', '迁移文件'));
  lines.push('');
  lines.push('# 数据库表');
  lines.push('');
  lines.push(`由 \`backend/paideia-app/src/main/resources/db/migration/\` 的 ${files.length} 个迁移生成：`);
  lines.push(`共 **${tables.size} 张表**，其中只有 **${fks.length} 条外键**。`);
  lines.push('');
  lines.push('外键少是**刻意的**，不是遗漏：跨模块的关联（比如 `ai_memories_structured.user_id` → `users.id`）');
  lines.push('由应用层保证，不交给数据库。所以下面这张图只画**有真实外键约束**的关系，');
  lines.push('看不到关联不代表没关系——去读 mapper 或实体。');
  lines.push('');
  lines.push('```mermaid');
  lines.push('flowchart LR');
  for (const table of connected) {
    lines.push(`  ${table}["${table}"]`);
  }
  for (const fk of fks) {
    lines.push(`  ${fk.from} -->|${fk.column}| ${fk.to}`);
  }
  lines.push('```');
  lines.push('');
  lines.push('## 全部表');
  lines.push('');
  lines.push('| 表 | 迁移 | 外键 |');
  lines.push('| --- | --- | --- |');
  for (const table of [...tables.keys()].sort()) {
    const mine = byTable.get(table) ?? [];
    const desc = mine.length === 0 ? '（无）' : mine.map((fk) => `\`${fk.column}\` → \`${fk.to}\``).join('、');
    lines.push(`| \`${table}\` | \`${tables.get(table).replace(/\.sql$/, '')}\` | ${desc} |`);
  }
  lines.push('');
  return { output: lines.join('\n'), summary: `${tables.size} 张表 / ${fks.length} 条外键`,
    readme: { label: '表数与外键数', pattern: /(\d+) 张表与 (\d+) 条真实外键/, values: [tables.size, fks.length] } };
}

// ── API 端点清单 ────────────────────────────────────────────────────────────
function apiDoc() {
  const rows = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      // 只看 src/main：测试里有 FixtureItemController / ProbeController 这类夹具，
      // 它们不是对外暴露的端点，算进来会让清单虚高。
      if (entry.isDirectory()) {
        if (!['target', 'node_modules', 'src'].includes(entry.name)) {
          walk(full);
        } else if (entry.name === 'src') {
          const main = join(full, 'main');
          if (existsSync(main)) {
            walk(main);
          }
        }
      } else if (entry.name.endsWith('Controller.java')) {
        const src = readFileSync(full, 'utf8');
        const base = src.match(/@RequestMapping\("([^"]*)"\)/)?.[1] ?? '';
        const verbs = [...src.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\("([^"]*)"\))?/g)].map((m) => ({
          verb: m[1].toUpperCase(),
          path: m[2] ?? '',
        }));
        const module = full.replace(/\\/g, '/').split('/backend/')[1]?.split('/')[0] ?? '?';
        rows.push({ module, base, controller: entry.name.replace('.java', ''), verbs });
      }
    }
  };
  walk(join(BACKEND));

  rows.sort((a, b) => (a.module === b.module ? a.base.localeCompare(b.base) : a.module.localeCompare(b.module)));
  const total = rows.reduce((n, r) => n + r.verbs.length, 0);
  const modules = [...new Set(rows.map((r) => r.module))];

  const byAuth = (base) => {
    if (base.includes('/admin')) return '仅管理员';
    if (base.startsWith('/api/v1/auth') || base.includes('/public/')) return '免鉴权';
    return '登录即可';
  };

  const lines = [];
  lines.push(HEADER('控制器', '控制器'));
  lines.push('');
  lines.push('# API 端点清单');
  lines.push('');
  lines.push(`由后端 ${rows.length} 个 Controller 生成：**${total} 个端点**，分布在 ${modules.length} 个模块。`);
  lines.push('');
  lines.push('这是**结构性的清单**，不是手写文档——端点加一个这里就多一行，');
  lines.push('所以它不会像手写的接口文档那样过期。真正的授权规则在 `SecurityConfiguration`，');
  lines.push('下表最后一列只是按前缀推出的**粗分类**，碰到具体端点的判定以代码为准。');
  lines.push('');
  lines.push('| 模块 | 前缀 | 控制器 | 鉴权（按前缀推测） | 端点数 |');
  lines.push('| --- | --- | --- | --- | --- |');
  for (const r of rows) {
    lines.push(`| \`${r.module}\` | \`${r.base || '(根)'}\` | \`${r.controller}\` | ${byAuth(r.base)} | ${r.verbs.length} |`);
  }
  lines.push('');
  lines.push('## 按 HTTP 方法统计');
  lines.push('');
  const byVerb = new Map();
  for (const r of rows) {
    for (const v of r.verbs) {
      byVerb.set(v.verb, (byVerb.get(v.verb) ?? 0) + 1);
    }
  }
  lines.push('| 方法 | 数量 |');
  lines.push('| --- | --- |');
  for (const [verb, count] of [...byVerb.entries()].sort((a, b) => b[1] - a[1])) {
    lines.push(`| \`${verb}\` | ${count} |`);
  }
  lines.push('');
  return { output: lines.join('\n'), summary: `${total} 个端点 / ${rows.length} 个控制器`,
    readme: { label: '端点数与控制器数', pattern: /(\d+) 个端点 \/ (\d+) 个控制器/, values: [total, rows.length] } };
}

const targets = [
  { file: join(ARCH, '01-module-dependencies.md'), label: '01-module-dependencies.md', build: backendDoc, kind: '后端模块依赖图' },
  { file: join(ARCH, '04-frontend-packages.md'), label: '04-frontend-packages.md', build: frontendDoc, kind: '前端包依赖图' },
  { file: join(ARCH, '05-database-tables.md'), label: '05-database-tables.md', build: databaseDoc, kind: '数据库表清单' },
  { file: join(ARCH, '06-api-surface.md'), label: '06-api-surface.md', build: apiDoc, kind: 'API 端点清单' },
];

const check = process.argv.includes('--check');
let failed = false;
mkdirSync(ARCH, { recursive: true });
// 比较前先归一换行：Windows 上 core.autocrlf 会把检出变成 CRLF，而生成物是 LF——
// 不归一的话每个新 worktree 里 --check 都会假红（2026-11-02 在 worktree 实测）。
const normalizeEol = (text) => text.replace(/\r\n/g, '\n');
const built = [];
for (const target of targets) {
  const { output, summary, readme } = target.build();
  built.push(readme);
  if (check) {
    const current = existsSync(target.file) ? readFileSync(target.file, 'utf8') : '';
    if (normalizeEol(current) !== normalizeEol(output)) {
      console.error(`✗ docs/architecture/${target.label} 已漂移（${target.kind}与声明不一致）。`);
      console.error('  跑 `node scripts/gen-arch-diagram.mjs` 重新生成，再连同代码一起提交。');
      failed = true;
    } else {
      console.log(`✓ ${target.kind}与声明一致（${summary}）。`);
    }
  } else {
    writeFileSync(target.file, output, 'utf8');
    console.log(`✓ 已写出 docs/architecture/${target.label}（${summary}）。`);
  }
}
// README 是索引、不是生成物：这里只校验它的计数与事实一致，不改写它——
// 它带着散文明说这份存档"不包含什么"，生成化会把维护权一起拿走。
if (check) {
  const readmePath = join(ARCH, 'README.md');
  const readme = existsSync(readmePath) ? readFileSync(readmePath, 'utf8') : '';
  for (const { label, pattern, values } of built) {
    const matched = readme.match(pattern);
    if (!matched) {
      console.error(`✗ docs/architecture/README.md 里找不到「${label}」的计数（期望 ${values.join('/')}）。`);
      failed = true;
      continue;
    }
    const got = matched.slice(1).map(Number);
    if (got.some((value, index) => value !== values[index])) {
      console.error(`✗ docs/architecture/README.md 与事实不一致：${label} README=${got.join('/')}，事实=${values.join('/')}。改 README 的说明文字即可。`);
      failed = true;
    } else {
      console.log(`✓ README 计数一致（${label} ${values.join('/')}）。`);
    }
  }
}

if (failed) {
  process.exit(1);
}
