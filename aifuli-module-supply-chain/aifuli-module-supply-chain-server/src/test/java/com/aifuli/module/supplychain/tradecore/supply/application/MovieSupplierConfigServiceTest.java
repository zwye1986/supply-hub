package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.api.movie.MovieSupplierChannel;
import com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 加密凭据集身份与当前版本边界测试；数据库真实查询另由IntegrationTest覆盖。 */
class MovieSupplierConfigServiceTest {
    /** 测试数据仅使用虚构应用ID和密钥。 */
    private final SupplierMapper suppliers = mock(SupplierMapper.class);
    private final SupplierConnectorMapper connectors = mock(SupplierConnectorMapper.class);
    private final SupplierConnectorVersionMapper versions = mock(SupplierConnectorVersionMapper.class);
    private final CredentialSetMapper sets = mock(CredentialSetMapper.class);
    private final CredentialItemMapper items = mock(CredentialItemMapper.class);
    private final CredentialSetResolver credentials = mock(CredentialSetResolver.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MovieSupplierConfigService service = new MovieSupplierConfigService(
            suppliers, connectors, versions, sets, items, credentials, json);
    private SupplierConnectorDO connector;
    private SupplierConnectorVersionDO version;
    private CredentialSetDO set;
    private CredentialItemDO item;

    /** 模拟用户已入库JSON，使用只含一个渠道的当前版本。 */
    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierConnectorDO.class);
        connector = SupplierConnectorDO.builder().id(2L).supplierId(1L).connectorCode("jc-default")
                .adapterType("MOVIE_JC_V1").currentVersionId(3L).status((short) 10).build();
        version = SupplierConnectorVersionDO.builder().id(3L).connectorId(2L).versionNo(1)
                .status((short) 10).credentialSetId(4L)
                .credentialItemSnapshot("[{\"itemId\":5,\"itemKey\":\"appSecret\",\"providerType\":\"ENVELOPE\",\"activeSecretVersion\":1}]").baseUrl("http://supplier.example/api")
                .protocolVersion("v1").adapterConfigSchemaVersion(1).connectTimeoutMs(5000).readTimeoutMs(30000)
                .adapterConfigJson("{\"appId\":\"test-app\",\"channel\":\"JC\",\"accountRef\":\"jc-default\","
                        + "\"deliverMinutes\":\"8,15,30\",\"minRequestIntervalMs\":1100}").build();
        set = CredentialSetDO.builder().id(4L).supplierId(1L).status((short) 10).build();
        item = CredentialItemDO.builder().id(5L).credentialSetId(4L).itemKey("appSecret")
                .providerType("ENVELOPE").activeSecretVersion(1).status((short) 10).build();
        when(connectors.selectList(any(Wrapper.class))).thenReturn(List.of(connector));
        when(suppliers.selectById(1L)).thenReturn(SupplierDO.builder().id(1L).status((short) 10)
                .supplierType("STANDARD_SUPPLIER").build());
        when(versions.selectById(3L)).thenReturn(version);
        when(sets.selectById(4L)).thenReturn(set);
        when(items.selectListBySetId(4L)).thenReturn(List.of(item));
        when(credentials.resolveSet(4L)).thenAnswer(ignored -> Map.of("appSecret", "test-secret".toCharArray()));
    }

    @Test
    void mapsExistingJsonAndClearsProviderBuffer() {
        char[] buffer = "test-secret".toCharArray();
        when(credentials.resolveSet(4L)).thenReturn(Map.of("appSecret", buffer));
        var result = service.accounts(List.of(MovieSupplierChannel.JC));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getAppId()).isEqualTo("test-app");
        assertThat(result.getFirst().getAppSecret()).isEqualTo("test-secret");
        assertThat(result.getFirst().getConfigVersion()).isEqualTo("v1");
        assertThat(result.getFirst().getAccountRef()).isEqualTo("jc-default");
        assertThat(result.getFirst().getMinRequestIntervalMs()).isEqualTo(1100);
        assertThat(buffer).containsOnly('\0');
        assertThat(result.getFirst().toString()).doesNotContain("test-secret", "test-app");
    }

    @Test
    void rejectsMissingCurrentVersionAndDraftInsteadOfFallingBackToLatest() {
        connector.setCurrentVersionId(null);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("current_version_id");
        connector.setCurrentVersionId(3L);
        version.setStatus((short) 0);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("status=10");
        verifyNoInteractions(credentials, sets);
    }

    @Test
    void rejectsOtherConnectorsVersionBeforeReadingSecrets() {
        version.setConnectorId(999L);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("属于本账号");
        verifyNoInteractions(credentials, sets);
    }

    @Test
    void rejectsMisboundChannelAndAccountRef() {
        version.setAdapterConfigJson(version.getAdapterConfigJson().replace("\"JC\"", "\"JY\""));
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("不一致");
        verifyNoInteractions(credentials);
    }

    @Test
    void rejectsFractionalIntervalAndUnknownSecretFields() {
        String original = version.getAdapterConfigJson();
        version.setAdapterConfigJson(original.replace("1100", "1.5"));
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("minRequestIntervalMs");
        version.setAdapterConfigJson(original.replace("{", "{\"appSecret\":\"must-not-be-in-json\","));
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC)))
                .hasMessageContaining("未支持字段").hasMessageNotContaining("must-not-be-in-json");
        verifyNoInteractions(credentials);
    }

    @Test
    void pausedSetAndUnresolvableCredentialFailTheWholeRequest() {
        set.setStatus((short) 20);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("凭据集未启用");
        set.setStatus((short) 10);
        when(credentials.resolveSet(4L)).thenThrow(new IllegalStateException("test credential unavailable"));
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("unavailable");
    }

    @Test
    void selectedChannelWithoutAccountIsFailureRatherThanSuccessfulEmptySupply() {
        when(connectors.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JY))).hasMessageContaining("JY");
        verifyNoInteractions(credentials, versions, sets);
    }

    @Test
    void rejectsDuplicateNullAndEmptyChannelSelections() {
        assertThatThrownBy(() -> service.accounts(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accounts(Arrays.asList(MovieSupplierChannel.JC, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC, MovieSupplierChannel.JC)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    /** 电影拒绝槽、ENV、跨供应商集、项键或身份版本变化，均在解密之前失败。 */
    @Test
    void rejectsInvalidBindingsBeforeResolvingSecrets() {
        version.setCredentialSlotId(8L);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("加密凭据集");
        version.setCredentialSlotId(null);
        set.setSupplierId(99L);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("供应商");
        set.setSupplierId(1L);
        item.setProviderType("ENV");
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("ENVELOPE");
        item.setProviderType("ENVELOPE");
        item.setActiveSecretVersion(2);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("发布快照");
        item.setActiveSecretVersion(1);
        item.setItemKey("secretKey");
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("appSecret");
        item.setItemKey("appSecret");
        item.setStatus((short) 20);
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("appSecret");
        verifyNoInteractions(credentials);
    }

    /** 解析结果不完整时也擦除全部已解析材料。 */
    @Test
    void clearsAllBuffersWhenResolvedMaterialIsRejected() {
        char[] secret = "test-secret".toCharArray();
        char[] extra = "unexpected-secret".toCharArray();
        when(credentials.resolveSet(4L)).thenReturn(Map.of("appSecret", secret, "extra", extra));
        assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("不完整");
        assertThat(secret).containsOnly('\0');
        assertThat(extra).containsOnly('\0');
    }

}
