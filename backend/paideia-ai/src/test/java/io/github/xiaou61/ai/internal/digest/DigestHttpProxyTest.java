package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GitHub 那一路的代理解析（2026-11-10 线上）。
 *
 * <p>只有 GitHub 走代理，其它源直连；地址写坏必须退回直连——整组因为一个配置字符串变空，
 * 比直连超时更难查。
 */
class DigestHttpProxyTest {

    @Test
    @DisplayName("空配置 = 直连")
    void blankMeansDirect() {
        assertThat(DigestHttp.proxyAddress("")).isNull();
        assertThat(DigestHttp.proxyAddress(null)).isNull();
        assertThat(DigestHttp.proxyAddress("   ")).isNull();
    }

    @Test
    @DisplayName("host:port 与 http://host:port 等价")
    void parsesBothForms() {
        assertThat(DigestHttp.proxyAddress("127.0.0.1:7890"))
                .isEqualTo(InetSocketAddress.createUnresolved("127.0.0.1", 7890));
        assertThat(DigestHttp.proxyAddress("http://127.0.0.1:7890"))
                .isEqualTo(InetSocketAddress.createUnresolved("127.0.0.1", 7890));
    }

    @Test
    @DisplayName("写坏了退回直连，不抛")
    void brokenAddressFallsBackToDirect() {
        assertThat(DigestHttp.proxyAddress("没有端口")).isNull();
        assertThat(DigestHttp.proxyAddress("http://:7890")).isNull();
        assertThat(DigestHttp.withOptionalProxy(5, "没有端口")).isNotNull();
    }
}
