---
title: CSS：盒模型与层叠
category: frontend-from-zero
order: 3
chapter: 看懂
minutes: 26
level: beginner
status: published
---

## 这一关在解决什么

CSS 里最常见的两种困惑：

- **"我明明写了 `width: 200px`，为什么实际是 240px？"**
- **"我明明写了这个样式，为什么没生效？"**

第一个是盒模型，第二个是层叠（优先级）。这两件事讲清楚，你就不会再靠 `!important` 猜。

打个比方：盒模型像快递箱——箱子外还要留缓冲泡沫（padding）和胶带厚度（border），你量的只是内腔（content）。真实机制是 `width` 默认只算 content，加上 padding 和 border 才是实际占位。

```sketch
{
  "title": "盒模型与层叠：一条排查路",
  "rows": [
    [
      { "id": "box", "title": "盒模型", "sub": "content/padding/border", "tone": "blue" },
      { "id": "margin", "title": "边距合并", "sub": "相邻·父子·空元素", "tone": "blue" }
    ],
    [
      { "id": "cascade", "title": "层叠优先级", "sub": "五个来源按顺序比", "tone": "violet" },
      { "id": "inherit", "title": "继承", "sub": "有些属性自动下传", "tone": "violet" }
    ],
    [
      { "id": "debug", "title": "排查流程", "sub": "先看有没有、再看谁覆盖", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "box", "to": "debug", "label": "占位不对" },
    { "from": "cascade", "to": "debug", "label": "没生效" }
  ]
}
```

## 讲解

### 每个元素都是一个盒子

浏览器把每个元素看成一个矩形盒子，从里到外四层：

```text
┌─────────────────────────────┐
│           margin            │
│  ┌───────────────────────┐  │
│  │        border         │  │
│  │  ┌─────────────────┐  │  │
│  │  │    padding      │  │  │
│  │  │  ┌───────────┐  │  │  │
│  │  │  │  content  │  │  │  │
│  │  │  └───────────┘  │  │  │
│  │  └─────────────────┘  │  │
│  └───────────────────────┘  │
└─────────────────────────────┘
```

`width` 默认指的是**content** 的宽度（`box-sizing: content-box`），所以：

```css
.box {
  width: 200px;
  padding: 20px;
  border: 1px solid #ccc;
}
```

实际占位是 `200 + 20×2 + 1×2 = 242px`。这就是"写了 200 实际 242"的来源。

几乎所有项目都会在全局改掉它：

```css
*, *::before, *::after {
  box-sizing: border-box;
}
```

改成 `border-box` 后，`width: 200px` 表示**连 padding 与 border 一起算**，实际占位就是 200px。绝大多数布局都希望是这个语义。

### 三个会塌陷/合并的边距

- **相邻兄弟的 margin 会合并**：上下两个块，上面的 `margin-bottom: 20px`、下面的 `margin-top: 30px`，间距不是 50 而是 **30**（取较大者）。
- **父子 margin 可能穿透**：子元素的上 margin 会跑到父元素外面，看起来像父元素被推下去了。给父元素加 `padding` 或 `overflow: hidden` 可以建立新的边距上下文。
- **空元素的高度会被合并掉**：一个没有内容、没有 padding/border 的 `div`，上下 margin 会直接合并成一条。

这三条不是 bug，是规范。布局时遇到"间距比写的小"，先想边距合并。

### 层叠：五个来源按顺序比

同一个属性被多条规则命中时，浏览器按下面的顺序决定赢家（从强到弱）：

1. **`!important`**（用户样式里的 important 更强，这里先不展开）；
2. **行内样式** `style="..."`；
3. **选择器优先级**；
4. **出现顺序**（后写的赢）；
5. **继承**（没有直接规则时，从父元素拿）。

选择器优先级用一个四元组记：`(行内, id, class/属性/伪类, 元素/伪元素)`。

```css
p { color: black; }                 /* (0,0,0,1) */
.note { color: blue; }              /* (0,0,1,0) 赢 */
#main .note { color: red; }         /* (0,1,1,0) 赢 */
```

注意 `#main .note` 比 `.note` 高，但**不要靠堆 id 解决冲突**。那会让后面的样式越来越难覆盖。更稳的做法是把选择器写短、层级写浅。

### 继承：有些属性会自动往下传

`color`、`font-family`、`font-size`、`line-height` 会继承；`margin`、`padding`、`border`、`display` 不会。

```css
body { color: #333; font-family: system-ui; }
/* 所有后代默认拿到这两个值，不用重复写 */
```

`font-size` 用相对单位（`rem`/`em`）时，继承会让"基准"逐层变化——这是 `em` 容易算错的原因。`rem` 始终相对根元素，通常更好预测。

### 一个排查流程

样式没生效时，按顺序问：

1. **规则出现在 Styles 面板里了吗？** 没出现 = 选择器没选中（检查拼写、层级、元素是否真的存在）。
2. **出现了但被划掉？** 看是谁覆盖的：优先级更高、还是后写的规则。
3. **没划掉但仍不生效？** 可能是值非法（`display: flexx` 会被丢弃）、被其他属性覆盖（如 `width` 对 `inline` 元素无效）、或者父元素限制（`overflow: hidden` 裁掉）。

这套流程比"加 `!important` 试试"快，而且不会留下技术债。

## 常见误区

### 误区：`!important` 能解决样式冲突

**你以为**：不生效就加 `!important`，总能盖住。
**其实**：它只是把当前规则提到最高优先级，下一个冲突还得再加一个，最后没人知道哪个规则真正生效。它适合的场景极少（覆盖第三方库的内联样式），业务样式里用它是把问题往后推。
**怎么验证**：在 Styles 面板里数一下同一个属性上有几个 `!important`——超过一个就说明层叠已经失控了。

### 误区：`width: 100%` 就是"撑满"

**你以为**：父元素多宽，子元素就多宽。
**其实**：`100%` 是相对**父元素的 content 宽度**；如果子元素自己还有 padding/border，`content-box` 下会溢出。另外父元素如果是 `flex` 容器，子项的 `width` 还会被 `flex-shrink` 影响。
**怎么验证**：给子元素临时加 `outline: 2px solid red`，看它是否超出父元素的边界。

### 误区：`margin: 0 auto` 能居中任何东西

**你以为**：写了就居中。
**其实**：它只对**有确定宽度的块级元素**生效。行内元素、没有宽度的元素、或者父元素是 flex 容器时，行为都不一样。Flex 容器里应该用 `justify-content` / `align-items`。
**怎么验证**：DevTools 里看元素 computed 的 `display` 与 `width`，再决定用哪种居中方式。

## 带走一句

**盒子算的是 border-box，冲突看的是层叠顺序**——把这两件事各问一次，九成的"CSS 玄学"都有确定答案。

```quiz
{"id":"fe-03-quiz","title":"盒模型与层叠","questions":[
 {"question":"`width: 200px; padding: 20px; border: 1px solid`，在默认 content-box 下实际占多宽？","options":["200px","220px","242px","240px"],"answer":2,"explain":"content 200 加左右 padding 40、左右 border 2，合计 242；改成 border-box 才是 200。"},
 {"question":"下面哪个选择器优先级最高？","options":["p","p.note","#main p","body p"],"answer":2,"explain":"优先级是 (行内, id, class, 元素)：#main p 有一个 id，其余都没有。"},
 {"question":"两个相邻块，上块 margin-bottom 20px、下块 margin-top 30px，实际间距是？","options":["50px","30px","20px","0"],"answer":1,"explain":"相邻兄弟的垂直 margin 会合并，取较大者，不是相加。"}
]}
```
