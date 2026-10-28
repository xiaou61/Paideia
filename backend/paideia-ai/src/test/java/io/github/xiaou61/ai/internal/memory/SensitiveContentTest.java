package io.github.xiaou61.ai.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内容过滤器的判定（WORK-050 AC-001）。
 *
 * <p>两个方向都要测：**该拦的拦住**，以及**不该拦的别误伤**。只测前者的过滤器会在真实使用里
 * 把"密码学""身份证校验算法"这类正常技术讨论一起拒掉，然后没人再敢开这个开关。
 */
class SensitiveContentTest {

    @Test
    @DisplayName("七类敏感形态各拦一次")
    void flagsEachCategory() {
        Map<String, String> cases = Map.of(
                "我的手机号是 13812345678", SensitiveContent.Category.PHONE.name(),
                "身份证 110101199003075514 别弄丢", SensitiveContent.Category.ID_CARD.name(),
                "卡号 6222021234567890123", SensitiveContent.Category.BANK_CARD.name(),
                "-----BEGIN RSA PRIVATE KEY-----\nMIIE...", SensitiveContent.Category.PRIVATE_KEY.name(),
                "key 是 sk-abcdefghijklmnop1234", SensitiveContent.Category.API_KEY.name(),
                "password=Hunter2xyz", SensitiveContent.Category.KEY_VALUE.name(),
                "连接串 jdbc:mysql://root:pw@10.0.0.1:3306/db", SensitiveContent.Category.CONNECTION_STRING.name());

        cases.forEach((text, category) -> assertThat(SensitiveContent.find(text))
                .as(text)
                .contains(SensitiveContent.Category.valueOf(category)));
    }

    @Test
    @DisplayName("技术讨论与类型声明不误伤")
    void doesNotFlagTechnicalText() {
        List<String> safe = List.of(
                "密码学是一门研究加密的学科",
                "身份证校验算法是模 11 加权求和",
                "他喜欢先给结论再解释",
                "type Credential = { password: string }",
                "if (password != null) { return; }",
                "MySQL 索引失效的三种常见情况",
                "他在读《数据密集型应用系统设计》",
                "快速排序的平均复杂度是 O(n log n)",
                "订单号 20261028000123 已经发出",
                "第 1234567890 条记录");

        for (String text : safe) {
            assertThat(SensitiveContent.find(text)).as(text).isEmpty();
        }
    }

    @Test
    @DisplayName("空输入不当成敏感")
    void blankIsNotSensitive() {
        assertThat(SensitiveContent.find(null)).isEmpty();
        assertThat(SensitiveContent.find("")).isEmpty();
        assertThat(SensitiveContent.find("   ")).isEmpty();
    }
}
