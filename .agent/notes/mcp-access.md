# MCP 接入说明

Paideia 通过 **MCP（Model Context Protocol）** 把平台能力暴露给外部 AI 助手。本文只讲怎么接、有哪些视图、注意什么；设计依据见 [WORK-019](../changes/archive/2026-11-22-WORK-019-MCP网关与工具目录/design.md)。

> 本文档**不含任何真实 token**。token 在你自己的「设置 → MCP 接入」页签发。

## 1. 签发凭据

1. 登录 Paideia，打开右上角账号菜单 → **MCP 接入**（或直接访问 `/settings/mcp`）。
2. 填一个名字（例如「家里的 Claude Desktop」），按需要勾选：
   - **允许写入**：建笔记、打自评标记、调用平台 AI 问答。不勾则只有只读权限。
   - **永不过期**：默认 90 天。
3. 点创建。**明文只显示这一次**，刷新后无法再查看——丢了就吊销重建。
4. 吊销立即生效，没有任何缓存延迟。

## 2. 配置客户端

### ZCode

在 MCP 配置文件的 `mcp.servers` 下加一条：

```json
{
  "paideia": {
    "type": "http",
    "url": "https://<你的站点>/mcp",
    "headers": {
      "Authorization": "Bearer pd_mcp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }
  }
}
```

### Claude Desktop

```json
{
  "mcpServers": {
    "paideia": {
      "type": "http",
      "url": "https://<你的站点>/mcp",
      "headers": {
        "Authorization": "Bearer pd_mcp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

其它客户端只要支持 **Streamable HTTP** 与自定义 `Authorization` 头即可，不需要 OAuth。

## 3. 视图（端点）

| 端点 | 内容 | 需要 write scope |
| --- | --- | --- |
| `/mcp` | 默认组，只读（知识库、笔记、账号、自评、AI 问答） | 否 |
| `/mcp/write` | 默认组，含写工具 | 是 |
| `/mcp/x/all` | 全部工具组，只读 | 否 |
| `/mcp/x/all/write` | 全部工具组，含写 | 是 |
| `/mcp/x/knowledge` | 只看知识库 | 否 |
| `/mcp/x/{toolset}` | 单个工具组，只读 | 否 |
| `/mcp/x/{toolset}/write` | 单个工具组，含写 | 是 |

**建议从 `/mcp` 开始**：工具少的时候模型选得更准，也少一次「这个工具是干嘛的」的猜测。需要写的时候再换成带 `/write` 的端点，或者给同一个地址换一张带 write scope 的凭据。

未知的工具组返回 **404**（与「端点不存在」同一个响应，不告诉你哪些组有效）。

## 4. 工具清单

| 工具 | 说明 | 写 |
| --- | --- | --- |
| `knowledge_search` | 按关键词检索已发布条目 | |
| `knowledge_get_entry` | 读一篇已发布条目的正文 | |
| `knowledge_list_categories` | 列全部分类 | |
| `knowledge_my_marks` | 列我自己的自评标记 | |
| `note_list` | 列我的笔记（标题 + 摘要） | |
| `note_get` | 读一篇笔记全文 | |
| `note_create` | 新建笔记 | ✓ |
| `note_append` | 追加到笔记末尾 | ✓ |
| `account_me` | 读我的身份与求职目标 | |
| `assessment_list_marks` | 列我的自评标记（可按类型过滤） | |
| `assessment_mark_entry` | 给一条打「懂了 / 还不懂」 | ✓ |
| `assistant_ask` | 让平台 AI 回答一个问题（**消耗积分**） | ✓ |
| `assistant_draft_note` | 只起草、不写库（内部助手专用，外部视图不出现） | |

> **命名**：工具名统一用 `工具组_动作` 的下划线形状（如 `knowledge_search`），不是点号。
> 原因是内部适配器要把同一批名字发给 DeepSeek 的 OpenAI 兼容接口，那条通道只接受
> `^[a-zA-Z0-9_-]+$`——点号会被 400 拒绝。一份名字两边都能用，就不存在"改了一边忘了另一边"。

**归属永远是凭据绑定的那个人**：工具参数里没有 `userId`，传了也会被丢掉。所以外部助手看不到别人的数据，也不可能通过改参数越权。

## 5. 计费

只有 `assistant_ask` 会产生费用：它走平台的积分体系，余额不足时**直接失败且不发起任何上游请求**。其余工具不扣积分。

## 6. 排错

| 现象 | 原因 |
| --- | --- |
| 401 `{"error":"unauthorized"}` | 没有 token、token 拼错、已过期或已吊销。**响应刻意不区分这几种**，别指望从错误里判断 token 是否曾经有效。 |
| 403 | 用只读凭据访问了带 `/write` 的端点。 |
| 404 | 视图名写错（注意只允许小写字母、数字、下划线）。 |
| 工具报「没有名为 X 的工具」 | 该工具不在当前视图。换到含它的视图（例如写工具需要 `/write`）。 |
| 工具报「调用过于频繁」 | 默认每 token 每分钟 120 次。 |
| 工具报「平台尚未配置 AI 服务」 | `assistant_ask` 需要部署配置里配好平台凭据。 |

## 7. 架构备注

- 传输：**Streamable HTTP**（协议 2025-06-18），单端点 POST，回复是 JSON 或请求级 SSE。
- 无 Cookie、无 CSRF 依赖：凭据在 `Authorization` 头里。
- 视图是**启动时按目录构建并缓存**的：`tools/list` 与 `tools/call` 用的是同一份清单，清单里没有的工具一定调不动。
- 每次工具调用写一行 `mcp.audit` 日志（tokenId / userId / tool / view / durationMs / isError / bytes），**不含参数正文**。
