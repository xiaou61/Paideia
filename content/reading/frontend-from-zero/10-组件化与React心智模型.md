---
title: 组件化：React 的心智模型
category: frontend-from-zero
order: 10
chapter: 做成产品
minutes: 28
level: intermediate
status: published
---

## 这一关在解决什么

上一关手写 DOM 时，你已经做了三件重复的事：拼结构、绑事件、在数据变化后手动同步。React 把这件重复的事变成一个公式：

**UI = f(state)** —— 界面是状态的函数，你只描述"给定状态应该长什么样"，剩下的由 React 做。

这一关不背 API，只建立三个概念：组件、props、渲染。

打个比方：以前手写 DOM 像自己搬砖砌墙，React 像给装修队一张效果图——你说"这个状态该长什么样"，它去改墙。真实机制是 UI = f(state)：组件是返回结构的函数，React 对比前后两次结果只改变化的 DOM。

```code-flow
{
  "id": "fe-10-react-model",
  "title": "React 心智：组件 · props · 渲染 · diff",
  "nodes": [
    { "id": "props", "label": "props", "sub": "父给·只读", "layer": 0, "order": 0 },
    { "id": "comp", "label": "组件", "sub": "返回结构的函数", "layer": 1, "order": 0 },
    { "id": "tree", "label": "元素树", "sub": "这次渲染的快照", "layer": 2, "order": 0 },
    { "id": "diff", "label": "diff", "sub": "对比前后两棵树", "layer": 3, "order": 0 },
    { "id": "dom", "label": "真实 DOM", "sub": "只改变化的部分", "layer": 4, "order": 0 }
  ],
  "edges": [
    { "from": "props", "to": "comp", "label": "喂输入" },
    { "from": "comp", "to": "tree", "label": "调用返回" },
    { "from": "tree", "to": "diff", "label": "新旧对比" },
    { "from": "diff", "to": "dom", "label": "只改差异" }
  ],
  "steps": [
    {
      "title": "组件就是返回结构的函数",
      "desc": "返回值是这一块界面长什么样",
      "nodes": ["comp"],
      "edges": [],
      "code": "function TaskRow({ task, onRemove }) {\n  return (\n    <li className=\"row\">\n      <span>{task.title}</span>\n      <button type=\"button\" onClick={() => onRemove(task.id)}>删除</button>\n    </li>\n  )\n}",
      "focus": [1, 2, 3, 4, 5, 6, 7],
      "log": [{ "k": "不是模板", "v": "是函数，可以有逻辑" }],
      "explain": "函数名大写才能当标签用；返回的不是 DOM 节点，是这一块界面的描述。"
    },
    {
      "title": "props 从外往里传，只读",
      "desc": "数据向下、事件向上",
      "nodes": ["props", "comp"],
      "edges": ["props>comp"],
      "code": "<TaskRow task={task} onRemove={remove} />",
      "focus": [1],
      "log": [{ "k": "方向", "v": "数据向下·事件向上" }],
      "explain": "子组件不能改 props；想改只能调用父组件传下来的回调。"
    },
    {
      "title": "渲染一次算出整棵结构",
      "desc": "只写当前状态应该有什么",
      "nodes": ["comp", "tree"],
      "edges": ["comp>tree"],
      "code": "function TaskList({ tasks, status, error, onRetry, onRemove }) {\n  if (status === 'loading') return <p>加载中…</p>\n  if (status === 'error') return <div><p>加载失败：{error}</p><button onClick={onRetry}>重试</button></div>\n  if (tasks.length === 0) return <p>还没有任务。</p>\n  return <ul>{tasks.map((task) => <TaskRow key={task.id} task={task} onRemove={onRemove} />)}</ul>\n}",
      "focus": [1, 2, 3, 4, 5, 6],
      "log": [{ "k": "写法", "v": "没有先清空再插入" }],
      "explain": "你按状态写整棵结构，React 对比前后两次结果，只改真正变化的 DOM。"
    },
    {
      "title": "React 对比前后两棵树",
      "desc": "diff 出最小变更",
      "nodes": ["tree", "diff"],
      "edges": ["tree>diff"],
      "code": "// 上次：<li>A</li> <li>B</li>\n// 这次：<li>A</li> <li>C</li>\n// React 只改 B → C，不动 A",
      "focus": [1, 2, 3],
      "log": [{ "k": "虚拟 DOM", "v": "diff 出差异" }],
      "explain": "这是虚拟 DOM 与 diff 的实际意义：你按状态写，它按差异改。"
    },
    {
      "title": "key 决定复用，最终只改变化的 DOM",
      "desc": "用稳定的业务 id",
      "nodes": ["diff", "dom"],
      "edges": ["diff>dom"],
      "code": "// key 用稳定业务 id：删除中间项，其余身份不错位\n// key 用数组下标：后面各项身份错位，输入框内容跟着串",
      "focus": [1, 2],
      "log": [{ "k": "key", "v": "列表项的身份" }],
      "explain": "用下标当 key，插入/删除会让身份错位，表现为输入框内容串了或动画错乱。"
    }
  ]
}
```

## 讲解

### 组件就是返回结构的函数

