import { Button, Spinner } from '@paideia/ui'
import { createResumeApi } from '@paideia/resume'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { ResumePaperView } from '../components/ResumePaperView'

const resumes = createResumeApi(api)

/**
 * 打印视图（WORK-018 REQ-021）。
 *
 * <p>只负责"取定稿版本"与两种失败态；纸面、版式切换与打印样式在
 * {@link ResumePaperView}，与预览页共用一份。
 */
export function ResumePrintPage() {
  const { versionId } = useParams<{ versionId: string }>()
  const navigate = useNavigate()
  const id = Number(versionId)

  const { data: version, isPending } = useQuery({
    queryKey: ['resume-version', id],
    queryFn: () => resumes.getVersion(id),
    enabled: Number.isFinite(id),
  })

  if (isPending) {
    return (
      <div className="flex justify-center py-20">
        <Spinner />
      </div>
    )
  }

  if (version === undefined || version.finalData === null) {
    return (
      <div className="mx-auto max-w-2xl py-20 text-center">
        <p>这个版本还没有定稿，无法打印。</p>
        <Button className="mt-4" variant="outline" onClick={() => navigate(-1)}>
          返回
        </Button>
      </div>
    )
  }

  return <ResumePaperView data={version.finalData} />
}
