---
title: 把容器焊死：非 root、只读根、无网络
category: docker
order: 17
minutes: 21
level: advanced
status: published
---

## 64MB 的 /tmp 和一行 nofile

本仓库判题沙箱拼出来的命令在 `backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/DockerCommand.java`。Java 单文件那段脚本是：

```text
read -r size
dd bs=1 count="$size" of=/tmp/Main.java 2>/dev/null
cd /tmp && javac Main.java && java Main
```

外面的参数里有 `--read-only`、`--tmpfs /tmp:rw,size=64m,exec`、`--user 65534:65534`、`--cap-drop=ALL`，还有一行 `--ulimit nofile=64`。Java 单文件那条路径先撞的是 `javac` 写不进去；Spring 项目那条路径撞的才是下面这个类缺失。

Spring 项目那条路径第一版也用了 `nofile=64`，报错是：

```text
[ERROR] Execution default-resources of goal maven-resources-plugin:3.3.1:resources failed:
        A required class was missing while executing ...: org/apache/commons/lang3/StringUtils
```

我第一反应是离线 Maven 仓库没烤全，去 `~/.m2` 里找 `commons-lang3`。找到之后更迷惑：jar 在，版本也对。把 `nofile` 从 64 提到 1024，同一个镜像、同一份代码，过了。这个失败后来记在 `DockerCommand.java` 的注释里，Spring 路径也单独放宽到 1024。

（这台开发机没装 Docker。上面这段是本仓库的代码与它注释里记的报错，不是我这次在本机跑的；后面的容器内输出是按 Docker/Linux 官方文档写的标准形态，也没有实跑。）

## 为什么 fd 不够会变成类缺失

Maven 的插件类加载器要同时打开很多 jar。`nofile=64` 的上限里还要扣掉已经打开的 stdin/stdout/stderr，真正能留给新 `open()` 的更少；打不开 jar 时，异常被包成了「类缺失」，日志里看不到 `EMFILE`。这不是依赖缺失，是打开文件数不够。

最省事的验证是把 `nofile` 提上去再跑一次；要抓根因，在容器里 `strace -f -e trace=openat`，能看到 `EMFILE`。但本仓库的镜像里没装 strace，排查成本高；所以做法是给 Maven 单独一档 `nofile=1024`，而不是把整个沙箱的上限都放大。

## 默认的 docker run 不是「root 能写整个文件系统」

默认容器的进程是 UID 0。没开 userns-remap 或 rootless 时，这个 0 在宿主上也是 0。但它不是「什么都行」：

- Docker 默认只给 14 个 capability：`CHOWN`、`DAC_OVERRIDE`、`FOWNER`、`FSETID`、`KILL`、`SETGID`、`SETUID`、`SETPCAP`、`NET_BIND_SERVICE`、`NET_RAW`、`SYS_CHROOT`、`MKNOD`、`AUDIT_WRITE`、`SETFCAP`。Linux 全部 capability 是 40 多项，差的那些（`SYS_ADMIN`、`SYS_MODULE`、`SYS_PTRACE`、`NET_ADMIN` 等）才是挂载、加载模块、跟踪进程、改网络配置的钥匙。
- 默认 seccomp profile 会挡掉 40 多个系统调用（`mount`、`reboot`、`kexec_load` 等），不是只靠 capability。
- 启用 AppArmor 的宿主上还有 `docker-default` profile；SELinux 发行版是 `container_t`。
- 它能写的是容器的可写层、显式挂成 rw 的 volume/bind mount、tmpfs，以及 `/dev/shm`。宿主上没挂进来的路径，它看不见。

所以「默认 docker run 不够」这个结论对，但理由不是「root 能写整个文件系统」，而是这几层默认值加在一起仍然挡不住不可信代码：它还能出网、还能用那 14 个 capability 里的 `NET_RAW`/`DAC_OVERRIDE`、还能把可写层写到宿主磁盘满、还能 fork。

## --read-only 只改根，不改挂载

`--read-only` 把容器的根文件系统挂成 ro。它不会把 volume、bind mount、tmpfs 一起变只读——这些挂载有自己的 rw/ro 选项。

- `-v /host/cache:/app/cache` 默认是 rw；要只读得写 `:ro`。
- `docker run -v appdata:/data` 的 named volume 默认 rw。
- `--tmpfs /tmp:rw,size=64m,exec` 是 rw；`exec` 只影响能不能执行里面的文件。
- `/dev/shm` 是 Docker 默认挂的 tmpfs（默认 64MB，`noexec`），`--read-only` 不会把它变只读。
- 镜像里 `VOLUME /data` 声明的位置是独立挂载，也是 rw。

