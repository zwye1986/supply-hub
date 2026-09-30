package com.aifuli.module.supplychain.integration.rtmart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旅通入口 URL 端口契约测试，固定最小业务参数并禁止把签名私密配置带入中台命令。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#RtmartEntryUrlPort}。</p>
 */
class RtmartEntryUrlPortContractTest {

    private static final String TOKEN_SENTINEL = "TOKEN-SECRET-SENTINEL";
    private static final String URL_SENTINEL = "https://rtmart.example/entry?token=FULL-URL-SENTINEL";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_CODE = "COMPANY";
    private static final String LONGITUDE = "LONGITUDE-SENSITIVE-SENTINEL";
    private static final String LATITUDE = "LATITUDE-SENSITIVE-SENTINEL";
    private static final String TRADE_ID = "TRADE-SENSITIVE-SENTINEL";

    @Test
    void homeCommandContainsOnlyRequiredBusinessFields() {
        RtmartEntryUrlPort.HomeUrlCommand command = new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, LONGITUDE, LATITUDE, TOKEN_SENTINEL);

        assertThat(recordFields(RtmartEntryUrlPort.HomeUrlCommand.class))
                .containsExactly("projectId", "channelCode", "companyCode", "longitude", "latitude", "token")
                .doesNotContain("appKey", "privateKey", "sign", "nonce", "timestamp");
        assertThat(command.projectId()).isEqualTo(101L);
        assertThat(command.channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(command.companyCode()).isEqualTo(COMPANY_CODE);
        assertThat(command.longitude()).isEqualTo(LONGITUDE);
        assertThat(command.latitude()).isEqualTo(LATITUDE);
        assertThat(command.token()).isEqualTo(TOKEN_SENTINEL);
        assertThat(command.toString()).doesNotContain(TOKEN_SENTINEL, LONGITUDE, LATITUDE);
    }

    @Test
    void detailCommandContainsOnlyRequiredBusinessFields() {
        RtmartEntryUrlPort.DetailUrlCommand command = new RtmartEntryUrlPort.DetailUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, TRADE_ID, TOKEN_SENTINEL);

        assertThat(recordFields(RtmartEntryUrlPort.DetailUrlCommand.class))
                .containsExactly("projectId", "channelCode", "companyCode", "tradeId", "token")
                .doesNotContain("appKey", "privateKey", "sign", "nonce", "timestamp");
        assertThat(command.tradeId()).isEqualTo(TRADE_ID);
        assertThat(command.token()).isEqualTo(TOKEN_SENTINEL);
        assertThat(command.toString()).doesNotContain(TOKEN_SENTINEL, TRADE_ID);
    }

    @Test
    void commandsRejectNullProjectId() {
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                null, CHANNEL_CODE, COMPANY_CODE, LONGITUDE, LATITUDE, TOKEN_SENTINEL))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.DetailUrlCommand(
                null, CHANNEL_CODE, COMPANY_CODE, TRADE_ID, TOKEN_SENTINEL))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void homeCommandRejectsEveryBlankStringField(String blank) {
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                101L, blank, COMPANY_CODE, LONGITUDE, LATITUDE, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channelCode");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, blank, LONGITUDE, LATITUDE, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("companyCode");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, blank, LATITUDE, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longitude");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, LONGITUDE, blank, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latitude");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, LONGITUDE, LATITUDE, blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void detailCommandRejectsEveryBlankStringField(String blank) {
        assertThatThrownBy(() -> new RtmartEntryUrlPort.DetailUrlCommand(
                101L, blank, COMPANY_CODE, TRADE_ID, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channelCode");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.DetailUrlCommand(
                101L, CHANNEL_CODE, blank, TRADE_ID, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("companyCode");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.DetailUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, blank, TOKEN_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tradeId");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.DetailUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, TRADE_ID, blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token");
    }

    @Test
    void resultRequiresEntryUrlAndRedactsItFromDiagnostics() {
        RtmartEntryUrlPort.EntryUrlResult result = new RtmartEntryUrlPort.EntryUrlResult(URL_SENTINEL);

        assertThat(result.entryUrl()).isEqualTo(URL_SENTINEL);
        assertThat(result.toString()).doesNotContain(URL_SENTINEL, "FULL-URL-SENTINEL");
        assertThatThrownBy(() -> new RtmartEntryUrlPort.EntryUrlResult(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entryUrl");
    }

    private static String[] recordFields(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .toArray(String[]::new);
    }
}
