package io.github.xiaou61.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全装配：无状态资源服务。
 *
 * <p>不建会话、不用 Cookie，因此关闭 CSRF——浏览器会话认证在桌面壳里本就不可靠
 * （页面来源于自定义协议，{@code SameSite}/{@code Secure} 语义失效），统一走 Authorization 头。
 *
 * <p>放行规则：健康检查、错误页、以及 <b>{@code /api/v1/auth/**}</b> 下的凭据交换端点；
 * 其余（含 Actuator 的其他端点）一律要求已认证。
 *
 * <p><b>前缀纪律</b>：{@code /api/v1/auth/**} 整体放行 —— 该前缀下只允许放"用凭据换令牌"
 * 或"令牌自身操作"的端点（验证码、发信、注册、登录、刷新、登出）。**不得**把任何读取
 * 业务数据的端点挂到这个前缀下，否则它会默默变成公开接口。这条靠约定而不是靠配置拦住，
 * 所以写在这里显式提醒。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** 角色 claim 对应的权限前缀，与 Spring Security 的 {@code hasRole} 约定一致。 */
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    @Bean
    JwtAuthService authPort(AuthProperties properties) {
        if (!properties.hasSecret()) {
            if (!properties.allowEphemeralSecret()) {
                // 失败而不是静默用随机密钥：重启即令全部令牌失效、多实例之间也无法互验，
                // 误部署到共享环境只会在运行时才暴露。见 AuthProperties 的类注释。
                throw new IllegalStateException("""
                        未配置签名密钥 paideia.auth.secret（环境变量 PAIDEIA_AUTH_SECRET），且未显式允许临时密钥。
                        二选一：
                          · 配置固定密钥：paideia.auth.secret，至少 32 字符
                          · 仅本地或骨架期：显式设置 paideia.auth.allow-ephemeral-secret=true
                        """);
            }
            log.warn("""
                    paideia.auth.allow-ephemeral-secret=true 已显式开启，本次进程使用随机生成的签名密钥。
                    后果：重启后此前签发的令牌全部失效，多实例之间也无法互相校验。""");
            return new JwtAuthService(properties.withSecret(randomSecret()));
        }
        return new JwtAuthService(properties);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 资源服务器复用同一个解码器：令牌的签发与校验规则只有一份，
     * 不会出现"签发用一套、校验用另一套"的错位。
     */
    @Bean
    JwtDecoder jwtDecoder(JwtAuthService authPort) {
        return authPort.decoder();
    }

    /**
     * 把角色 claim 映射为权限。
     *
     * <p>必须显式提供：Spring 默认只从 {@code scope}/{@code scp} 读权限，而我们的角色在
     * {@code role} claim 里 —— 不映射的话令牌通过校验却没有任何权限，表现为"已登录但什么都不能做"。
     *
     * <p>角色值是小写的（与数据库和前端一致），权限名统一大写为 {@code ROLE_ADMIN} /
     * {@code ROLE_LEARNER}，与 {@code hasRole('ADMIN')} 的约定对齐。**认不出的角色映射为
     * 空权限**（认证通过但无权），不做任何降级猜测。
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString(JwtAuthService.ROLE_CLAIM);
            List<GrantedAuthority> authorities = role == null || role.isBlank()
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority(
                            ROLE_AUTHORITY_PREFIX + role.toUpperCase(Locale.ROOT)));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        http
                // 必须显式接管 CORS：否则预检 OPTIONS 会先被下面的 anyRequest().authenticated() 挡成 401，
                // 表现为"浏览器直接调通、跨域调用全挂"。规则本身来自 paideia-web 的 WebMvcConfigurer。
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                // iframe 嵌入（WORK-028）：运维面板由 Grafana 渲染、被管理端同源 iframe 嵌入，
                // 而 Spring Security 默认发 `X-Frame-Options: DENY`——实测浏览器直接拒绝显示面板。
                // 改成 SAMEORIGIN：仍然禁止**任何第三方站点**嵌入本服务（这才是点击劫持的威胁），
                // 只是允许我们自己同源嵌入。DENY 额外多挡的"本站自嵌"没有防护价值——
                // 能控制本站页面的人已经不需要靠 iframe 来做事了。
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // 指标端点（WORK-028）：只允许**本机来源**读。8081 是公网端口，
                        // /actuator/prometheus 会暴露 JVM 内部结构与业务维度标签，不能像 health
                        // 那样整体放行。**触发重评**：一旦本进程前面挂了反向代理，远端地址会
                        // 变成 127.0.0.1，这条白名单即失效——那时必须改为独立回环管理端口。
                        .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
                        .access(LocalOnlyAuthorizationManager.INSTANCE)
                        .requestMatchers("/error").permitAll()
                        // 见类注释的前缀纪律：这个前缀下只放凭据交换类端点
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 账号的管理端接口（WORK-030）。**必须排在任何更宽的 /api/v1/account 规则
                        // 与 anyRequest() 之前**：顺序写反这条永不生效、也不会有任何报错，只会在
                        // 某天表现为"任何已登录的学习者都能列出全部账号、改别人的角色、删别人的号"。
                        // 现有 /api/v1/account/** 没有专门规则（落 authenticated），但靠 anyRequest
                        // 兜底来保护管理端接口太脆弱，所以显式写在这里。
                        .requestMatchers("/api/v1/account/admin/**").hasRole("ADMIN")
                        // 运维面板（WORK-028）。两条都**必须排在 anyRequest() 之前**：
                        //   ① 摘要接口与其它管理端接口同权；
                        //   ② Grafana 反代是面板的唯一入口，它内部开匿名 Viewer——
                        //      这条规则一旦静默失效，等于把 Grafana 直接公开给任何登录用户。
                        .requestMatchers("/api/v1/ops/admin/**").hasRole("ADMIN")
                        // 运维诊断的告警 webhook（WORK-042）：机器到机器，没有 JWT。
                        // 只允许**回环来源**；共享令牌在控制器里用常量时间比较再验一次。
                        // 必须排在 anyRequest() 之前，否则会被当成普通接口要求登录。
                        .requestMatchers("/api/v1/ops/hooks/**")
                        .access(LocalOnlyAuthorizationManager.INSTANCE)
                        // Grafana 反代**整体放行，安全边界交给 Grafana 自己**（WORK-028）。
                        //
                        // 试过而放弃的三条路，每条都有实测证据：
                        //   ① 要求 hasRole("ADMIN")：iframe 的导航请求不带 Authorization 头 → 401；
                        //   ② 加 ?ticket= 查询参数：导航能过，但 SPA 的静态资源与 API 子请求
                        //      既不继承头、也不带查询串 → 逐个 401，面板永远停在
                        //      "Grafana has failed to load its application files"；
                        //   ③ 只放行 /public/**：资源过了，dashboard API 又 401。
                        // 根因是**本项目无 Cookie、纯 Bearer**，而 Grafana 是 SPA——它需要一套
                        // 跨请求自动携带的会话，那只能是 Cookie。
                        //
                        // 所以：本前缀放行，Grafana **关掉匿名、开内置登录**，由它自己的会话
                        // cookie 把关。于是"谁能看面板"由 Grafana 口令决定（值在服务器上 600 的
                        // grafana.env 里，不进仓库）。代价是多一套口令，这一点如实记在报告里。
                        .requestMatchers("/api/v1/ops/grafana/**").permitAll()
                        // 知识库的管理端写接口。**必须排在下面的 anyRequest() 之前**：
                        // Spring Security 按声明顺序取第一个匹配的规则，顺序写反这条永不生效，
                        // 且不会有任何报错——只会在某天表现为"任何已登录的学习者都能改知识库"。
                        .requestMatchers("/api/v1/knowledge/admin/**").hasRole("ADMIN")
                        // 笔记的举报处理。同样**必须排在任何更宽的 /api/v1/notes 规则（以及
                        // anyRequest）之前**：顺序写反这条永不生效，且不会有任何报错。
                        .requestMatchers("/api/v1/notes/admin/**").hasRole("ADMIN")
                        // AI 积分的管理端接口（WORK-009）。**必须排在任何更宽的 /api/v1/ai 规则
                        // 之前**：顺序写反这条永不生效、也不会有任何报错，只会在某天表现为
                        // "任何已登录的学习者都能看别人的用量、给自己发积分"。
                        .requestMatchers("/api/v1/ai/admin/**").hasRole("ADMIN")
                        // 速递的管理端删除（WORK-027）。**必须排在任何更宽的 /api/v1/digest 规则
                        // 之前**：顺序写反这条永不生效、也不会有任何报错，只会在某天表现为
                        // "任何已登录的学习者都能删速递条目"。路径用 `<模块>/admin` 而不是
                        // `/api/v1/admin/<模块>`——后者不匹配任何既有规则，会直接落到 anyRequest。
                        .requestMatchers("/api/v1/digest/admin/**").hasRole("ADMIN")
                        // 题目概念标注（WORK-033）。**必须排在 /api/v1/learning 的任何规则之前**：
                        // 顺序写反时这条永不生效、也不报错，表现为任何登录用户都能改题目标签。
                        .requestMatchers("/api/v1/learning/admin/**").hasRole("ADMIN")
                        // 助手回答反馈的管理端列表（WORK-036）。**必须排在下面的
                        // /api/v1/ai/** 之前**：顺序写反这条永不生效、也不报错，
                        // 只会在某天表现为"任何已登录的学习者都能看到全平台的差评"。
                        .requestMatchers("/api/v1/ai/feedback/admin/**").hasRole("ADMIN")
                        // 速递的读接口（WORK-027）：要登录。内容本身是公开新闻，但"哪天看过什么"
                        // 是隐私，且不做匿名访问可以避免它被当成公开抓取源。
                        .requestMatchers("/api/v1/digest/**").authenticated()
                        // AI 精简（WORK-005）：**只要有身份即可，不要求角色**。显式写一条而不是
                        // 靠下面的 anyRequest() 兜住，是为了让"它没有角色限制"成为一个可读的声明——
                        // 否则将来看到这个前缀没有规则，会以为漏配了。余额与明细都是每个用户自己的，
                        // 不是管理操作。
                        .requestMatchers("/api/v1/ai/**").authenticated()
                        // SQL 数据集的管理端（WORK-035）。**必须排在下面的 /api/v1/sandbox/**
                        // 之前**：那条是 authenticated()，顺序写反时这条永不生效、也不报错，
                        // 表现为任何已登录的学习者都能下架别人的数据集、看全部举报。
                        // 这个漏配是本次合并 WORK-035 时由 ApiContractTest 抓到的（真缺陷）。
                        .requestMatchers("/api/v1/sandbox/admin/**").hasRole("ADMIN")
                        // 代码执行（WORK-015）：只需登录——执行的是自己的练习代码，但必须可追溯
                        .requestMatchers("/api/v1/sandbox/**").authenticated()
                        // 只读分享：**全项目第二处免鉴权读端点**（用户裁决 12）。与附件读取同一模型——
                        // id 是 UUID、不可枚举即凭据。**限定 GET**：分享只有读，把 POST 一起放行
                        // 就等于给了匿名写入口。产物只含笔记标题与正文，不含作者、记忆与学习痕迹。
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/shares/**").permitAll()
                        // 附件读取**免鉴权**（用户裁决）：<img> 不携带 Authorization 头，
                        // 而本项目无 Cookie，要求登录就等于图片显示不出来。限定 GET：
                        // 上传挂在 /api/v1/knowledge/admin/files，不能因为放行读取把写入也放开。
                        .requestMatchers(HttpMethod.GET, "/api/v1/knowledge/files/**").permitAll()
                        // 前端静态资源：同源部署时前端由本进程一并提供，入口页与资源目录必须放行，
                        // 否则连登录页都加载不出来（实测：未放行时 GET / 与 GET /admin/index.html 都是 401）。
                        // SPA 用 Hash 路由，所以**不必**为每个前端路由放行；**放行静态文件不等于放行数据**——
                        // 授权仍然只由 /api/** 上的规则决定，前端守卫从来不是授权边界。
                        //
                        // PWA 的三个入口（WORK-016）也必须在这里：浏览器取 manifest 与注册 Service Worker
                        // 都是**匿名请求**（登录前就要能安装），落在 anyRequest().authenticated() 后面
                        // 会拿到 401——实测部署后 manifest 与 sw.js 均为 401，PWA 完全不可用。
                        // 它们只是静态文件，与图标一样不含任何数据。
                        .requestMatchers(
                                HttpMethod.GET,
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/admin",
                                "/admin/**",
                                "/favicon.ico",
                                // PWA：清单、Service Worker 及其运行时、图标
                                "/manifest.webmanifest",
                                "/sw.js",
                                "/workbox-*.js",
                                "/registerSW.js",
                                "/pwa-*.png",
                                "/apple-touch-icon.png")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
