package com.aifuli.module.supplychain.integration.rtmart;

import java.util.Objects;

/**
 * 获取旅通 HOME/DETAIL 最终入口 URL 的出站端口。
 *
 * <p>命令只携带中台已验证的业务参数和短期 token；appKey、私钥、签名、nonce 等私密配置
 * 属于后续真实 Adapter，不得进入本端口合同。token 和返回 URL 均不得通过诊断字符串泄漏。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#RtmartEntryUrlPort}。</p>
 */
public interface RtmartEntryUrlPort {

    /** 获取旅通首页入口地址。 */
    EntryUrlResult getHomeUrl(HomeUrlCommand command);

    /** 获取旅通订单详情入口地址。 */
    EntryUrlResult getDetailUrl(DetailUrlCommand command);

    /** 首页入口命令，经纬度保持渠道协议原始字符串精度。 */
    record HomeUrlCommand(Long projectId, String channelCode, String companyCode,
                          String longitude, String latitude, String token) {

        public HomeUrlCommand {
            Objects.requireNonNull(projectId, "projectId");
            requireText(channelCode, "channelCode");
            requireText(companyCode, "companyCode");
            requireText(longitude, "longitude");
            requireText(latitude, "latitude");
            requireText(token, "token");
        }

        /**
         * 仅输出项目、渠道和企业编码；短期 token 与精确经纬度均不得进入诊断字符串。
         *
         * @return 不含短期 token 与精确坐标的安全命令摘要
         */
        @Override
        public String toString() {
            return "HomeUrlCommand[projectId=" + projectId
                    + ", channelCode=" + channelCode
                    + ", companyCode=" + companyCode + ']';
        }
    }

    /** 详情入口命令，tradeId 是订单冻结的唯一支付流水标识。 */
    record DetailUrlCommand(Long projectId, String channelCode, String companyCode,
                            String tradeId, String token) {

        public DetailUrlCommand {
            Objects.requireNonNull(projectId, "projectId");
            requireText(channelCode, "channelCode");
            requireText(companyCode, "companyCode");
            requireText(tradeId, "tradeId");
            requireText(token, "token");
        }

        /**
         * 仅输出项目、渠道和企业编码；短期 token 与支付流水号均不得进入诊断字符串。
         *
         * @return 不含短期 token 与支付流水号的安全命令摘要
         */
        @Override
        public String toString() {
            return "DetailUrlCommand[projectId=" + projectId
                    + ", channelCode=" + channelCode
                    + ", companyCode=" + companyCode + ']';
        }
    }

    /** 旅通最终入口地址；完整 URL 可能携带凭证，不得进入日志。 */
    record EntryUrlResult(String entryUrl) {

        public EntryUrlResult {
            requireText(entryUrl, "entryUrl");
        }

        /** @return 不含完整入口 URL 的安全结果摘要 */
        @Override
        public String toString() {
            return "EntryUrlResult[entryUrl=<redacted>]";
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
