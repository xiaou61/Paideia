package io.github.xiaou61.ai.internal.camp;

import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.platform.ErrorCode;

/**
 * 出题失败（WORK-029）。
 *
 * <p>单独一个异常类型而不是直接抛 {@code BizException}：调用方要根据"是哪一类失败"决定
 * 是否值得修复重试（形状不对值得，空返回也值得，但"一道有效题都没有"不值得）。
 *
 * <p>对外一律映射成 {@link ErrorCode#INTERNAL}：这些原因都是"上游没给出可用结果"，
 * 不是用户输入的错。
 */
public final class CampGenerationException extends RuntimeException {

    public enum Kind {
        /** 模型返回空内容（含全空白）。**绝不能当成"0 道题"**。 */
        EMPTY_RESPONSE,
        /** 不是合法 JSON，或 JSON 形状不符合契约。 */
        BAD_SHAPE,
        /** 修完一轮后仍然一道有效题都没有。 */
        NO_VALID_ITEM
    }

    private final Kind kind;

    private CampGenerationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 这次失败对应的处置（WORK-069）。
     *
     * <p>`EMPTY_RESPONSE` 通常是 max_tokens 被思考吃满，调大配置才可能变好；`BAD_SHAPE` 与
     * `NO_VALID_ITEM` 是模型输出不合规，同配置重来没有意义——都按 `bad_output` 记录。
     */
    public FailureKind failureKind() {
        return switch (kind) {
            case EMPTY_RESPONSE -> FailureKind.NEEDS_HUMAN;
            case BAD_SHAPE, NO_VALID_ITEM -> FailureKind.BAD_OUTPUT;
        };
    }

    public static CampGenerationException emptyResponse() {
        return new CampGenerationException(Kind.EMPTY_RESPONSE,
                "模型返回了空内容，本次生成失败，未扣积分");
    }

    public static CampGenerationException badShape(String detail) {
        return new CampGenerationException(Kind.BAD_SHAPE,
                "模型输出不符合题目格式（" + detail + "），本次生成失败，未扣积分");
    }

    public static CampGenerationException noValidItem() {
        return new CampGenerationException(Kind.NO_VALID_ITEM,
                "这次一道有效的题都没生成出来，本次生成失败，未扣积分");
    }
}
