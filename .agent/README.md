# .agent 项目工作区

本目录保存已安装 `project-lifecycle` Skill 使用的项目资料，不保存 Skill 本体。根目录 `AGENTS.md` 负责入口路由；若存在 `AGENTS.override.md`，按更高优先级使用。

```text
.agent/
  INDEX.md                  按模块定位代码、规格、变更和理解材料
  memory.md                 跨任务仍有效的长期记忆
  rules/always.md           用户确认后的项目常驻规范
  specs/                    多个工作项共享的当前事实
  changes/WORK-编号-中文名/  受管理需求的生命周期工件
  notes/ references/        决策理由与共享资料
  html/                     用户同意后生成的项目理解型 HTML
  history/                  更新历史与 Git 历史视图
  scripts/                  项目内确定性辅助脚本（不放 Skill 状态脚本）
```

`specs/` 保存当前共享事实，`changes/` 保存一次变更的依据、任务和验证；`history/updates.md` 追加每轮变更、决策、依据、验证和本地提交边界（Git 历史视图如需重建，用 `scripts/generate_core_history.py --output <路径>` 生成，当前未入库）；源代码与可执行测试仍在项目原有目录。`always.md` 只保存经用户确认且适用于多个任务的长期规则，单次约束写在对应 `requirements.md`。`html/` 只在用户明确同意后写入理解材料，空目录本身不表示已批准。

直接用自然语言开始、确认、继续或查询状态即可。`WORK-*` 只用于跨对话定位，不代表阶段批准；恢复顺序、门槛、澄清、验证和完成语义统一见已安装 Skill 的 `references/workflow.md`。
