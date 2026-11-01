# Spring 项目骨架（沙箱镜像里的 `/opt/skeleton` 的副本）

`paideia-sandbox` 跑 `language=spring` 时，容器先把镜像里的 `/opt/skeleton` 拷到 `/tmp/app`，
再把课程给的 `files` 与学习者改的那个文件写到 `src/` 下，最后 `mvn -o test`。

**这份副本是为了让 `scripts/verify_spring_project.py` 能在本机跑**——骨架原本只存在于服务器镜像
`paideia-spring:3` 里，仓库里没有，等于"判分依赖的东西不可复现"。

**改这里必须重建镜像**（`paideia-spring:<n+1>`）并同步更新服务器上的 `sandbox.spring-image`，
否则线上与本地校验会跑两套骨架。两边的差异只有依赖缓存位置（镜像里是 `/opt/m2`）。
