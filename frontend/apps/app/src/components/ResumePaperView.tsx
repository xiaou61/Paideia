import {
  readResumeTemplate,
  RESUME_TEMPLATES,
  ResumeSheet,
  saveResumeTemplate,
  type ResumeData,
  type ResumeTemplateId,
} from '@paideia/resume'
import { Button } from '@paideia/ui'
import { ArrowLeft, Printer } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'

/**
 * 简历纸张视图：工具栏（返回 / 版式 / 打印）+ A4 纸面。
 *
 * <p>预览页与打印页共用它——两页的差别只在数据从哪来（主简历 / 定稿版本），
 * 纸面与打印规则必须只有一份。
 *
 * <p><b>刻意不包 AppShell</b>：打印时页头、导航、助手面板都不该出现。工具栏用
 * `data-print-hide` 在 `@media print` 下隐藏。
 *
 * <p>版式选择存 localStorage（`@paideia/resume` 的 read/saveResumeTemplate），
 * 所以"预览里选的"与"打印时用的"是同一套。
 */
export function ResumePaperView({ data }: { data: ResumeData }) {
  const navigate = useNavigate()
  const [template, setTemplate] = useState<ResumeTemplateId>(() => readResumeTemplate())

  return (
    <div className="bg-background min-h-screen">
      <div
        className="mx-auto flex max-w-[210mm] flex-wrap items-center justify-between gap-2 px-4 py-4"
        data-print-hide
      >
        <Button variant="ghost" onClick={() => navigate(-1)}>
          <ArrowLeft className="size-4" />
          返回
        </Button>

        <div className="border-border flex items-center gap-1 rounded-lg border p-1" role="group" aria-label="版式">
          {RESUME_TEMPLATES.map((item) => (
            <Button
              key={item.id}
              size="sm"
              variant={template === item.id ? 'secondary' : 'ghost'}
              title={item.hint}
              aria-pressed={template === item.id}
              data-testid={`resume-template-${item.id}`}
              onClick={() => {
                setTemplate(item.id)
                saveResumeTemplate(item.id)
              }}
            >
              {item.name}
            </Button>
          ))}
        </div>

        <Button onClick={() => window.print()}>
          <Printer className="size-4" />
          打印 / 存为 PDF
        </Button>
      </div>

      <p className="text-muted-foreground mx-auto mb-3 max-w-[210mm] px-4 text-xs" data-print-hide>
        打印时在对话框里关掉「页眉和页脚」，日期和网址才不会被印上去。
      </p>

      {/* 纸张固定白底黑字：深色主题下打印不会变成白字白纸 */}
      <article
        className={`resume-print mx-auto max-w-[210mm] overflow-hidden bg-white text-neutral-900 shadow-sm ${
          template === 'sidebar' ? '' : 'px-8 py-10'
        }`}
        data-testid="resume-print"
      >
        <ResumeSheet data={data} template={template} />
      </article>

      <div className="h-10" data-print-hide />

      <style>{`
        @media print {
          @page { size: A4; margin: 12mm; }
          body { background: #fff !important; }
          [data-print-hide] { display: none !important; }
          .resume-print { max-width: none; padding: 0 !important; box-shadow: none !important; }
          /* 版式里有中性灰（侧栏底、小节标题），统一压成黑白，任何主题与打印机都可读 */
          .resume-print, .resume-print * { color: #000 !important; background: transparent !important; border-color: #bbb !important; }
          .resume-entry { break-inside: avoid; }
        }
      `}</style>
    </div>
  )
}
