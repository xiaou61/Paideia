import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'

import { MarkdownField } from './markdown-field'

function Harness({ onUpload }: { onUpload?: (file: File) => Promise<{ url: string; name: string }> }) {
  const [value, setValue] = useState('')
  return (
    <MarkdownField
      value={value}
      onChange={setValue}
      testIdPrefix="md-test"
      onUpload={onUpload}
    />
  )
}

afterEach(() => cleanup())

describe('MarkdownField', () => {
  it('toolbar inserts markdown at the current cursor', () => {
    render(<Harness />)

    fireEvent.click(screen.getByTestId('md-test-toolbar-heading'))

    expect((screen.getByTestId('md-test-body') as HTMLTextAreaElement).value).toBe('## ')
  })

  it('renders upload affordance only when onUpload is provided', () => {
    const { rerender } = render(<Harness />)
    expect(screen.queryByText('可粘贴/拖入图片或 PDF')).toBeNull()

    rerender(<Harness onUpload={async () => ({ url: '/x.png', name: 'x.png' })} />)
    expect(screen.getByText('可粘贴/拖入图片或 PDF')).toBeTruthy()
  })

  it('upload failure keeps the body unchanged', async () => {
    render(<Harness onUpload={async () => Promise.reject(new Error('upload failed'))} />)
    const body = screen.getByTestId('md-test-body') as HTMLTextAreaElement
    fireEvent.change(body, { target: { value: '原正文' } })
    fireEvent.drop(body, {
      dataTransfer: { files: [new File(['x'], 'x.png', { type: 'image/png' })] },
    })

    expect(await screen.findByTestId('md-test-upload-error')).toBeTruthy()
    expect(body.value).toBe('原正文')
  })
})
