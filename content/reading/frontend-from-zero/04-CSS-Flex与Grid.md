---
title: CSS：Flex 与 Grid
category: frontend-from-zero
order: 4
chapter: 看懂
minutes: 28
level: beginner
status: published
---

## 这一关在解决什么

布局是 CSS 里最容易"能凑出来但说不清"的部分。这一关给一条明确的选型规则：

- **一维排列（一行或一列）用 Flex**；
- **二维网格（行和列都要对齐）用 Grid**。

以及三个最常用的能力：对齐、分配剩余空间、换行。

## 讲解

### Flex：沿着一条主轴排

```css
.row {
  display: flex;
  gap: 12px;
}
```

容器变成 flex 后，子元素默认沿**主轴**（横向）排成一行。两句话记住整个模型：

- `justify-content`：主轴方向怎么排（`flex-start` / `center` / `space-between`）；
- `align-items`：交叉轴方向怎么对齐（`stretch` / `center` / `flex-start`）。

```css
.nav {
  display: flex;
  justify-content: space-between; /* 两端对齐 */
  align-items: center;            /* 垂直居中 */
}
```

**垂直居中终于不用算高度了**——这是 Flex 最直接的收益。

### 剩余空间怎么分：`flex-grow` / `flex-shrink` / `flex-basis`

`flex: 1` 是简写，约等于 `flex: 1 1 0%`：可以长大、可以缩小、基准为 0。所以几个 `flex: 1` 的子项会**均分**容器。

```css
.sidebar { flex: 0 0 240px; } /* 固定 240，不参与分配 */
.content { flex: 1; }         /* 吃掉剩下的全部 */
```

如果写成 `flex: 1 1 auto`，基准是内容宽度，分配结果会受内容影响——"两个都设了 flex:1 为什么不一样宽"通常就是这个。

还有一个隐藏默认值：**flex 子项的最小宽度是 `min-content`**，长单词或长链接会把卡片撑破。修法是 `min-width: 0`：

```css
.card { flex: 1; min-width: 0; }
.card-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
```

### 换行：`flex-wrap`

默认不换行，子项会被压缩。要换行必须显式写：

```css
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
```

`gap` 同时管行间距与列间距，比给每个子项加 margin 干净得多（不会在首尾多出空隙）。

### Grid：行和列一起定义

```css
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
```

`repeat(3, minmax(0,1fr))` 是"三列等宽，且允许缩到 0"——`minmax(0, 1fr)` 里的 0 很关键，写成 `1fr` 时最小值仍是 `min-content`，长内容会撑破列。

Grid 更适合：

- 卡片列表（列数固定或响应式变化）；
- 页面整体骨架（header / sidebar / main / footer）；
- 需要行高对齐的表格式布局。

```css
.page {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  grid-template-rows: auto 1fr auto;
  grid-template-areas:
    "header header"
    "side   main"
    "footer footer";
}
```

`grid-template-areas` 让结构在 CSS 里"画"出来，比一堆 `grid-column: 1 / 3` 好读。

### 该用哪个：一条判断

| 问题 | 选择 |
| --- | --- |
| 只关心一行/一列的排列与对齐 | Flex |
| 行列都要对齐，或者要定义整页骨架 | Grid |
| 内容数量不定、需要自动换行 | Flex + `flex-wrap` |
| 卡片需要固定列数、响应式变列数 | Grid |

两者可以嵌套：Grid 排页面骨架，每个区域里用 Flex 排一行按钮。**不要用一套硬套所有场景**。

## 常见误区

### 误区：`flex: 1` 的三个子项一定等宽

**你以为**：都写 `flex: 1` 就均分。
**其实**：`flex: 1` 是 `1 1 0%`，理论上均分；但如果子项有 `min-width`、内容不可压缩、或者某个子项写了 `flex-basis: auto`，结果就会偏。最隐蔽的是长单词把 `min-content` 撑大。
**怎么验证**：临时给子项加 `outline` 看实际宽度，再查 computed 的 `flex` 与 `min-width`。

### 误区：Grid 只能写死列数

**你以为**：`repeat(3, 1fr)` 就是三列，手机上要另写一套。
**真正省事的写法**：

```css
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
```

容器多宽就排几列，每列至少 240px，剩余空间均分。一行 CSS 解决"手机一列、平板两列、桌面三列"，不需要媒体查询。`auto-fill` 与 `auto-fit` 的区别只在于空轨道是否塌缩。

### 误区：布局用 `position: absolute` 更"精确"

**你以为**：绝对定位能放到任意位置，最可控。
**其实**：绝对定位脱离文档流，不占空间、不参与换行、父元素一改尺寸就对不齐。除了浮层（弹窗、下拉菜单、tooltip）这类**确实要脱离流**的元素，常规布局应该用 Flex/Grid。
**怎么验证**：把窗口从宽拖到窄，绝对定位的布局会重叠或溢出，Flex/Grid 会自己重排。

## 带走一句

**一维用 Flex、二维用 Grid，剩余空间交给 `flex`/`fr` 分配**——布局里绝大多数"对不齐"，都是选错了模型或漏了 `min-width: 0`。

```quiz
{"id":"fe-04-quiz","title":"Flex 与 Grid","questions":[
 {"question":"只需要把一排按钮垂直居中、两端对齐，最合适的是？","options":["Grid","Flex + justify-content: space-between + align-items: center","position: absolute","float"],"answer":1,"explain":"一维排列是 Flex 的典型场景；Grid 也能做，但这里不需要定义列。"},
 {"question":"`flex: 1` 的子项被长链接撑破卡片，最直接的修法是？","options":["加 !important","给子项加 min-width: 0","改成 display: block","去掉 flex"],"answer":1,"explain":"flex 子项默认最小宽度是 min-content；min-width: 0 才允许它缩到比内容更窄，再配合省略号。"},
 {"question":"`repeat(auto-fill, minmax(240px, 1fr))` 的效果是？","options":["永远三列","按容器宽度自动排能放下的列数，每列至少 240px","每列固定 240px，右侧留白","只能用于 Grid 的第一行"],"answer":1,"explain":"一行声明实现响应式列数：放得下几列就排几列，剩余空间由 1fr 均分。"}
]}
```
