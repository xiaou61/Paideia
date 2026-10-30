import { Spinner } from '@paideia/ui'
import { createResumeApi } from '@paideia/resume'
import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router'

import { api } from '../api'
import { ResumePaperView } from '../components/ResumePaperView'

const resumes = createResumeApi(api)

/**
 * 主简历预览（`/resume/:id/preview`）。
 *
 * <p>存在的理由：打印页只认**定稿版本**，而"我想看看这版简历长什么样"不该先花一次
 * 模型调用。这里直接渲染编辑器里那份主简历，所以选版式、看效果、存 PDF 都不用先优化。
 *
 * <p>`queryKey` 与编辑器相同：从编辑器点"预览"进来时数据已在缓存里，开关版式不重新请求。
 */
export function ResumePreviewPage() {
  const { id } = useParams<{ id: string }>()
  const documentId = Number(id)

  const { data: document, isPending } = useQuery({
    queryKey: ['resume', documentId],
    queryFn: () => resumes.get(documentId),
    enabled: Number.isFinite(documentId),
  })

  if (isPending) {
    return (
      <div className="flex justify-center py-20">
        <Spinner />
      </div>
    )
  }

  if (document === undefined) {
    return <p className="py-20 text-center">没有找到这份简历。</p>
  }

  return <ResumePaperView data={document.data} />
}
