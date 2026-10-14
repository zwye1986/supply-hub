package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengSecurityReason;

import java.util.Set;

/**
 * 华昇错误分类纯规则：按端点与阶段把整数原始错误码投影为封闭动作类别。
 *
 * <p>职责：维护第 4 节错误分类规则的纯逻辑部分——创建拒绝候选、无副作用永久拒绝、
 * 暂时错误、原单核实，以及可信未建单的退避间隔与有效查询阈值。
 * 分类只在华昇纯决策中维护，不新增可配置“任意错误直接退款”表；
 * 应用层结合持久发送历史、原单／资产／预留／Case 状态作最终决策。
 *
 * <p>不变量：
 * <ul>
 *   <li>规则输出使用封闭动作：失败候选、重新授权、发送前重试、原单核实、人工保留；最终动账只在锁后生命周期执行；</li>
 *   <li>“没有业务副作用”的证明必须覆盖本单全部相关历史尝试，由操作持久 side-effect-free 标记提供，
 *       不能仅依据本次 TOKEN 未发送业务、单次 {@code wasBusinessCallStarted=false} 或日志缺失；</li>
 *   <li>同一错误在首次 PLACE 与已有订单阶段意义不同，端点是分类输入的一部分。</li>
 * </ul>
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#4-错误分类规则、#45-有界预算与任务持久化。
 */
public final class HuashengErrorClassification {

    /** 首次 PLACE 的明确创建拒绝候选码（第 4.2 节封闭清单）；单凭这些码不立即释放，进入第 5.4 节退避查单分支。 */
    public static final Set<Integer> PLACE_CREATION_REJECTION_CODES = Set.of(
            7002, 7003, 7005, 7006, 7007, 7008, 7010, 7011, 7018, 7022
    );

    /** 可信未建单收敛要求的有效 7014 查询次数（用户 2026-10-13 确认）。 */
    public static final int REQUIRED_VALID_NOT_CREATED_QUERIES = 4;

    /** 发送前授权／准备重试的总认领上限（第 4.5 节）。 */
    public static final int MAX_PRE_SEND_CLAIMS = 3;

    private static final Set<Integer> TRANSIENT_BEFORE_SEND_CODES = Set.of(
            4001, 50, 7001, 7004, 7009, 7013, 7017, 7024
    );

    private static final Set<Integer> ORDER_VERIFICATION_CODES = Set.of(
            7012, 7014, 7015, 7016, 7019, 7020, 7021, 7023, 7101, 7203, 7204, 7302
    );

    private static final Set<Integer> AUTHORIZATION_REJECTION_CODES = Set.of(4002, 4003);

    private HuashengErrorClassification() {
    }

    /** 是否为首次 PLACE 的明确创建拒绝候选码；CONFIRM/TAKE 等已有订单阶段返回同一码不适用该分支。 */
    public static boolean isPlaceCreationRejection(int rawCode) {
        return PLACE_CREATION_REJECTION_CODES.contains(rawCode);
    }

    /**
     * 是否为订单创建前的明确永久拒绝（第 4.2 节 1/2 类），可结合无副作用证明直接失败释放：
     * 51 参数校验失败、66 前置安全配置拒绝、TOKEN 端点 4002/4003 账号或绑定拒绝。
     */
    public static boolean isDefinitivePreCreationRejection(int rawCode, HuashengSecurityReason securityReason) {
        if (rawCode == 51) {
            return true;
        }
        if (rawCode == 66) {
            return securityReason != null && securityReason.isPreSecurityRejection();
        }
        return AUTHORIZATION_REJECTION_CODES.contains(rawCode);
    }

    /** 是否为 TOKEN 端点的授权账号/绑定拒绝（4002/4003）。 */
    public static boolean isAuthorizationRejection(int rawCode) {
        return AUTHORIZATION_REJECTION_CODES.contains(rawCode);
    }

    /** 是否为暂时故障（第 4.4 节）：发送前授权／只读查询可退避重试；已发送的写操作优先核原单。 */
    public static boolean isTransientBeforeSend(int rawCode) {
        return TRANSIENT_BEFORE_SEND_CODES.contains(rawCode);
    }

    /**
     * 是否必须原单核实（第 4.4 节）：保持 billno/orderSn，不创建新单。
     * 未知码、HTTP／网络／解析异常同样保守原单核实，不从此方法得到例外。
     */
    public static boolean requiresOrderVerification(int rawCode) {
        return ORDER_VERIFICATION_CODES.contains(rawCode);
    }

    /**
     * 未建单候选分支的退避间隔（第 5.4 节，用户已确认）：
     * 首次创建拒绝后第 1 次查询等待 5 秒；后续依次 15／30／60 秒，再封顶 60 秒。
     *
     * @param queryResultsSoFar 已终止的候选查询次数（含异常；不等于有效7014次数，设计 §7）
     * @return 下一次查询相对上次终止结果的等待秒数
     */
    public static int notCreatedBackoffSeconds(int queryResultsSoFar) {
        return switch (queryResultsSoFar) {
            case 0 -> 5;
            case 1 -> 15;
            case 2 -> 30;
            default -> 60;
        };
    }

    /**
     * 发送前授权／准备重试的认领延迟（第 4.5 节）：最多 3 次总认领，后两次延迟 5 秒、15 秒。
     *
     * @param claimCountSoFar 已发生的总认领次数（本次重试之前）
     * @return 下一次重试的延迟秒数
     */
    public static int preSendRetryDelaySeconds(int claimCountSoFar) {
        return switch (claimCountSoFar) {
            case 0, 1 -> 5;
            default -> 15;
        };
    }
}
