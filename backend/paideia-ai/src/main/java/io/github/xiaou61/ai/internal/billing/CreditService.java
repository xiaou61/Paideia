package io.github.xiaou61.ai.internal.billing;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分的预扣、结算与调整（WORK-009）。
 *
 * <p><b>为什么是"先预留、后结算"而不是"调完再扣"</b>：纯后扣在并发下必然超扣——多个请求各自
 * 读到"余额够"，最后一个把余额扣成负数。预留用的是一条带 {@code balance >= ?} 的原子 UPDATE，
 * 影响行数就是判据（AC-004）。
 *
 * <p><b>扣减失败一律不发起上游请求</b>（REQ-006）：预留在调用之前同步完成，失败就直接抛
 * {@link ErrorCode#INSUFFICIENT_CREDITS}，界面据此给可操作提示。
 *
 * <p><b>流水只记净额（2026-10-16 用户要求的改动）</b>：预留是**实现细节**，不该出现在用户看的
 * 历史里。所以预留只动余额、**不写流水**；一次调用在流水里就是**一行**（真实消耗多少就写多少）。
 * 代价与补偿：崩在"预留后、结算前"的那笔不再有 {@code RESERVE} 行可查，改由**不变式
 * 「余额 == 初始余额 + 流水求和」是否成立**来发现——正常路径下这个等式严格成立，
 * 不成立就说明有一笔未结算的预留（见 {@code design.md} §12 的已知缺口 1）。
 *
 * <p><b>账面永远不为负</b>：所有扣减都走带条件的 UPDATE；真的扣不动时**不扣**，把差额写进
 * 流水备注留痕，而不是让余额变负或抛错把已经产生的回答吞掉。
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    /** 明细/审计一次最多返回多少行。量级是"调用次数"，先给足；真不够再做分页。 */
    private static final int LIST_LIMIT = 200;

    private final CreditAccountMapper accounts;
    private final CreditLedgerMapper ledger;
    private final BillingProperties billing;

    CreditService(CreditAccountMapper accounts, CreditLedgerMapper ledger, BillingProperties billing) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.billing = billing;
    }

    /** 对外的流水行。 */
    public record LedgerRow(long id, long userId, long delta, String kind, String model,
                            Integer inputTokens, Integer outputTokens, Long refMessageId,
                            Long operatorUserId, String note, Instant createdAt) {

        static LedgerRow of(CreditLedger row) {
            return new LedgerRow(row.getId(), row.getUserId(), row.getDelta(), row.getKind(), row.getModel(),
                    row.getInputTokens(), row.getOutputTokens(), row.getRefMessageId(),
                    row.getOperatorUserId(), row.getNote(), row.getCreatedAt());
        }
    }

    public boolean enabled() {
        return billing.isEnabled();
    }

    public long balance(long userId) {
        Long balance = accounts.findBalance(userId);
        return balance == null ? 0 : balance;
    }

    /**
     * 一次调用的预留额。
     *
     * <p>ponytail: 不引入 tokenizer 去精确预算——那是一个新依赖，而预留只为"并发时不超扣"服务。
     * 代价是余额会被短暂多占（毫秒级，结算立即归还）。真出现"并发下余额被占住导致误拒"的实测，
     * 再改成"按上一次同类调用的用量滑动平均"。
     */
    public long estimate() {
        return Math.max(1, Math.round(billing.getMaxCreditsPerCall() * billing.getReserveFactor()));
    }

    /**
     * 预留：从余额里先扣掉预留额。
     *
     * <p>**只动余额、不写流水**（见类注释）：它是实现细节，不是一次发生的交易。
     *
     * @return 实际预留的额度（调用方要把它带回来做结算）
     * @throws BizException {@link ErrorCode#INSUFFICIENT_CREDITS} 余额不足；**此时尚未发起任何上游请求**
     */
    @Transactional
    public long reserve(long userId, long amount) {
        if (!billing.isEnabled()) {
            return 0;
        }
        if (amount <= 0) {
            return 0;
        }
        if (accounts.deductIfEnough(userId, amount) == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_CREDITS,
                    "积分不足，本次需要预留 " + amount + " 分（当前 " + balance(userId) + " 分）。"
                            + "当前没有自动充值，请联系管理员。");
        }
        return amount;
    }

    /**
     * 结算：按**实际用量**算出该扣多少，与预留的差额补退；流水只写**一行净额**。
     *
     * <p>三步：
     * <ol>
     *   <li>折算并**截到单次上限**（REQ-010）；</li>
     *   <li>实际 &lt; 预留 → 退还差额（常态）；实际 &gt; 预留 → 补扣差额，补不动就**不扣**、
     *       差额写进备注留痕（账面永不为负）；</li>
     *   <li>把**真实扣掉的那一笔**写成一行 {@code SETTLE}。净额为 0 时不写行——
     *       "没有消耗"就是没有流水。</li>
     * </ol>
     *
     * <p>用量为 {@code null}（上游没给）时按 0 计——**退还全部预留**，于是净额为 0、不写流水：
     * 没有依据就不该扣。
     */
    @Transactional
    public int settle(long userId, long reserved, String model, Integer inputTokens, Integer outputTokens,
                      Long refMessageId) {
        return settleAs(userId, reserved, CreditLedger.KIND_SETTLE, model, inputTokens, outputTokens, refMessageId);
    }

    /**
     * 自动整理记忆的结算（WORK-013）。
     *
     * <p>种类单独记（{@code MEMORY_EXTRACT}），因为它是"我没聊几次怎么花了这么多"的唯一解释处：
     * 与对话的消耗混成一类，用户对着账目算不明白，面板也没法单独显示"最近一次整理花了多少"。
     */
    @Transactional
    public int settleMemoryExtraction(long userId, long reserved, String model,
                                      Integer inputTokens, Integer outputTokens, Long refMessageId) {
        return settleAs(userId, reserved, CreditLedger.KIND_MEMORY_EXTRACT, model, inputTokens, outputTokens, refMessageId);
    }

    /**
     * 面试点评的结算（WORK-010）。
     *
     * <p>种类单独记：一场面试里点评是单次最贵的一笔（要读整场对话），与逐轮对话混成一类之后，
     * 用户无法回答"这次面试到底花在哪"。
     */
    @Transactional
    public int settleInterviewReview(long userId, long reserved, String model,
                                     Integer inputTokens, Integer outputTokens, Long refMessageId) {
        return settleAs(userId, reserved, CreditLedger.KIND_INTERVIEW_REVIEW, model, inputTokens, outputTokens, refMessageId);
    }

    /**
     * 简历相关调用的结算（WORK-018）。
     *
     * <p>解析 / 分析 / 提案三次调用共用一个种类：它们在用户眼里是"改简历"这一件事，
     * 拆成三种只会让账目更难读。三次各自的用量仍在流水行里（模型 + 输入 + 输出 token）。
     */
    @Transactional
    public int settleResume(long userId, long reserved, String model,
                            Integer inputTokens, Integer outputTokens, Long refMessageId) {
        return settleAs(userId, reserved, CreditLedger.KIND_RESUME, model, inputTokens, outputTokens, refMessageId);
    }

    /**
     * 训练营出卷/复测的结算（WORK-029）。
     *
     * <p>种类单独记（{@code CAMP}）：一个营至少两笔调用（诊断 + 复测），与简历混成一类之后，
     * 用户没法回答"这个营到底花了多少"。
     */
    @Transactional
    public int settleCamp(long userId, long reserved, String model,
                          Integer inputTokens, Integer outputTokens, Long refMessageId) {
        return settleAs(userId, reserved, CreditLedger.KIND_CAMP, model, inputTokens, outputTokens, refMessageId);
    }

    /** 复习出题的结算（WORK-034）：与训练营同一套预留/结算，只是流水种类分开。 */
    @Transactional
    public int settleReview(long userId, long reserved, String model,
                            Integer inputTokens, Integer outputTokens) {
        return settleAs(userId, reserved, CreditLedger.KIND_REVIEW, model, inputTokens, outputTokens, null);
    }

    /**
     * 结算的公共实现。
     *
     * <p>返回**真正扣掉的那一笔**（WORK-022）：观测要记"这一轮花了多少"，
     * 而那是结算算出来的，调用方原先只知道自己预留了多少。返回 0 表示没扣。
     */
    private int settleAs(long userId, long reserved, String kind, String model,
                         Integer inputTokens, Integer outputTokens, Long refMessageId) {
        if (!billing.isEnabled() || reserved <= 0) {
            return 0;
        }
        long actual = Math.min(
                billing.creditsFor(model, orZero(inputTokens), orZero(outputTokens)),
                billing.getMaxCreditsPerCall());

        long deducted = reserved;
        String note = null;
        if (actual < reserved) {
            accounts.add(userId, reserved - actual);
            deducted = actual;
        } else if (actual > reserved) {
            long excess = actual - reserved;
            if (accounts.deductIfEnough(userId, excess) == 1) {
                deducted = actual;
            } else {
                log.warn("ai.credit.settle_shortfall userId={} reserved={} actual={}", userId, reserved, actual);
                note = "余额不足以补扣差额 " + excess + " 分";
            }
        }
        if (deducted > 0) {
            write(userId, -deducted, kind, model, inputTokens, outputTokens, refMessageId, null, note);
        }
        return (int) Math.max(deducted, 0);
    }

    /**
     * 全额退还：上游一轮都没返回用量、或这次调用根本没发生。
     *
     * <p>**不写流水**：净额为零——余额回到预留前的样子，就没有"发生过一笔交易"这回事。
     * 调用失败这件事本身在 SSE 与日志里可见，不需要在账本上留一条金额为 0 的行。
     */
    @Transactional
    public void refund(long userId, long reserved, Long refMessageId, String note) {
        if (!billing.isEnabled() || reserved <= 0) {
            return;
        }
        accounts.add(userId, reserved);
        log.info("ai.credit.refunded userId={} reserved={} reason={}", userId, reserved, note);
    }

    /**
     * 外部 MCP 问答的结算（WORK-019）。
     *
     * <p>种类单独记（{@code MCP_ASK}）：外部调用不产生会话消息，也**没有 ref_message_id**——
     * 与对话混成一类之后，用户对账时会发现"一笔没有对应消息的扣费"，那正是需要解释的地方。
     */
    @Transactional
    public int settleExternalAsk(long userId, long reserved, String model,
                                 Integer inputTokens, Integer outputTokens) {
        return settleAs(userId, reserved, CreditLedger.KIND_MCP_ASK, model, inputTokens, outputTokens, null);
    }

    /**
     * 管理员调整。
     *
     * @return 调整后的余额
     * @throws BizException 扣减时余额不足、或数量为 0
     */
    @Transactional
    public long adjust(long userId, long delta, long operatorUserId, String note) {
        if (delta == 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "调整数量不能为 0");
        }
        if (delta > 0) {
            accounts.add(userId, delta);
        } else if (accounts.deductIfEnough(userId, -delta) == 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "该用户余额不足以扣减 " + (-delta) + " 分（当前 " + balance(userId) + " 分）");
        }
        write(userId, delta, CreditLedger.KIND_ADJUST, null, null, null, null, operatorUserId, note);
        return balance(userId);
    }

    /** 本人明细。 */
    public List<LedgerRow> listForUser(long userId, int limit) {
        return ledger.listByUser(userId, clamp(limit)).stream().map(LedgerRow::of).toList();
    }

    /** 管理端审计：全平台最近的流水。 */
    public List<LedgerRow> auditRecent(int limit) {
        return ledger.listRecent(clamp(limit)).stream().map(LedgerRow::of).toList();
    }

    /** 管理端审计的分页结果。 */
    public PageResult<LedgerRow> auditPage(Long userId, String kind,
                                           java.time.Instant from, java.time.Instant to,
                                           PageQuery page) {
        String kindFilter = kind == null || kind.isBlank() ? null : kind.trim();
        long total = ledger.countAudit(userId, kindFilter, from, to);
        if (total == 0L) {
            return PageResult.of(0L, page, List.of());
        }
        return PageResult.of(total, page, ledger
                .pageAudit(userId, kindFilter, from, to, page.limit(), page.offset())
                .stream().map(LedgerRow::of).toList());
    }

    private static int clamp(int limit) {
        return limit <= 0 ? LIST_LIMIT : Math.min(limit, LIST_LIMIT);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void write(long userId, long delta, String kind, String model, Integer inputTokens,
                       Integer outputTokens, Long refMessageId, Long operatorUserId, String note) {
        CreditLedger row = new CreditLedger();
        row.setUserId(userId);
        row.setDelta(delta);
        row.setKind(kind);
        row.setModel(model);
        row.setInputTokens(inputTokens);
        row.setOutputTokens(outputTokens);
        row.setRefMessageId(refMessageId);
        row.setOperatorUserId(operatorUserId);
        row.setNote(note);
        ledger.insert(row);
    }
}
