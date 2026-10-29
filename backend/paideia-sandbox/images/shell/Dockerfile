# Linux 判题练习场的沙箱镜像（WORK-056）。
#
# **为什么是 debian 而不是 alpine**：判题要在执行前后各做一次文件快照，用的是 GNU 的
# `find -printf '%M %s %p'`（BusyBox 的 find 没有 -printf）。而且课程里讲的 `sed -i`、
# `ps` 的列、`grep -P` 也都是 GNU 行为——镜像与教材不一致时，学习者照着课敲会得出
# "课错了"的结论，这比镜像大 100MB 更贵。
#
# **为什么装 systemd**：第 12 关的题用 `systemd-analyze verify` 判 unit 文件。只装包、
# 不启服务——容器里本来也起不了 PID 1 的 systemd，判题也只需要它的静态检查。
#
# 基础镜像按 digest 钉住：`repo:tag` 会漂移，而漂移意味着"今天验过的隔离参数明天可能配到
# 一个不同的基础镜像"。digest 由 build.sh 打印，改完记得回填这里。
FROM debian:12-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        coreutils \
        findutils \
        grep \
        sed \
        gawk \
        procps \
        util-linux \
        systemd \
    && rm -rf /var/lib/apt/lists/*

# 构建期自检：判题依赖的四样能力缺一个就让构建红，而不是等运行时第一次提交才发现。
# （find -printf 是快照、awk 是第 07 关、systemd-analyze 是第 12 关、bash 是学习者脚本的解释器）
RUN find --version | head -1 \
    && awk --version | head -1 \
    && bash --version | head -1 \
    && systemd-analyze --version | head -1
