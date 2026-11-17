# UI 组件清单（自动生成，请勿手改）

> 由 `pnpm ui:manifest` 从 `packages/ui/src/index.ts` 与 `packages/ui/src/component-meta.json` 生成。
> 需改组件用途请编辑 `component-meta.json` 后重跑；CI 用 `pnpm ui:manifest:check` 防漂移。

共 32 个模块、123 个组件/导出。

| 分类 | 导出 | 用途 | 源文件 |
| --- | --- | --- | --- |
| 布局 | `AppShell` | 应用外壳、页头与区块容器 | `layout/app-shell.tsx` |
| 布局 | `PageHeader` | 应用外壳、页头与区块容器 | `layout/app-shell.tsx` |
| 布局 | `Section` | 应用外壳、页头与区块容器 | `layout/app-shell.tsx` |
| 反馈 | `Alert` | 内联提示条（信息 / 成功 / 警告 / 错误） | `components/alert.tsx` |
| 反馈 | `AlertAction` | 内联提示条（信息 / 成功 / 警告 / 错误） | `components/alert.tsx` |
| 反馈 | `AlertDescription` | 内联提示条（信息 / 成功 / 警告 / 错误） | `components/alert.tsx` |
| 反馈 | `AlertTitle` | 内联提示条（信息 / 成功 / 警告 / 错误） | `components/alert.tsx` |
| 基元 | `Badge` | 状态徽标 | `components/badge.tsx` |
| 基元 | `badgeVariants` | 状态徽标 | `components/badge.tsx` |
| 基元 | `Button` | 按钮 | `components/button.tsx` |
| 基元 | `buttonVariants` | 按钮 | `components/button.tsx` |
| 基元 | `Card` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardAction` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardContent` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardDescription` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardFooter` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardHeader` | 卡片容器与各分区 | `components/card.tsx` |
| 基元 | `CardTitle` | 卡片容器与各分区 | `components/card.tsx` |
| 表单 | `Checkbox` | 复选框 | `components/checkbox.tsx` |
| 浮层 | `Dialog` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogClose` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogContent` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogDescription` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogFooter` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogHeader` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogOverlay` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogPortal` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogTitle` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DialogTrigger` | 模态对话框及其子部件 | `components/dialog.tsx` |
| 浮层 | `DropdownMenu` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuCheckboxItem` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuContent` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuGroup` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuItem` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuLabel` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuPortal` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuRadioGroup` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuRadioItem` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuSeparator` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuShortcut` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuSub` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuSubContent` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuSubTrigger` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 浮层 | `DropdownMenuTrigger` | 下拉菜单及其子部件 | `components/dropdown-menu.tsx` |
| 数据态 | `Empty` | 空状态占位 | `components/empty.tsx` |
| 数据态 | `EmptyContent` | 空状态占位 | `components/empty.tsx` |
| 数据态 | `EmptyDescription` | 空状态占位 | `components/empty.tsx` |
| 数据态 | `EmptyHeader` | 空状态占位 | `components/empty.tsx` |
| 数据态 | `EmptyMedia` | 空状态占位 | `components/empty.tsx` |
| 数据态 | `EmptyTitle` | 空状态占位 | `components/empty.tsx` |
| 表单 | `Input` | 单行文本输入 | `components/input.tsx` |
| 导航 | `ListPagination` | 列表分页控件 | `components/pagination.tsx` |
| 数据态 | `ErrorAlert` | 查询错误提示与加载行 | `components/query-state.tsx` |
| 数据态 | `LoadingRow` | 查询错误提示与加载行 | `components/query-state.tsx` |
| 表单 | `Label` | 表单标签 | `components/label.tsx` |
| 表单 | `Select` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectContent` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectGroup` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectItem` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectLabel` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectScrollDownButton` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectScrollUpButton` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectSeparator` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectTrigger` | 下拉选择及其子部件 | `components/select.tsx` |
| 表单 | `SelectValue` | 下拉选择及其子部件 | `components/select.tsx` |
| 基元 | `Separator` | 分隔线 | `components/separator.tsx` |
| 数据态 | `Skeleton` | 骨架屏占位 | `components/skeleton.tsx` |
| 反馈 | `Toaster` | 全局 Toast 通知容器 | `components/sonner.tsx` |
| 反馈 | `toast` | toast() 能力，从 sonner 转出（应用无需自行依赖 sonner） | `sonner（外部包）` |
| 反馈 | `Spinner` | 加载指示器 | `components/spinner.tsx` |
| 表单 | `Switch` | 开关 | `components/switch.tsx` |
| 数据展示 | `Table` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableBody` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableCaption` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableCell` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableFooter` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableHead` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableHeader` | 表格及其子部件 | `components/table.tsx` |
| 数据展示 | `TableRow` | 表格及其子部件 | `components/table.tsx` |
| 导航 | `Tabs` | 标签页切换 | `components/tabs.tsx` |
| 导航 | `TabsContent` | 标签页切换 | `components/tabs.tsx` |
| 导航 | `TabsList` | 标签页切换 | `components/tabs.tsx` |
| 导航 | `TabsTrigger` | 标签页切换 | `components/tabs.tsx` |
| 导航 | `tabsListVariants` | 标签页切换 | `components/tabs.tsx` |
| 浮层 | `Tooltip` | 工具提示 | `components/tooltip.tsx` |
| 浮层 | `TooltipContent` | 工具提示 | `components/tooltip.tsx` |
| 浮层 | `TooltipProvider` | 工具提示 | `components/tooltip.tsx` |
| 浮层 | `TooltipTrigger` | 工具提示 | `components/tooltip.tsx` |
| Markdown | `Markdown` | Markdown 渲染的唯一实现（含预加载） | `markdown/markdown.tsx` |
| Markdown | `MarkdownLoading` | Markdown 渲染的唯一实现（含预加载） | `markdown/markdown.tsx` |
| Markdown | `preloadMarkdown` | Markdown 渲染的唯一实现（含预加载） | `markdown/markdown.tsx` |
| Markdown | `extractQuizzes` | 从正文抽取 quiz 练习 | `markdown/practice.ts` |
| Markdown | `documentRenderers` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `QuizQuestionView` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `SourceReadingProvider` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `useSourceReading` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseCodeTour` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseCodeMap` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseLearningPath` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseCodeChallenge` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseQuiz` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `parseSourceWalk` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `scoreQuiz` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `visibleSteps` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `walkDefaults` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `walkPermalink` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| Markdown | `permalinkOf` | 源码解读 / 练习 / 图版等专用块与页级上下文 | `markdown/renderers/index.ts` |
| 主题 | `isTheme` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `THEME_KEY` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `THEME_STORAGE_KEY` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `DEFAULT_SKIN` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `SKIN_KEY` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `SKIN_STORAGE_KEY` | 主题键、存储键与类型 | `theme/keys.ts` |
| 主题 | `ThemeProvider` | ThemeProvider 与 useTheme（同时管明暗与皮肤） | `theme/theme-context.tsx` |
| 主题 | `useTheme` | ThemeProvider 与 useTheme（同时管明暗与皮肤） | `theme/theme-context.tsx` |
| 主题 | `ThemeToggle` | 明暗切换按钮 | `theme/theme-toggle.tsx` |
| 主题 | `isSkin` | 皮肤注册表与查询 | `theme/skins.ts` |
| 主题 | `skinById` | 皮肤注册表与查询 | `theme/skins.ts` |
| 主题 | `SKIN_FAMILIES` | 皮肤注册表与查询 | `theme/skins.ts` |
| 主题 | `SKINS` | 皮肤注册表与查询 | `theme/skins.ts` |
| 主题 | `SkinPicker` | 皮肤选择器 | `theme/theme-picker.tsx` |
| 主题 | `subscribeToThemeChange` | 主题变更事件订阅 | `theme/theme-events.ts` |
| 主题 | `THEME_CHANGE_EVENT` | 主题变更事件订阅 | `theme/theme-events.ts` |