```jsx
function TaskRow({ task, onRemove }) {
  return (
    <li className="row">
      <span>{task.title}</span>
      <button type="button" onClick={() => onRemove(task.id)}>删除</button>
    </li>
  )
}
```

- 函数名大写在 JSX 里才能当标签用；
- 返回值是"这一块界面长什么样"，不是 DOM 节点；
- 组件可以组合：列表组件里 `tasks.map((task) => <TaskRow key={task.id} ... />)`。

**组件不是模板**。模板是字符串，组件是函数——它可以有逻辑、可以调用别的组件、可以被测试。

### props：从外往里传，只读

```jsx
<TaskRow task={task} onRemove={remove} />
```

- `props` 是父组件给的输入，**子组件不能改**；
- 想改只能调用父组件传下来的函数（如 `onRemove`）；
- 数据向下流、事件向上报，这是 React 里最基本的方向。

这条约束换来的是可预测：一个组件长什么样，只取决于它的 props 与自己的状态，不取决于谁在什么时候改过它。

### 渲染：一次"算"出整棵结构

```jsx
function TaskList({ tasks, status, error, onRetry, onRemove }) {
  if (status === 'loading') return <p>加载中…</p>
  if (status === 'error') {
    return (
      <div>
        <p>加载失败：{error}</p>
        <button type="button" onClick={onRetry}>重试</button>
      </div>
    )
  }
  if (tasks.length === 0) return <p>还没有任务。</p>
  return (
    <ul>
      {tasks.map((task) => <TaskRow key={task.id} task={task} onRemove={onRemove} />)}
    </ul>
  )
}
```

注意没有"先清空再插入"。你只写当前状态下应该有什么，React 对比前后两次结果，只改真正变化的 DOM。这就是虚拟 DOM 与 diff 的实际意义：**你按状态写，它按差异改**。

### key：列表项的身份

```jsx
{tasks.map((task) => <TaskRow key={task.id} task={task} />)}
```

`key` 告诉 React "这一项还是不是同一项"。用数组下标当 key 时，删除中间一项会让后面所有项的身份错位，表现为"输入框内容跟着串了"或"动画错乱"。**用稳定的业务 id**。

### 受控与非受控

输入框的值可以由 React 状态控制：

```jsx
function Search() {
  const [text, setText] = useState('')
  return <input value={text} onChange={(e) => setText(e.target.value)} />
}
```

这叫受控组件：输入框显示什么完全由 `text` 决定。好处是"值"只有一个来源，做校验、联动、清空都很直接。代价是每次输入都会触发一次渲染（通常没问题）。

### 组件怎么切

一条实用标准：**一个组件只负责一个变化原因**。

- 列表负责"数据有哪些"；
- 行负责"一条怎么显示"；
- 按钮负责"点了做什么"。

切得太碎会让 props 层层传递；切得太粗会让一个改动牵动一片。先从"页面 → 区块 → 行"三层开始，等真的复用或变复杂再拆。

## 常见误区

### 误区：组件里直接改 props 也能跑

**你以为**：`task.title = 'x'` 改了就改了。
**其实**：props 是只读约定；直接改不会触发重新渲染，还会让数据来源变得不可追踪。要改就通过回调让父组件更新状态。
**怎么验证**：在子组件里改 props 后看界面是否变化——通常不变，因为 React 不知道你改了。

### 误区：`key` 只是消警告的

**你以为**：随便写个 `key={index}` 就行。
**其实**：`key` 是 React 判断"同一项"的依据。用下标时，插入/删除会让身份错位，状态（输入值、展开状态）会跟着错位。
**怎么验证**：列表每行放一个输入框，在中间插入一项，看输入内容是否串行。

### 误区：组件越多越"组件化"

**你以为**：拆得越细越专业。
**其实**：拆分的收益是复用与隔离，成本是 props 传递与跳转阅读。为一个只出现一次的三行区块拆三个组件，通常得不偿失。
**怎么验证**：数一下这个组件被用了几次、改它时要不要同时改三个文件。

## 带走一句

**组件是返回界面的函数，props 向下、事件向上，界面永远由状态算出来**——记住这三句，React 的大半 API 都是它们的展开。

```quiz
{"id":"fe-10-quiz","title":"组件与渲染","questions":[
 {"question":"React 的核心公式「UI = f(state)」意味着？","options":["界面由状态算出来，你描述给定状态下应该长什么样","必须先拿到 DOM 再改","状态由界面反推","每个组件都要有 state"],"answer":0,"explain":"你按状态写整棵结构，React 对比前后差异只改真正变化的 DOM，不需要手写同步。"},
 {"question":"列表用数组下标当 key，在中间插入一项时最可能出现？","options":["编译错误","后面各项的身份错位，输入框内容或展开状态跟着串","性能下降十倍","列表无法渲染"],"answer":1,"explain":"key 是身份标识；下标在插入/删除后会变，React 会把不同项当成同一项复用。"},
 {"question":"子组件想改父组件传下来的数据，正确做法是？","options":["直接改 props 对象","调用父组件传下来的回调，让父组件更新状态","把 props 复制一份改副本","刷新页面"],"answer":1,"explain":"props 只读；数据向下、事件向上，这样数据来源始终可追踪。"}
]}
```
