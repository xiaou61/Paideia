import type { ReactNode } from 'react'

import type { ResumeData } from './types'

/**
 * 简历纸张渲染：三套版式，同一份数据。
 *
 * <p>版式参考 Reactive Resume（MIT）的 Onyx / Azurill 与 JSON Resume 主题生态的单栏惯例。
 * **没有搬它们的代码**：Reactive Resume v5 的模板已经是 `@react-pdf` 专用体系，搬进来要连
 * react-pdf 一起引，而中文还要额外内嵌 CJK 字体（5MB+）；浏览器的"打印 / 存为 PDF"本来
 * 就负责 A4 分页，这里只写 HTML + Tailwind。
 *
 * <p><b>纸张固定白底黑字，不跟随应用主题</b>：深色主题下用 `text-foreground` 打印会得到
 * 白字白纸，而纸张本来也不该跟着应用换色。
 *
 * <p>`break-inside: avoid` 由调用方的打印样式给 `.resume-entry`。
 */

export type ResumeTemplateId = 'classic' | 'sidebar' | 'compact'

export interface ResumeTemplateInfo {
  id: ResumeTemplateId
  name: string
  hint: string
}

export const RESUME_TEMPLATES: readonly ResumeTemplateInfo[] = [
  { id: 'classic', name: '简洁', hint: '单栏，信息一行一条，最保险' },
  { id: 'sidebar', name: '侧栏', hint: '左侧信息栏 + 右侧经历，压缩到一页更轻松' },
  { id: 'compact', name: '紧凑', hint: '单栏小字号，经历多的人更合适' },
]

const TEMPLATE_KEY = 'paideia:resume-template'

/** 读上次选的版式。存的值不认识（改过 key、旧版本）时退回 `classic`。 */
export function readResumeTemplate(): ResumeTemplateId {
  try {
    const saved = localStorage.getItem(TEMPLATE_KEY)
    return RESUME_TEMPLATES.some((item) => item.id === saved) ? (saved as ResumeTemplateId) : 'classic'
  } catch {
    // 隐私模式/桌面端禁存储时只是记不住选择，不影响打印
    return 'classic'
  }
}

export function saveResumeTemplate(id: ResumeTemplateId): void {
  try {
    localStorage.setItem(TEMPLATE_KEY, id)
  } catch {
    // 同上：记不住就记不住
  }
}

export function ResumeSheet({ data, template = 'classic' }: { data: ResumeData; template?: ResumeTemplateId }) {
  if (template === 'sidebar') {
    return <SidebarSheet data={data} />
  }
  if (template === 'compact') {
    return <CompactSheet data={data} />
  }
  return <ClassicSheet data={data} />
}

/** 联系方式一行：空值不占位。 */
function contactLine(data: ResumeData): string {
  return [data.basics.phone, data.basics.email, data.basics.city, ...data.basics.links]
    .filter((value) => value !== '')
    .join(' · ')
}

function YearRange({ start, end }: { start: string; end: string }) {
  return (
    <p className="shrink-0 text-[11px] text-paper-muted">
      {start} - {end}
    </p>
  )
}

