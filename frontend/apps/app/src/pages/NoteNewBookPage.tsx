import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, Check, Globe, Lock, Plus, Tag, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'

import {
  Alert,
  AlertDescription,
  AppShell,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Label,
  PageHeader,
} from '@paideia/ui'

import { LearnerHeader } from '../components/LearnerHeader'
import { notes, type BookInput, type BookView } from '../notes-api'

/** 预设主题色：足够区分多个知识库，又不至于刺眼。 */
const ACCENTS = [
  { value: '#6366f1', name: '靛蓝' },
  { value: '#0ea5e9', name: '天蓝' },
  { value: '#10b981', name: '翡翠' },
  { value: '#f59e0b', name: '琥珀' },
  { value: '#ef4444', name: '珊瑚' },
  { value: '#ec4899', name: '玫瑰' },
  { value: '#8b5cf6', name: '紫罗兰' },
  { value: '#14b8a6', name: '青碧' },
]

/** 新建知识库：完整表单页（名称/描述/可见范围/主题色/标签）。 */
export function NoteNewBookPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [visibility, setVisibility] = useState<'private' | 'public'>('private')
  const [accentColor, setAccentColor] = useState<string>('#6366f1')
  const [tagInput, setTagInput] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: (input: BookInput) => notes.createBook(input),
    onSuccess: (book: BookView) => {
      navigate(`/notes/${book.id}`)
    },
    onError: (reason) => setError(reason instanceof Error ? reason.message : '创建失败，请稍后重试'),
  })

  function addTag(raw: string) {
    const value = raw.trim()
    if (value === '' || tags.includes(value) || tags.length >= 10) return
    setTags([...tags, value.slice(0, 20)])
    setTagInput('')
  }

  function submit() {
    if (name.trim() === '') {
      setError('请先填写知识库名称')
      return
    }
    setError(null)
    create.mutate({
      name: name.trim(),
      description: description.trim(),
      visibility,
      accentColor,
      tags,
    })
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <PageHeader
        title="新建知识库"
        description="一个知识库就是一棵文档树。起个名字、挑个颜色，之后可以随时把资料整理进来。"
        actions={
          <Button variant="ghost" render={<Link to="/notes" />}>
            <ArrowLeft className="mr-1 h-4 w-4" />
            返回
          </Button>
        }
      />

      <form
        className="space-y-6"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-base">基本信息</CardTitle>
            <CardDescription>名称必填，描述可以之后再补。</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label htmlFor="book-name">知识库名称</Label>
                <span className="text-muted-foreground text-xs">{name.length} / 200</span>
              </div>
              <Input
                id="book-name"
                aria-label="知识库名称"
                maxLength={200}
                placeholder="例如「考研数学」「读书笔记」「工作沉淀」"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label htmlFor="book-desc">描述</Label>
                <span className="text-muted-foreground text-xs">{description.length} / 1000</span>
              </div>
              <textarea
                id="book-desc"
                aria-label="知识库描述"
                rows={4}
                maxLength={1000}
                placeholder="一句话说明这个知识库收什么内容，方便以后的自己快速想起。"
                className="border-input bg-background placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 w-full resize-y rounded-lg border px-3 py-2 text-sm leading-6 outline-none focus-visible:ring-3"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">可见范围</CardTitle>
            <CardDescription>决定整库分享链接的寿命。</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-2">
            {(
              [
                {
                  value: 'private',
                  icon: Lock,
                  title: '私有',
                  desc: '仅自己可见。生成的整库分享链接 30 天后自动失效。',
                },
                {
                  value: 'public',
                  icon: Globe,
                  title: '公开',
                  desc: '分享链接永久有效，拿到链接的人可只读浏览；不会出现在公共列表。',
                },
              ] as const
            ).map((option) => {
              const Icon = option.icon
              const active = visibility === option.value
              return (
                <button
                  key={option.value}
                  type="button"
                  aria-label={`可见范围：${option.title}`}
                  aria-pressed={active}
                  onClick={() => setVisibility(option.value)}
                  className={`flex items-start gap-3 rounded-xl border p-4 text-left transition ${
                    active
                      ? 'border-primary bg-primary/5 ring-primary/30 ring-2'
                      : 'border-border hover:bg-muted/50'
                  }`}
                >
                  <span
                    className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${
                      active ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                    }`}
                  >
                    <Icon className="h-4 w-4" />
                  </span>
                  <span className="min-w-0">
                    <span className="flex items-center gap-1 text-sm font-medium">
                      {option.title}
                      {active ? <Check className="text-primary h-3.5 w-3.5" /> : null}
                    </span>
                    <span className="text-muted-foreground mt-1 block text-xs leading-5">
                      {option.desc}
                    </span>
                  </span>
                </button>
              )
            })}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">主题配色</CardTitle>
            <CardDescription>让卡片在列表里一眼可辨。</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-3">
              {ACCENTS.map((accent) => {
                const active = accentColor === accent.value
                return (
                  <button
                    key={accent.value}
                    type="button"
                    aria-label={`主题色：${accent.name}`}
                    aria-pressed={active}
                    title={accent.name}
                    onClick={() => setAccentColor(accent.value)}
                    className={`flex h-10 w-10 items-center justify-center rounded-full transition ${
                      active ? 'ring-offset-background ring-2 ring-offset-2' : 'hover:scale-105'
                    }`}
                    style={{
                      backgroundColor: accent.value,
                      boxShadow: active ? `0 0 0 2px ${accent.value}` : undefined,
                    }}
                  >
                    {active ? <Check className="h-4 w-4 text-white" /> : null}
                  </button>
                )
              })}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">标签</CardTitle>
            <CardDescription>最多 10 个，回车或逗号添加。可用于列表筛选。</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Tag className="text-muted-foreground absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
                <Input
                  aria-label="添加标签"
                  className="pl-9"
                  placeholder="输入标签后回车"
                  value={tagInput}
                  onChange={(event) => {
                    const value = event.target.value
                    if (value.endsWith(',') || value.endsWith('，')) {
                      addTag(value.slice(0, -1))
                    } else {
                      setTagInput(value)
                    }
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      addTag(tagInput)
                    }
                  }}
                />
              </div>
              <Button type="button" variant="outline" onClick={() => addTag(tagInput)}>
                <Plus className="mr-1 h-4 w-4" />
                添加
              </Button>
            </div>
            {tags.length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {tags.map((tag) => (
                  <span
                    key={tag}
                    className="bg-muted text-foreground inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs"
                  >
                    {tag}
                    <button
                      type="button"
                      aria-label={`移除标签 ${tag}`}
                      className="text-muted-foreground hover:text-foreground"
                      onClick={() => setTags(tags.filter((value) => value !== tag))}
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground text-xs">还没有标签。</p>
            )}
          </CardContent>
        </Card>

        {error !== null ? (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}

        <div className="flex items-center justify-end gap-2">
          <Button type="button" variant="ghost" render={<Link to="/notes" />}>
            取消
          </Button>
          <Button type="submit" disabled={create.isPending || name.trim() === ''}>
            {create.isPending ? '创建中…' : '创建知识库'}
          </Button>
        </div>
      </form>
    </AppShell>
  )
}
