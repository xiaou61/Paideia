/**
 * 设计系统的唯一出口。
 *
 * 应用只从这里取组件，不深入 src/components 内部路径 —— 这样将来替换某个基元
 * 的实现（或在注册表里换基座）不会波及调用方。
 * 样式入口是单独的 `@paideia/ui/styles.css`，vite 插件在 `@paideia/ui/vite`。
 */

export { AppShell, PageHeader, Section } from './layout/app-shell'

export { Alert, AlertAction, AlertDescription, AlertTitle } from './components/alert'
export { Badge, badgeVariants } from './components/badge'
export { Button, buttonVariants } from './components/button'
export { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from './components/card'
export { Checkbox } from './components/checkbox'
export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
} from './components/dialog'
export {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from './components/dropdown-menu'
export {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty'
export { Input } from './components/input'
export { Label } from './components/label'
export {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectScrollDownButton,
  SelectScrollUpButton,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from './components/select'
export { Separator } from './components/separator'
export { Skeleton } from './components/skeleton'
export { Toaster } from './components/sonner'
// 转出 sonner 的 toast：应用因此不必自己依赖 sonner，只从设计系统取能力
export { toast } from 'sonner'
export { Spinner } from './components/spinner'
export { Switch } from './components/switch'
export {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from './components/table'
export { Tabs, TabsContent, TabsList, TabsTrigger, tabsListVariants } from './components/tabs'
export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from './components/tooltip'

// markdown 渲染的唯一实现：学习者端条目、管理端预览、助手回答都从这里取（WORK-012）
export {
  Markdown,
  MarkdownLoading,
  preloadMarkdown,
  type MarkdownProps,
  type MarkdownRenderer,
} from './markdown/markdown'

// 源码解读的五种块与页级上下文（WORK-014）：知识库正文与管理端预览共用
// 练习册的抽取（WORK-048）：把 quiz 围栏从正文里摘出来，正文交给渲染器、题交给入口卡
export { extractQuizzes, type ExtractResult } from './markdown/practice'
export {
  documentRenderers,
  QuizQuestionView,
  SourceReadingProvider,
  useSourceReading,
  parseCodeTour,
  parseCodeMap,
  parseLearningPath,
  parseCodeChallenge,
  parseQuiz,
  scoreQuiz,
  type Quiz,
  type QuizQuestion,
  permalinkOf,
  type DocumentContextValue,
  type EntryProgress,
  type ProgressPatch,
  type ProgressPort,
  type RunOutput,
  type SandboxPort,
} from './markdown/renderers'

export { isTheme, THEME_KEY, THEME_STORAGE_KEY, type Theme, type ThemeStorage } from './theme/keys'
export { ThemeProvider, useTheme } from './theme/theme-context'
export { ThemeToggle } from './theme/theme-toggle'
