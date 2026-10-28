---
title: React 的一次并发渲染是怎么开始的
category: source-reading
line_summary: 从一次 React 渲染入口追下去，学会读框架源码时先找它决定"什么时候做事"。
line_name: 源码解读
line_kind: reading
line_sort: 30
order: 0
minutes: 25
status: published
---

## 这篇讲什么



`setState` 之后到屏幕上真实更新，中间隔着一次**可以被打断的渲染**。这篇只看它的入口：

谁决定了"从头开始"还是"接着上次做"。



## 先看调用形状



```javascript

// 一次并发渲染的入口，去掉所有分支判断后只剩四步

executionContext |= RenderContext;          // 1. 标上下文

if (rootOrLanesChanged) prepareFreshStack(); // 2. 必要时重置

workLoopConcurrent();                        // 3. 按片工作

commitRoot();                                // 4. 提交

```



## 一次更新的路径



```code-map

{

  "id": "react-render",

  "title": "从调度到渲染",

  "nodes": [

    { "id": "schedule", "label": "scheduleUpdateOnFiber", "layer": 0, "order": 0 },

    { "id": "render", "label": "renderRootConcurrent", "layer": 1, "order": 0, "tour": "react-render", "step": 0 },

    { "id": "fresh", "label": "prepareFreshStack", "layer": 2, "order": 0, "tour": "react-render", "step": 1 },

    { "id": "loop", "label": "workLoopConcurrent", "layer": 2, "order": 1, "tour": "react-render", "step": 2 }

  ],

  "edges": [

    { "from": "schedule", "to": "render", "label": "调度" },

    { "from": "render", "to": "fresh", "label": "必要时重置" },

    { "from": "fresh", "to": "loop", "label": "开始工作" }

  ]

}

```



点节点会跳到下面走读的对应步骤；反过来切步骤，上面这张图也会跟着亮。



## 走读：入口那几十行



```code-tour

{

  "id": "react-render",

  "title": "渲染是怎么开始的",

  "repo": "facebook/react",

  "commit": "d75b0697",

  "license": "MIT",

  "file": "packages/react-reconciler/src/ReactFiberWorkLoop.js",

  "steps": [

    {

      "title": "设置上下文，准备渲染",

      "line": 2794,

      "code": "function renderRootConcurrent(root: FiberRoot, lanes: Lanes): RootExitStatus {\n  const prevExecutionContext = executionContext;\n  executionContext |= RenderContext;\n  const prevDispatcher = pushDispatcher(root.containerInfo);\n  const prevAsyncDispatcher = pushAsyncDispatcher();",

      "language": "javascript",

      "focus": [2, 3],

      "explain": "先把 `executionContext` 标成 RenderContext，再把当前的 dispatcher 压栈。\n\n这一步的意义是：**在这段代码里调用的 hook 都会走渲染期的实现**，而不是更新期的。"

    },

    {

      "title": "要不要从头开始",

      "line": 2800,

      "code": "  // If the root or lanes have changed, throw out the existing stack\n  // and prepare a fresh one. Otherwise we will continue where we left off.\n  if (workInProgressRoot !== root || workInProgressRootRenderLanes !== lanes) {\n    // ...\n    resetRenderTimer();\n    prepareFreshStack(root, lanes);\n  } else {\n    workInProgressRootIsPrerendering = checkIfRootIsPrerendering(root, lanes);\n  }",

      "language": "javascript",

      "focus": [3, 6],

      "explain": "条件只有两个：**root 变了**，或者**本轮的 lanes 变了**。\n\n两者都没变时是「接着上次做」——这正是并发渲染能被打断再续上的原因。",

      "predict": {

        "question": "为什么 root 或 lanes 一变就必须丢掉旧栈？",

        "options": ["旧栈属于另一次渲染，混用会算错", "为了释放内存", "为了让 GC 有机会运行"],

        "answer": 0,

        "reveal": "`workInProgress` 指向的是**某一轮**渲染的半成品树。换了 root 或优先级之后，它不再是「这一轮」的进度，接着用会把两轮的结果混在一起。"

      }

    },

    {

      "title": "重置工作栈",

      "line": 2038,

      "code": "function prepareFreshStack(root: FiberRoot, lanes: Lanes): Fiber {\n  if (enableProfilerTimer && enableComponentPerformanceTrack) {\n    markAllLanesInOrder();\n    const previousRenderStartTime = renderStartTime;\n    recordRenderTime();",

      "language": "javascript",

      "focus": [1],

      "explain": "它把 root 上的 `workInProgress`、`workInProgressRoot` 与本轮 lanes 全部重置。\n\n这也是为什么注释里写 *throw out the existing stack*——不是清个变量，而是整棵半成品树作废。",

      "state": {

        "vars": [

          { "name": "workInProgressRoot", "value": "root（旧）→ null" },

          { "name": "workInProgress", "value": "旧树的根 → 新树" },

          { "name": "lanes", "value": "SyncLane" }

        ],

        "stack": ["prepareFreshStack", "renderRootConcurrent"],

        "output": []

      }

    },

    {

      "title": "按片工作",

      "line": 3071,

      "code": "function workLoopConcurrent(nonIdle: boolean) {\n  if (workInProgress !== null) {\n    const yieldAfter = now() + (nonIdle ? 25 : 5);\n    do {\n      performUnitOfWork(workInProgress);\n    } while (workInProgress !== null && now() < yieldAfter);\n  }\n}",

      "language": "javascript",

      "focus": [2, 4, 5],

      "explain": "**它自己主动让出**：干到 25ms（Idle 时 5ms）就停，把主线程还给浏览器。\n\n注释里那句很关键——让出的目的不是省开销，而是**不让动画掉到 30fps 以下**。"

    }

  ]

}

```



