/**
 * 把用户粘贴/拖入的图片压成一段 data URL，供 Markdown 正文内嵌。
 *
 * <p>为什么不用附件后端：笔记正文本身是一份 Markdown，内嵌 data URL 让「预览 / 导出 .md /
 * 整库分享」三处天然自洽，也不需要为图片单独修一条鉴权链路。代价是正文会变大，所以这里
 * 先把最长边压到 1600px、再按质量衰减，直到 base64 长度落进预算。
 */

const MAX_EDGE = 1600
/** base64 字符预算：与正文 20 万字上限留足余量。 */
const MAX_DATA_LENGTH = 180_000
const MIN_QUALITY = 0.4

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(url)
      resolve(image)
    }
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('图片读取失败'))
    }
    image.src = url
  })
}

/** 返回可直接写进 Markdown 的 data URL；非图片或压不下来时抛错。 */
export async function imageFileToDataUrl(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('只能插入图片')
  }
  const image = await loadImage(file)
  const scale = Math.min(1, MAX_EDGE / Math.max(image.naturalWidth, image.naturalHeight))
  const width = Math.max(1, Math.round(image.naturalWidth * scale))
  const height = Math.max(1, Math.round(image.naturalHeight * scale))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (context === null) {
    throw new Error('浏览器不支持图片处理')
  }
  context.drawImage(image, 0, 0, width, height)

  let quality = 0.82
  let data = canvas.toDataURL('image/jpeg', quality)
  while (data.length > MAX_DATA_LENGTH && quality > MIN_QUALITY) {
    quality -= 0.12
    data = canvas.toDataURL('image/jpeg', quality)
  }
  if (data.length > MAX_DATA_LENGTH) {
    throw new Error('图片过大，请换一张更小的')
  }
  return data
}

/** MarkdownField 的 onUpload 约定：返回 url 与用于 alt 的名字。 */
export async function uploadNoteImage(file: File): Promise<{ url: string; name: string }> {
  const url = await imageFileToDataUrl(file)
  const name = file.name.replace(/\.[^.]+$/, '') || 'image'
  return { url, name }
}