/** 单栏（默认）。信息密度最低、最像传统中文简历。 */
function ClassicSheet({ data }: { data: ResumeData }) {
  const contact = contactLine(data)
  return (
    <div className="space-y-5 text-[13px] text-paper-ink">
      <header className="border-b border-paper-line pb-3">
        <h1 className="text-2xl font-semibold">{data.basics.name}</h1>
        {contact !== '' && <p className="mt-1">{contact}</p>}
        {data.intent.role !== '' && <p className="mt-1">求职意向：{data.intent.role}</p>}
      </header>

      {data.work.length > 0 && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">工作与实习经历</h2>
          {data.work.map((item, index) => (
            <div key={index} className="resume-entry mb-3">
              <div className="flex items-baseline justify-between gap-2">
                <p className="font-medium">
                  {item.company}
                  {item.title !== '' && <span> · {item.title}</span>}
                </p>
                <YearRange start={item.start} end={item.end} />
              </div>
              {item.summary !== '' && <p>{item.summary}</p>}
              <ul className="ml-4 list-disc">
                {item.bullets.map((bullet, bulletIndex) => (
                  <li key={bulletIndex}>{bullet}</li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}

      {data.projects.length > 0 && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">项目经历</h2>
          {data.projects.map((item, index) => (
            <div key={index} className="resume-entry mb-3">
              <div className="flex items-baseline justify-between gap-2">
                <p className="font-medium">
                  {item.name}
                  {item.role !== '' && <span> · {item.role}</span>}
                </p>
                {item.period !== '' && <p className="shrink-0 text-[11px] text-paper-muted">{item.period}</p>}
              </div>
              <ul className="ml-4 list-disc">
                {item.bullets.map((bullet, bulletIndex) => (
                  <li key={bulletIndex}>{bullet}</li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}

      {data.education.length > 0 && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">教育经历</h2>
          {data.education.map((item, index) => (
            <div key={index} className="resume-entry mb-2">
              <div className="flex items-baseline justify-between gap-2">
                <p>
                  {item.school}
                  {item.major !== '' && <span> · {item.major}</span>}
                  {item.degree !== '' && <span> · {item.degree}</span>}
                </p>
                <YearRange start={item.start} end={item.end} />
              </div>
              {item.highlights.length > 0 && (
                <ul className="ml-4 list-disc">
                  {item.highlights.map((highlight, highlightIndex) => (
                    <li key={highlightIndex}>{highlight}</li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </section>
      )}

      {data.skills.length > 0 && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">技能</h2>
          <ul className="space-y-1">
            {data.skills.map((group, index) => (
              <li key={index}>
                <span className="font-medium">{group.group}</span>：{group.items.join('、')}
              </li>
            ))}
          </ul>
        </section>
      )}

      {data.awards.length > 0 && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">证书与奖项</h2>
          <ul className="list-disc">
            {data.awards.map((item, index) => (
              <li key={index} className="ml-4">
                {item.name}
                {item.issuer !== '' && <span> · {item.issuer}</span>}
                {item.date !== '' && <span> · {item.date}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {data.selfEvaluation !== '' && (
        <section>
          <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">自我评价</h2>
          <p className="whitespace-pre-line">{data.selfEvaluation}</p>
        </section>
      )}
    </div>
  )
}

/** 侧栏：左窄栏放"静态信息"，右主栏放"要读的经历"。 */
function SidebarSheet({ data }: { data: ResumeData }) {
  const contact = contactLine(data)
  return (
    <div className="flex items-stretch text-[12px] text-paper-ink">
      <aside className="w-[58mm] shrink-0 space-y-5 border-r border-paper-line bg-paper-rail px-4 py-6">
        <h1 className="text-xl font-semibold leading-tight">{data.basics.name}</h1>

        {contact !== '' && (
          <div>
            <h2 className="mb-1 text-[10px] font-semibold tracking-widest text-paper-muted">联系方式</h2>
            <p className="break-words">{contact}</p>
          </div>
        )}

        {(data.intent.role !== '' || data.intent.city !== '' || data.intent.availability !== '') && (
          <div>
            <h2 className="mb-1 text-[10px] font-semibold tracking-widest text-paper-muted">求职意向</h2>
            {data.intent.role !== '' && <p className="font-medium">{data.intent.role}</p>}
            {data.intent.city !== '' && <p>{data.intent.city}</p>}
            {data.intent.availability !== '' && <p>{data.intent.availability}</p>}
          </div>
        )}

        {data.skills.length > 0 && (
          <div>
            <h2 className="mb-2 text-[10px] font-semibold tracking-widest text-paper-muted">技能</h2>
            {data.skills.map((group, index) => (
              <div key={index} className="mb-2">
                <p className="font-medium">{group.group}</p>
                <p>{group.items.join('、')}</p>
              </div>
            ))}
          </div>
        )}

        {data.education.length > 0 && (
          <div>
            <h2 className="mb-2 text-[10px] font-semibold tracking-widest text-paper-muted">教育经历</h2>
            {data.education.map((item, index) => (
              <div key={index} className="mb-2">
                <p className="font-medium">{item.school}</p>
                <p>
                  {[item.major, item.degree].filter((value) => value !== '').join(' · ')}
                </p>
                <p className="text-[11px] text-paper-muted">
                  {item.start} - {item.end}
                </p>
                {item.highlights.length > 0 && (
                  <ul className="ml-3 list-disc">
                    {item.highlights.map((highlight, highlightIndex) => (
                      <li key={highlightIndex}>{highlight}</li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        )}

        {data.awards.length > 0 && (
          <div>
            <h2 className="mb-2 text-[10px] font-semibold tracking-widest text-paper-muted">证书与奖项</h2>
            {data.awards.map((item, index) => (
              <div key={index} className="mb-1">
                <p className="font-medium">{item.name}</p>
                <p className="text-[11px] text-paper-muted">
                  {[item.issuer, item.date].filter((value) => value !== '').join(' · ')}
                </p>
              </div>
            ))}
          </div>
        )}
      </aside>

      <div className="min-w-0 flex-1 space-y-5 px-6 py-6">
        {data.work.length > 0 && (
          <section>
            <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">工作与实习经历</h2>
            {data.work.map((item, index) => (
              <div key={index} className="resume-entry mb-3">
                <p className="font-medium">
                  {item.company}
                  {item.title !== '' && <span> · {item.title}</span>}
                </p>
                <YearRange start={item.start} end={item.end} />
                {item.summary !== '' && <p>{item.summary}</p>}
                <ul className="ml-4 list-disc">
                  {item.bullets.map((bullet, bulletIndex) => (
                    <li key={bulletIndex}>{bullet}</li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        )}

        {data.projects.length > 0 && (
          <section>
            <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">项目经历</h2>
            {data.projects.map((item, index) => (
              <div key={index} className="resume-entry mb-3">
                <p className="font-medium">
                  {item.name}
                  {item.role !== '' && <span> · {item.role}</span>}
                  {item.period !== '' && <span className="ml-2 text-[11px] font-normal text-paper-muted">{item.period}</span>}
                </p>
                <ul className="ml-4 list-disc">
                  {item.bullets.map((bullet, bulletIndex) => (
                    <li key={bulletIndex}>{bullet}</li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        )}

        {data.selfEvaluation !== '' && (
          <section>
            <h2 className="mb-2 text-[12px] font-semibold tracking-widest text-paper-muted">自我评价</h2>
            <p className="whitespace-pre-line">{data.selfEvaluation}</p>
          </section>
        )}
      </div>
    </div>
  )
}

/** 紧凑：字号小一档、小节标题带下划线，适合经历多、要压到一页。 */
function CompactSheet({ data }: { data: ResumeData }) {
  const contact = contactLine(data)
  return (
    <div className="space-y-3 text-[11.5px] leading-snug text-paper-ink">
      <header className="flex items-baseline justify-between gap-3 border-b border-paper-line pb-2">
        <div>
          <h1 className="text-xl font-semibold leading-tight">{data.basics.name}</h1>
          {data.intent.role !== '' && <p className="text-[11px] text-paper-subtle">求职意向：{data.intent.role}</p>}
        </div>
        {contact !== '' && <p className="max-w-[55%] text-right text-[11px] text-paper-subtle">{contact}</p>}
      </header>

      {data.work.length > 0 && (
        <CompactSection title="工作与实习经历">
          {data.work.map((item, index) => (
            <div key={index} className="resume-entry mb-2">
              <div className="flex items-baseline justify-between gap-2">
                <p className="font-medium">
                  {item.company}
                  {item.title !== '' && <span> · {item.title}</span>}
                </p>
                <YearRange start={item.start} end={item.end} />
              </div>
              {item.summary !== '' && <p className="text-paper-subtle">{item.summary}</p>}
              <ul className="ml-4 list-disc">
                {item.bullets.map((bullet, bulletIndex) => (
                  <li key={bulletIndex}>{bullet}</li>
                ))}
              </ul>
            </div>
          ))}
        </CompactSection>
      )}

      {data.projects.length > 0 && (
        <CompactSection title="项目经历">
          {data.projects.map((item, index) => (
            <div key={index} className="resume-entry mb-2">
              <div className="flex items-baseline justify-between gap-2">
                <p className="font-medium">
                  {item.name}
                  {item.role !== '' && <span> · {item.role}</span>}
                </p>
                {item.period !== '' && <p className="shrink-0 text-[11px] text-paper-muted">{item.period}</p>}
              </div>
              <ul className="ml-4 list-disc">
                {item.bullets.map((bullet, bulletIndex) => (
                  <li key={bulletIndex}>{bullet}</li>
                ))}
              </ul>
            </div>
          ))}
        </CompactSection>
      )}

      {data.education.length > 0 && (
        <CompactSection title="教育经历">
          {data.education.map((item, index) => (
            <div key={index} className="resume-entry mb-1">
              <div className="flex items-baseline justify-between gap-2">
                <p>
                  {item.school}
                  {item.major !== '' && <span> · {item.major}</span>}
                  {item.degree !== '' && <span> · {item.degree}</span>}
                </p>
                <YearRange start={item.start} end={item.end} />
              </div>
              {item.highlights.length > 0 && <p className="text-paper-subtle">{item.highlights.join('；')}</p>}
            </div>
          ))}
        </CompactSection>
      )}

      {data.skills.length > 0 && (
        <CompactSection title="技能">
          {data.skills.map((group, index) => (
            <p key={index} className="resume-entry">
              <span className="font-medium">{group.group}</span>：{group.items.join('、')}
            </p>
          ))}
        </CompactSection>
      )}

      {data.awards.length > 0 && (
        <CompactSection title="证书与奖项">
          <p className="resume-entry">
            {data.awards
              .map((item) => [item.name, item.issuer, item.date].filter((value) => value !== '').join(' · '))
              .join('；')}
          </p>
        </CompactSection>
      )}

      {data.selfEvaluation !== '' && (
        <CompactSection title="自我评价">
          <p className="whitespace-pre-line">{data.selfEvaluation}</p>
        </CompactSection>
      )}
    </div>
  )
}

function CompactSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-1 border-b border-paper-line pb-0.5 text-[10.5px] font-semibold tracking-widest text-paper-muted">
        {title}
      </h2>
      {children}
    </section>
  )
}