## 动手：把入口的顺序补回来



```code-challenge

{

  "id": "react-entry-order",

  "kind": "order",

  "title": "把入口的顺序排对",

  "lines": [

    "判断 root 与 lanes 是否变化",

    "executionContext |= RenderContext;",

    "prepareFreshStack(root, lanes);",

    "workLoopConcurrent(false);"

  ],

  "answer": [1, 0, 2, 3],

  "explain": "先标上下文，再判断要不要重置，重置完才进工作循环。"

}

```



## 动手：找 bug



```code-challenge

{

  "id": "react-bug-check",

  "kind": "bug",

  "title": "这段判断错在哪一行",

  "language": "javascript",

  "code": "if (workInProgressRoot !== root || workInProgressRootRenderLanes !== lanes) {\n  resetRenderTimer();\n}\nprepareFreshStack(root, lanes);\n",

  "bugLine": 4,

  "explain": "`prepareFreshStack` 必须**在条件里面**：两者都没变时要接着上次做，无条件重置会把可续上的进度丢掉。"

}

```



## 动手：让代码真的跑起来



```code-challenge

{

  "id": "react-run-demo",

  "kind": "run",

  "title": "看看「按片工作」到底打印了什么",

  "language": "python",

  "starter": "def perform_unit(step):\n    print(f\"unit {step}\")\n\nfor i in range(3):\n    perform_unit(i)\n",

  "cases": [

    { "name": "三步都执行", "expected": "unit 0\nunit 1\nunit 2" }

  ],

  "explain": "真实实现里 `performUnitOfWork` 每次处理一个 Fiber；这里用三行循环模拟「逐个单元推进」。"

}

```



这一题需要部署时开启代码执行（`paideia.sandbox.enabled=true` 且本机有 Docker）；没开时它只显示代码，不会给出一个点了必然失败的按钮。



## 小测



```quiz

{

  "id": "react-entry-quiz",

  "title": "三个问题",

  "questions": [

    {

      "question": "什么情况下 renderRootConcurrent 会接着上次的进度继续？",

      "options": ["root 与 lanes 都没变", "只有 root 没变", "每次都会从头开始"],

      "answer": 0,

      "explain": "两个条件任一变化都会重置；否则是 continuation。"

    },

    {

      "question": "workLoopConcurrent 为什么要主动让出主线程？",

      "options": ["为了让动画不被饿死", "为了等待网络", "为了触发 GC"],

      "answer": 0,

      "explain": "源码注释写明：不是省开销，是为了不让动画掉帧。"

    },

    {

      "question": "prepareFreshStack 丢掉的是什么？",

      "options": ["本次渲染的半成品树", "整个 DOM", "所有 hook 状态"],

      "answer": 0,

      "explain": "丢的是这一轮的 workInProgress 树，不是已提交的界面。"

    }

  ]

}

```



## 三个常见误解



1. **"并发渲染 = 多线程"**：不是。它仍然跑在主线程上，靠的是**可以中断再续**。

2. **"被打断就白干了"**：被打断的树会作废，但已提交的部分不受影响；续不上的那一轮从头再来。

3. **"yield 是为了省性能"**：注释说得很直白，是为了让主线程有机会处理动画等更高优先级的活。



## 面试会怎么追问



- 如果 lanes 变了但 root 没变，为什么也要重置？

- `renderRootConcurrent` 与 `renderRootSync` 的分叉点在哪？

- 并发渲染下，一次 update 可能被丢弃多少次？什么条件下才会真正提交？



> 本文代码片段出自 [facebook/react](https://github.com/facebook/react/blob/d75b0697/packages/react-reconciler/src/ReactFiberWorkLoop.js)（MIT License），版权归 Meta Platforms, Inc. 所有，此处仅作教学引用。