所以「只读根之后只能写 tmpfs」是错的。要把可写面收窄到 `/tmp`（再加上 Docker 默认的 `/dev/shm`），得同时做三件事：不给 rw 的 volume/bind mount；需要读的源码用 `:ro`；用 `docker inspect --format '{{json .Mounts}}'` 和容器里的 `findmnt`/`cat /proc/mounts` 对一遍。`/dev/shm` 如果也不想让写，得单独覆盖，`--read-only` 不管它。

## 六个参数不是全套

`--user`、`--read-only`、`--cap-drop=ALL`、`--network=none`、`--pids-limit`、`--memory/--cpus` 是资源与权限的六个方向，但它们没有覆盖这四层：

| 层 | 默认 | 这套参数里有没有 | 还要做什么 |
| --- | --- | --- | --- |
| seccomp | Docker 默认 profile，挡 40 多个 syscall | 没有显式写 | 保持默认；高价值目标用自定义 allowlist |
| AppArmor/SELinux | `docker-default` / `container_t` | 没有显式写 | AppArmor 不要 `unconfined`；SELinux 不要 `label=disable` |
| user namespace | 默认关闭，容器 UID 0 = 宿主 UID 0 | 没有 | `userns-remap` 或 rootless |
| runtime | runc，直接跑宿主内核 | 没有 | gVisor `runsc` / Kata |

`--cap-drop=ALL` 不能替代 seccomp：capability 管的是「这个操作有没有权限」，seccomp 管的是「这个系统调用能不能进来」。没有 capability 的进程仍然可以调用大量非特权 syscall，内核攻击面还在；seccomp 是在 syscall 入口先筛一遍。

`--privileged` 会同时放开 capability、seccomp、AppArmor；`--security-opt seccomp=unconfined` 只关 seccomp，但已经足够让「cap-drop 很干净」的容器多出一层内核攻击面。

本仓库的 `DockerCommand` 留了 `--runtime=` 的口子：配置非空才加。`backend/paideia-app/src/main/resources/application.yml` 里 `runtime: ""`，所以默认还是 runc；切 gVisor 是升级档，不是默认。

## 那条 javac 命令照抄跑不起来

一种常见的写法是：

```text
docker run ... --entrypoint sh eclipse-temurin:21-jdk-alpine -c 'javac Main.java && java Main'
```

三个问题，前两个会让它直接失败，第三个会让它把构建产物和源码混在一起：

1. 镜像里没有 `Main.java`。`sh -c` 的当前目录是镜像的 `WORKDIR`，不是你的源码目录。
2. `--read-only` 下当前目录不可写。`javac` 默认把 `Main.class` 写在源文件旁边；源文件在只读的当前目录里，class 就写不出去。
3. 就算当前目录可写，class 也不该和源码混在一起。class 输出到独立目录，运行时用 `-cp` 指过去，才不会把源码目录当成构建产物。

本仓库项目模式的写法是：

```text
cd /tmp/work && javac -d /tmp/work/out $(find . -name '*.java') && java -cp /tmp/work/out TestMain
```

单文件模式的脚本则是 `cd /tmp && javac Main.java && java Main`——它能成立，是因为 `/tmp` 是显式挂的 64MB tmpfs，源码也是先 `dd` 到 `/tmp/Main.java`，不是靠镜像里预置文件。

## /tmp 的三种失败，和 Maven 的 HOME

把「/tmp 太小」当成一个坑，会误导排查方向；其实至少是三种：

### 1. 根只读，当前目录不可写

```text
$ docker run --rm --read-only --user 65534:65534 \
    -v "$PWD/src:/src:ro" -w / \
    eclipse-temurin:21-jdk-alpine sh -c 'javac -d / /src/Main.java'
... Read-only file system
```

`javac` 读得到 `/src/Main.java`，写不了 `/Main.class`。这里把写目标放在 `/`，不是 `/src`；`/src` 是 `:ro` 的 bind mount，写它会失败是另一个原因。换成 `touch out` 会把同一个 errno 暴露得更直接：

```text
$ docker run --rm --read-only --user 65534:65534 \
    -w / alpine sh -c 'touch out'
touch: out: Read-only file system
```

修法不是把根改回可写，而是把工作目录搬到 tmpfs。容器要带 `--tmpfs /tmp:rw,size=64m,exec`，然后在容器里：

```text
mkdir -p /tmp/work/out
cp /src/Main.java /tmp/work/
cd /tmp/work
javac -d /tmp/work/out Main.java
java -cp /tmp/work/out Main
```

### 2. tmpfs 默认 noexec

```text
$ docker run --rm --read-only --tmpfs /tmp:rw,size=64m \
    --user 65534:65534 alpine \
    sh -c 'cp /bin/true /tmp/x && chmod +x /tmp/x && /tmp/x'
sh: /tmp/x: Permission denied
```

