package com.aifuli.module.supplychain.infrastructure.identity;

import java.util.Set;

/**
 * 对外业务编号前缀白名单，与 {@link BusinessNoGenerator} 同源。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class BusinessNoPrefixes {

    private BusinessNoPrefixes() {
    }

    public static final String LST = "LST_";
    public static final String EVT = "EVT_";
    public static final String SC = "SC_";
    public static final String FR = "FR_";
    public static final String BO = "BO_";
    public static final String FUN = "FUN_";
    public static final String RF = "RF_";
    public static final String TMR = "TMR_";
    public static final String RCP = "RCP_";
    public static final String OP = "OP_";
    public static final String FA = "FA_";
    public static final String CASE = "CASE_";
    public static final String CRD = "CRD_";
    public static final String CON = "CON_";
    public static final String CPR = "CPR_";
    public static final String INV = "INV_";
    public static final String OFR = "OFR_";
    public static final String ACC = "ACC_";
    public static final String LED = "LED_";
    public static final String REQ = "REQ_";
    public static final String RR = "RR_";
    public static final String BILL = "BILL_";
    /** O2O 渠道用户绑定稳定业务编号。 */
    public static final String OUB = "OUB_";
    /** O2O 渠道订单详情稳定业务编号。 */
    public static final String O2D = "O2D_";
    /** O2O 退款 Case 稳定业务编号。 */
    public static final String ORC = "ORC_";
    /** O2O 外部操作稳定业务编号。 */
    public static final String O2OP = "O2OP_";
    /** 到店购渠道用户绑定稳定业务编号。 */
    public static final String IUB = "IUB_";
    /** 到店购入口凭证稳定业务编号。 */
    public static final String IEC = "IEC_";
    /** 到店购渠道订单头稳定业务编号。 */
    public static final String ISC = "ISC_";
    /** 到店购支付流水稳定业务编号。 */
    public static final String IPF = "IPF_";
    /** 到店购退款 Case 稳定业务编号。 */
    public static final String IRC = "IRC_";
    /** 到店购外部操作稳定业务编号。 */
    public static final String IOP = "IOP_";
    /** 到店购固定商户供应商订单稳定业务编号。 */
    public static final String ISO = "ISO_";
    /** 管理端自动生成的供应商永久业务编码。 */
    public static final String SPL = "SPL_";
    /** 供应商结算账户稳定业务编号。 */
    public static final String SSA = "SSA_";
    /** 供应商结算账户流水稳定业务编号。 */
    public static final String SSL = "SSL_";
    /**
     * 管理端创建的分销商永久业务编码。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md}。
     */
    public static final String BYR = "BYR_";
    /** 分销商品稳定业务编码。 */
    public static final String BPD = "BPD_";
    /** 采购项目稳定业务编码。 */
    public static final String PRJ = "PRJ_";
    /** 项目商品稳定业务编码。 */
    public static final String PLT = "PLT_";
    /** 平台 SPU 永久业务编码。 */
    public static final String SPU = "SPU_";
    /** 平台 SKU 永久业务编码。 */
    public static final String SKU = "SKU_";
    /** 平台品牌永久业务编码。 */
    public static final String BRD = "BRD_";
    /** 平台商品类目永久业务编码。 */
    public static final String CAT = "CAT_";
    /** 平台商品类型永久业务编码。 */
    public static final String PT = "PT_";
    /** 敏感数据访问审计稳定业务编号。 */
    public static final String AUD = "AUD_";
    /** 供应商调用审计稳定业务编号。 */
    public static final String CL = "CL_";
    /** 供应商原始状态观察稳定业务编号。 */
    public static final String OBS = "OBS_";

    public static final Set<String> ALL = Set.of(
            LST, EVT, SC, FR, BO, FUN, RF, TMR,
            RCP, OP, FA, CASE, CRD, CON, CPR, INV, OFR,
            ACC, LED, REQ, RR, BILL, OUB, O2D, ORC, O2OP,
            IUB, IEC, ISC, IPF, IRC, IOP, ISO, SPL, SSA, SSL,
            BYR, BPD, PRJ, PLT, SPU, SKU, BRD, CAT, PT, AUD, CL, OBS);
}
