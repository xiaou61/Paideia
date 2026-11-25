# 架构审计整改：第三批验证报告

记录于 2026-11-25。第三批 TASK-008 已完成；TASK-009 仍待实现。本报告只是阶段证据，用户业务验收尚未进行。

## 验证环境

`work/WORK-092` 独立工作树。发布回滚验证用**本地临时目录**模拟远端 `RemoteDir`，假 `systemctl`/`curl`/`sleep` 顶替系统命令，真实执行 `scripts/deploy/remote-deploy.sh` 渲染后的脚本；不连服务器、不部署、不碰生产。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-006：发布失败整体回滚 | `bash scripts/deploy/test-remote-deploy.sh`（Git Bash） | 0 | passed | 21 项断言全过。场景 1（健康通过）：输出 `DEPLOY_OK`，`app.jar` 换成新版本、`web/` 出现新文件、`exploded.manifest`/`web.manifest` 均推进、发版标记写入、`.rollback` 与 `.deploy.lock.d` 清理。场景 2（健康失败）：输出 `DEPLOY_FAILED`，`app.jar` 逐字节回到 `OLDJAR`、`web/` 新文件消失回旧版、两份清单均未推进、无残留快照。 |
| AC-006：并发发布串行化 | 同上（场景 3） | 0 | passed | 预置 `.deploy.lock.d` + 存活 pid 后运行：输出含「另一个发布」，退出码 0，`app.jar` 与 `web/` 均未被触碰。 |
| AC-006：迁移边界说明 | — | — | passed | `docs/architecture/03-deployment.md` 新增「发布失败的回滚边界」：只回代码不降级 schema、迁移须 expand/contract、并发锁、清单成功后推进；`release.ps1` 启动时也打印同一句。 |
| AC-006：脚本语法 | PowerShell `Parser::ParseFile`；`git diff --check` | 0 | passed | `release.ps1` 解析 0 错误；diff 无空白/冲突问题。 |

## 验收结果

- AC-006 有本地隔离模拟证据（成功晋升、健康失败整体回滚字节一致、并发锁拒绝）；`release.ps1 -ValidateOnly` 未在真实服务器链路跑（需线上授权），本地以语法解析 + 模拟脚本覆盖。
- AC-007 由 TASK-009 承载，尚未实现，见 tasks.md。

## 失败与未验证项

- 回滚验证在本地临时目录模拟，非真实服务器 systemd/端口；真实的 systemctl 重启、端口占用、90 秒健康轮询时序未在本机复现。
- 未连服务器、未部署、未触碰线上库或生产。