Docker 的 `--tmpfs` 默认挂载选项里有 `noexec`；不显式写 `exec`，放到 `/tmp` 里的程序就执行不了。这个失败和 `javac` 无关：`javac`/`java` 的二进制在 `/usr`，class 文件也不需要可执行位。`exec` 是给那些把编译产物放到 `/tmp` 再执行的场景准备的；只跑 Java，`noexec` 反而更安全。本仓库为了几种语言共用一套参数，统一写了 `exec`；只跑 Java 的话可以去掉。老版本 Docker 在 `--tmpfs` 里带了其他选项时可能不套默认值；复现时显式写 `noexec` 即可。

### 3. tmpfs 太小

```text
$ docker run --rm --read-only --tmpfs /tmp:rw,size=1m,exec \
    --user 65534:65534 alpine \
    sh -c 'dd if=/dev/zero of=/tmp/big bs=1M count=2'
dd: error writing '/tmp/big': No space left on device
```

`size=1m` 是硬上限。tmpfs 写进去的页会计入容器内存，所以「把 size 压到最小」不是安全，是换一种 OOM：512MB 的 tmpfs 配 256MB 内存上限，可能在写满之前就被内核杀掉。本仓库的 Java 单文件给 64MB，Spring 给 512MB，SQL 给 768MB，是按各自要写的量定的，不是统一值。

### 4. Maven 的 HOME 还在 /root

这条不是 `/tmp` 的问题，但会和上面三条一起出现。基础镜像把 `MAVEN_CONFIG=/root/.m2` 写进 ENV；`--user 65534` 读不到 `/root`，`--read-only` 也写不了。只改 `HOME` 不生效，要同时给：

```text
-e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2
```

本仓库的 Spring 路径就是这么做的（`.agent/notes/spring-project-sandbox-spike.md` 里记了这条）。如果 Maven 说 `copy_reference_file.log` 写不了，先查这两个环境变量，不要先怀疑镜像。

## 验证要验五件事，不是三条「都失败」

常见的验证命令是：

```text
docker run --rm --network=none --read-only --cap-drop=ALL alpine sh -c \
  'wget -T2 http://1.1.1.1 ; touch /root/x ; ping -c1 127.0.0.1'
```

它有三个问题：没带 `--user`，所以根本没验证非 root；`touch /root/x` 失败只能说明 `/root` 在只读根上，不能说明非 root 的写权限；`ping 127.0.0.1` 在 `--network=none` 下失败，更可能是 `--cap-drop=ALL` 拿掉了 `CAP_NET_RAW`，不是「断网」——loopback 还在。把两件事混在一行里，失败了也不知道是哪一层生效。

正确的验法是一条命令看五件事：

```text
$ docker run --rm --network=none --read-only \
    --tmpfs /tmp:rw,size=64m,exec \
    --user 65534:65534 --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=64 --memory=256m --memory-swap=256m --cpus=0.5 \
    --entrypoint sh debian:12-slim -c '
      echo "uid=$(id -u)"
      grep -E "^(CapEff|Seccomp|NoNewPrivs):" /proc/self/status
      findmnt -no TARGET,FSTYPE,OPTIONS /
      cat /proc/net/route'
```

标准输出形态：

```text
uid=65534
CapEff:	0000000000000000
Seccomp:	2
NoNewPrivs:	1
/ overlay ro,relatime,...
Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
```

`CapEff=0` 对照 `capsh --decode`：Docker 默认 14 项对应的掩码是 `00000000a80425fb`，解码就是前面列的那 14 项；`--cap-drop=ALL` 之后是 0。`Seccomp: 2` 表示过滤模式；`NoNewPrivs: 1` 表示 `no-new-privileges` 生效。`findmnt /` 看 `ro`；`/proc/net/route` 没有默认路由。要确认只有 `lo`，再加一句 `cat /proc/net/dev`。

`capsh --decode=00000000a80425fb` 在宿主上跑（要装 libcap2-bin）；容器里没这个命令，不要为了它联网装包。想看默认 14 项，直接跑一个不带 `--cap-drop` 的容器：`grep CapEff /proc/self/status`。

`ping` 不要拿来验断网：`--cap-drop=ALL` 下它失败更可能是 `CAP_NET_RAW` 没了，或者镜像里的 ping 走 raw socket；`--network=none` 下 loopback 仍然在。要验 loopback，用 Python 绑 `127.0.0.1`，或者直接看 `/proc/net/route` 没有默认路由。

## 生产环境不这么焊

