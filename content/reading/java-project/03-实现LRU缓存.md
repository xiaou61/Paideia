---
title: 实现一个 LRU 缓存
category: java-project
order: 3
minutes: 30
level: advanced
status: published
---

## 这一关要做什么

LRU（Least Recently Used）：容量满了，淘汰**最久没被使用**的那个。难点不在"存"，在"最近使用"要被 `get` 和 `put` 同时更新——只用一个 `HashMap` 存得下所有 key，却完全不知道谁该走。

**目标**：实现 `LRUCache`——构造时给容量；`get(key)` 不存在返回 `-1`；`put(key, value)` 在 key 已存在时只更新值并把它变成最近使用；容量超出时淘汰最久未使用的那个。

你的文件固定叫 `Main.java`，所以缓存类写成 `Main` 的静态内部类 `Main.LRUCache`；测试入口 `TestMain.java` 是**只读**的，它直接 `new Main.LRUCache(...)`，逐条打印 `ok: <用例名>` 或 `fail: <用例名>`。

```code-challenge
{
 "id": "project-3-run",
 "kind": "run",
 "title": "实现一个 LRU 缓存",
 "language": "java",
 "starter": "import java.util.HashMap;\nimport java.util.Map;\n\npublic class Main {\n    static class LRUCache {\n        private final int capacity;\n        private final Map<Integer, Integer> map = new HashMap<>();\n\n        LRUCache(int capacity) {\n            this.capacity = capacity;\n        }\n\n        int get(int key) {\n            Integer value = map.get(key);\n            return value == null ? -1 : value;\n        }\n\n        void put(int key, int value) {\n            // TODO: 容量满了要先淘汰最久未使用的那个\n            map.put(key, value);\n        }\n    }\n}\n",
 "files": [
  {
   "path": "TestMain.java",
   "content": "public class TestMain {\n    private static int failed = 0;\n\n    private static void check(String name, boolean ok) {\n        System.out.println((ok ? \"ok: \" : \"fail: \") + name);\n        if (!ok) failed++;\n    }\n\n    public static void main(String[] args) {\n        Main.LRUCache one = new Main.LRUCache(1);\n        one.put(1, 100);\n        one.put(2, 200);\n        check(\"capacity-1-evicts-oldest\", one.get(1) == -1 && one.get(2) == 200);\n\n        Main.LRUCache empty = new Main.LRUCache(2);\n        check(\"get-missing-returns-1\", empty.get(9) == -1);\n\n        Main.LRUCache update = new Main.LRUCache(2);\n        update.put(1, 10);\n        update.put(1, 11);\n        check(\"put-existing-updates-value\", update.get(1) == 11);\n\n        Main.LRUCache reorder = new Main.LRUCache(2);\n        reorder.put(1, 1);\n        reorder.put(2, 2);\n        reorder.put(1, 3);\n        reorder.put(3, 3);\n        check(\"put-existing-moves-to-recent\", reorder.get(2) == -1 && reorder.get(1) == 3 && reorder.get(3) == 3);\n\n        Main.LRUCache touched = new Main.LRUCache(2);\n        touched.put(1, 1);\n        touched.put(2, 2);\n        touched.get(1);\n        touched.put(3, 3);\n        check(\"get-moves-to-recent\", touched.get(2) == -1 && touched.get(1) == 1 && touched.get(3) == 3);\n\n        Main.LRUCache evict = new Main.LRUCache(3);\n        evict.put(1, 1);\n        evict.put(2, 2);\n        evict.put(3, 3);\n        evict.put(4, 4);\n        check(\"over-capacity-evicts-oldest\", evict.get(1) == -1 && evict.get(2) == 2 && evict.get(4) == 4);\n\n        Main.LRUCache repeat = new Main.LRUCache(2);\n        repeat.put(1, 1);\n        repeat.put(1, 2);\n        repeat.put(2, 2);\n        repeat.put(3, 3);\n        check(\"duplicate-put-single-entry\", repeat.get(1) == -1 && repeat.get(2) == 2 && repeat.get(3) == 3);\n\n        if (failed > 0) {\n            System.out.println(\"failed: \" + failed);\n        }\n    }\n}\n"
  }
 ],
 "cases": [
  {
   "name": "全部用例通过",
   "expected": "ok: capacity-1-evicts-oldest\nok: get-missing-returns-1\nok: put-existing-updates-value\nok: put-existing-moves-to-recent\nok: get-moves-to-recent\nok: over-capacity-evicts-oldest\nok: duplicate-put-single-entry\n"
  }
 ],
 "explain": "`LinkedHashMap` 传 `accessOrder=true` 时，`get` 与 `put` 都会把访问到的 key 移到队尾，顺序天然就是\"最近使用在后\"；再覆写 `removeEldestEntry`，超出容量时它自动删掉队头——LRU 需要的两件事都由标准库做了。手写版本是 `HashMap` 加一条双向链表：每次访问把节点移到头部，插入新节点后超容量就删尾部，复杂度同样是 O(1)，但容量 1、更新已有 key 这些边界更容易写错。"
}
```

**提示**：下面这版只用了 `HashMap`，`get`、`put` 的基本语义是对的，但完全没有"顺序"这个概念，跑一次就能看到哪几条 `fail`。测试覆盖：容量 1、查不存在的 key、更新已有 key 的值、更新已有 key 后它变成最近使用、`get` 命中也算"用过"、超过容量淘汰、重复 `put` 同一个 key 不重复占位。
