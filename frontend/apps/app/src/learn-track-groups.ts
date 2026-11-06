/**
 * 「从零开始」页的**主题分类**目录。
 *
 * <p>这是**呈现层的编辑分类**，不是产品数据：后端只给轨道（slug/名字/摘要/进度），
 * 这里把 slug 归到易读的主题下。加一条新轨道而这里还没配，它会落到「其他」——
 * 不会消失，也不会让页面报错，只是暂时没被归到更细的类里。
 *
 * <p>规则和 `TRACK_ICONS` 一致：按 slug 查，查不到有兜底。改分类只动这一个文件。
 */
export interface TrackGroupDef {
  key: string
  label: string
  /** 分类的一句话说明，显示在该分类标题下面。 */
  hint: string
  slugs: string[]
}

/** 顺序就是页面上分类的先后：先语言/数据库/后端，再到基础、算法、工程、运维，最后 AI 与精读。 */
export const TRACK_GROUPS: TrackGroupDef[] = [
  {
    key: 'lang',
    label: '编程语言',
    hint: '从零学一门语言：语法、面向对象、并发与工程化。',
    slugs: ['python-from-zero', 'java-from-zero', 'python-scripting'],
  },
  {
    key: 'db',
    label: '数据库',
    hint: '建表、查询、索引、事务与缓存。',
    slugs: ['mysql-from-zero', 'sql-practice', 'redis'],
  },
  {
    key: 'backend',
    label: '后端与框架',
    hint: 'Spring、JVM、并发，以及 Java 的项目与调试实战。',
    slugs: [
      'spring-from-zero',
      'spring',
      'spring-handmade',
      'spring-boot-project',
      'jvm',
      'concurrency',
      'java-debug',
      'java-project',
    ],
  },
  {
    key: 'frontend',
    label: '前端',
    hint: '从浏览器到组件化，再到构建、依赖、测试与发布：自己写出能维护、能上线的页面。',
    slugs: ['frontend-from-zero', 'frontend-engineering'],
  },
  {
    key: 'cs',
    label: '计算机基础',
    hint: '网络、操作系统与 Linux，程序真正跑起来的底座。',
    slugs: ['network-basics', 'os', 'linux-from-zero', 'linux'],
  },
  {
    key: 'algo',
    label: '算法与数据',
    hint: '复杂度、数据结构与数据分析的口径。',
    slugs: ['algorithms', 'data-analysis'],
  },
  {
    key: 'engineering',
    label: '工程与架构',
    hint: '边界、模块化、测试、Git 与系统设计，能维护、能上线。',
    slugs: ['architecture-from-zero', 'system-design', 'patterns', 'engineering', 'testing-from-zero', 'git'],
  },
  {
    key: 'ops',
    label: '运维与部署',
    hint: '容器、发布回滚、排障与把服务放到公网。',
    slugs: ['docker', 'docker-deploy', 'ops', 'linux-troubleshooting'],
  },
  {
    key: 'ai',
    label: 'AI 与智能体',
    hint: '大模型原理、智能体设计，以及 DSH 的插件化底盘。',
    slugs: ['ai', 'agents'],
  },
  {
    key: 'source',
    label: '源码与框架精读',
    hint: '拆开一个真实项目，学它内部是怎么做到的。',
    slugs: ['sprig-deepdive', 'dsh-core', 'open-source'],
  },
  {
    key: 'books',
    label: '书籍详解',
    hint: '一次读透一本经典书，把工程判断力建在前人的经验上。',
    slugs: ['books-clean-code', 'books-ddia', 'books-man-month', 'books-thinking'],
  },
  {
    key: 'security',
    label: '安全',
    hint: '信任边界、注入、越权与上线前的检查。',
    slugs: ['web-security'],
  },
]

/** 没被任何分类收录的轨道落在这里，保证「新加一条线」也不会从目录里消失。 */
export const OTHER_GROUP: TrackGroupDef = {
  key: 'other',
  label: '其他',
  hint: '还没归类的轨道。',
  slugs: [],
}

const BY_SLUG = new Map<string, TrackGroupDef>()
for (const group of TRACK_GROUPS) {
  for (const slug of group.slugs) BY_SLUG.set(slug, group)
}

export function groupKeyOf(slug: string): string {
  return BY_SLUG.get(slug)?.key ?? OTHER_GROUP.key
}

export function groupDefOf(key: string): TrackGroupDef {
  if (key === OTHER_GROUP.key) return OTHER_GROUP
  return TRACK_GROUPS.find((group) => group.key === key) ?? OTHER_GROUP
}