「生产要写日志文件，所以得留可写层」是坏实践：应用的日志应该走 stdout/stderr，由日志驱动收走；需要写的缓存、上传目录、临时文件，挂 volume 或 tmpfs。根文件系统仍然可以 `--read-only`。

生产容器的加固是另一套组合：非 root、只读根、`--cap-drop=ALL` 后按需 `--cap-add`、`no-new-privileges`、保留默认 seccomp/AppArmor、资源上限、网络策略。它不需要 `--network=none`（服务要接数据库和上游），也不会把 `--pids-limit` 压到 64（本仓库的 Java、Python、shell 用 64，SQL 用 128，Spring 用 256）。

这套「焊死」是给**代码作者不可信**的场景：判题沙箱、CI 构建、多租户函数计算。判断标准不是「是不是生产」，是「这段代码的作者是不是你信任的人」。

## 动手

用本仓库的镜像实操一次（镜像名按你本地实际改；本机没 Docker 的话，这段只能读）：

```text
$ docker run --rm -i \
    --network=none --read-only \
    --tmpfs /tmp:rw,size=512m,exec \
    --user 65534:65534 --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=256 --memory=1g --memory-swap=1g --cpus=2 \
    --ulimit nofile=1024 --ulimit nproc=256 \
    --entrypoint sh paideia-spring:3 \
    -c 'mkdir -p /tmp/app /tmp/home && cp -a /opt/skeleton/. /tmp/app/ \
        && cd /tmp/app \
        && HOME=/tmp/home MAVEN_CONFIG=/tmp/home/.m2 \
           mvn -o -q -Dmaven.repo.local=/opt/m2/repository test'
```

再把 `nofile=1024` 改成 `64` 跑一次。如果它报 `A required class was missing`，先别去补依赖，先看打开文件数。

## 检查一下

```quiz
{"id":"docker-17-quiz","title":"容器加固四问","questions":[
 {"question":"`--read-only` 之后，下面哪种说法对？","options":["根只读；显式挂成 rw 的 volume、bind mount、tmpfs 和默认的 `/dev/shm` 仍可写","根只读；容器里任何路径都不能写","根只读；只有 tmpfs 可写，volume 也会跟着变只读"],"answer":0,"explain":"`--read-only` 只改根文件系统；每个挂载有自己的 rw/ro。`/dev/shm` 是 Docker 默认挂的 tmpfs，默认可写。要收窄可写面，得逐个检查 `.Mounts` 与 `findmnt`。"},
 {"question":"`--ulimit nofile=64` 在 Maven 项目里可能表现成什么？","options":["「类缺失」这类看起来像依赖没下全的错","一定是明确的 `too many open files`","只会让编译变慢，不会失败"],"answer":0,"explain":"本仓库的 Spring 沙箱记录到的是 `A required class was missing ... StringUtils`。插件类加载器打不开 jar 时，异常被包成了类缺失；把 nofile 提到 1024 就恢复。"},
 {"question":"`--cap-drop=ALL` 之后还需要 seccomp 吗？","options":["需要；capability 管权限检查，seccomp 管系统调用面，两者不能互相替代","不需要；没有 capability 就没有内核攻击面","不需要；`--privileged` 会自动补上 seccomp"],"answer":0,"explain":"cap-drop 去掉的是特权操作的钥匙，非特权 syscall 仍然能进内核；seccomp 在入口按 syscall 过滤。`--privileged` 和 `seccomp=unconfined` 反而会把这一层关掉。"},
 {"question":"哪类场景最需要这套「焊死」参数？","options":["运行作者不可信的代码：判题沙箱、CI 构建、多租户函数","所有生产 Web 服务","本地开发环境"],"answer":0,"explain":"生产服务要接数据库和上游，`--network=none` 不适用；它需要的是非 root、只读根、cap-drop、资源上限和网络策略。判据是代码作者是否可信。"}
]}
```

## 还差的

- 这台机器没装 Docker，容器内的 `id`、`CapEff`、`findmnt` 输出没有实跑；上面的命令按 Docker/Linux 官方文档和本仓库代码写。
- 没有验证 gVisor/Kata 与这两个镜像的兼容性。`DockerCommand` 只在配置非空时加 `--runtime=`，`application.yml` 的 `runtime: ""` 默认仍是 runc。
- 没有写自定义 seccomp 白名单；默认 profile 只是起点。也没有验证 AppArmor/SELinux 在不同发行版上的实际 profile 名。
- 没有验证 `userns-remap` 或 rootless 下 volume 的属主变化。启用后现有 volume 的 uid 映射会变，可能需要重新 `chown`。
- 没有测 `--pids-limit` 对 JVM 线程数的具体下限，也没有做内核 CVE/逃逸测试。本仓库只钉了 64/128/256 几档。