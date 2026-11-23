import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * e2e 拉起本地后端时需要的「测试库在哪」。
 *
 * <p>学习者端与管理端的 `playwright.config.ts` 原本各抄了一份（约 18 行），已经漂移出两处差异：
 * `CONFIG_DIR` 一个用 `import.meta.dirname` 兜底 `process.cwd()`、另一个用 `fileURLToPath`；
 * `DB_USER` 的兜底一个 `'paideia_e2e'`、一个空串。差异本身不致命，但**两处各自演化**才是问题：
 * 改了一边、另一边照旧，表现是"某一端总是连错库"。所以抽到这里共用一份。
 *
 * <p>路径一律以**本文件位置**为基准，不用 `process.cwd()`：Playwright 的 `webServer.cwd`
 * 是相对配置文件目录解析的，而 `process.cwd()` 取决于从哪个目录敲的命令——两者不一致时，
 * 解析出来的 env 文件路径就是错的（表现为"文件明明在，却说缺少测试库连接"）。
 */

/** 本文件所在目录：`frontend/scripts`。 */
export const SCRIPTS_DIR = dirname(fileURLToPath(import.meta.url))

/** 仓库根目录。 */
export const REPO_ROOT = join(SCRIPTS_DIR, '..', '..')

/** 测试库连接文件（在 `.gitignore` 里，口令不入库）。 */
const LOCAL_DB_ENV_PATH = join(REPO_ROOT, 'backend', 'config', 'remote-db.local.env')

/** 解析 `export KEY=VALUE` 形式的 env 文件；读不到就返回空表（交给调用方报错）。 */
export function localDbEnv(): Record<string, string> {
  try {
    const entries: Array<[string, string]> = []
    for (const line of readFileSync(LOCAL_DB_ENV_PATH, 'utf8').split(/\r?\n/)) {
      const matched = /^export\s+([A-Z0-9_]+)=['"]?([^'"]*)['"]?$/.exec(line.trim())
      if (matched !== null) {
        entries.push([matched[1] ?? '', matched[2] ?? ''])
      }
    }
    return Object.fromEntries(entries)
  } catch {
    return {}
  }
}

const LOCAL_DB_ENV = localDbEnv()

/** 环境变量优先，其次是被忽略的 env 文件：临时指向别的库时不必改文件。 */
export const DB_URL = process.env.PAIDEIA_TEST_DB_URL ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_URL ?? ''
export const DB_USER = process.env.PAIDEIA_TEST_DB_USER ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_USER ?? 'paideia_e2e'
export const DB_PASSWORD = process.env.PAIDEIA_TEST_DB_PASSWORD ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_PASSWORD ?? ''

/**
 * 要起本地后端却拿不到库连接时，在**读配置**这一步就说清楚，别等 Spring 起不来再猜。
 *
 * <p>不打本地后端的情形（`E2E_BASE_URL` 指向已部署实例、`PWA_BASE_URL` 指向纯静态预览）
 * 不需要库，所以由调用方把这两个开关传进来。
 */
export function requireTestDatabase(options: { remote: boolean; pwa?: boolean }): void {
  if (options.remote || options.pwa === true || DB_URL !== '') {
    return
  }
  throw new Error(
    '缺少测试库连接：backend/config/remote-db.local.env 不存在。请找回该文件，或先 `source` 它再跑。',
  )
}

/**
 * 起本地后端时注入给 Spring 的数据源与迁移开关。
 *
 * <p>两处覆盖都必要：① 数据源指向专用测试库（e2e 会注册账号、造记录，不该写进开发库）；
 * ② 额外加载 `db/devdata`——种子账号在那里，它不在任何 profile 的默认迁移目录里。
 */
export function springEnvForLocalBackend(allowedOrigins: string): Record<string, string> {
  return {
    // forceConnectionTimeZoneToSession 与 connectionTimeZone 必须成对（服务器 MySQL 会话是 +08:00），
    // 否则建表默认值写进去的是本地钟点、驱动按 UTC 解释，时间整体快 8 小时。
    SPRING_DATASOURCE_URL: DB_URL,
    SPRING_DATASOURCE_USERNAME: DB_USER,
    SPRING_DATASOURCE_PASSWORD: DB_PASSWORD,
    SPRING_FLYWAY_LOCATIONS: 'classpath:db/migration,classpath:db/devdata',
    // 测试库被三种迁移配置共用：夹具的 V900 会出现在这个库里，不忽略就起不来。
    SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS: '*:future,*:missing',
    SPRING_FLYWAY_OUT_OF_ORDER: 'true',
    // 浏览器对同源 POST 也会带 Origin 头，后端按来源判定，未列入直接 403。
    // Actuator 端点另有独立的一份 CORS 配置，两处都要给。
    PAIDEIA_WEB_CORS_ALLOWED_ORIGINS: allowedOrigins,
    MANAGEMENT_ENDPOINTS_WEB_CORS_ALLOWED_ORIGINS: allowedOrigins,
  }
}
