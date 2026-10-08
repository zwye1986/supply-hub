package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.api.movie.MovieSupplierAccountDTO;
import com.aifuli.module.supplychain.api.movie.MovieSupplierChannel;
import com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;

/**
 * 从中台现有配置及加密凭据表读取电影账号，固定当前已发布版本，不访问电影库。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#电影密钥读取局部调整2026-10-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSupplierConfigService {
    /** 连接器DDL定义的启用状态。 */
    private static final short CONNECTOR_ENABLED = 10;
    /** 标准履约供应商类型，不允许将渠道外部商户当作电影协议账号。 */
    private static final String STANDARD_SUPPLIER = "STANDARD_SUPPLIER";
    /** 当前已入库电影协议与配置Schema的版本。 */
    private static final int CONFIG_SCHEMA_VERSION = 1;
    private static final String PROTOCOL_VERSION = "v1";
    /** 兼容已入库JSON的封闭字段集，不允许在JSON内扩散密钥。 */
    private static final Set<String> CONFIG_FIELDS = Set.of("appId", "channel", "accountRef",
            "minRequestIntervalMs", "deliverMinutes");
    /** JC已确认的订单类型。 */
    private static final Set<String> JC_OPTIONS = Set.of("8", "15", "30");
    /** 账号配置及凭据数据都由供应链数据源和事务管理器承载。 */
    private final SupplierMapper supplierMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    /** 只读既有凭据集；不改公共录入、校验或发布流程。 */
    private final CredentialSetMapper credentialSetMapper;
    private final CredentialItemMapper credentialItemMapper;
    private final CredentialSetResolver credentialSetResolver;
    /** 电影签名密钥的唯一项键及数据库加密类型。 */
    private static final String APP_SECRET = "appSecret";
    private static final String ENVELOPE = "ENVELOPE";
    /** 使用严格JSON读取，错误不回显原配置文本。 */
    private final ObjectMapper objectMapper;

    /**
     * 每次请求读取一个数据库快照；某个所选渠道无有效账号时明确失败，防止任务假成功。
     * 高频入口仅记录渠道及数量，不记录应用ID、凭证或配置对象。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public List<MovieSupplierAccountDTO> accounts(List<MovieSupplierChannel> channels) {
        if (channels == null || channels.isEmpty() || channels.size() > MovieSupplierChannel.values().length
                || channels.stream().anyMatch(Objects::isNull) || new HashSet<>(channels).size() != channels.size()) {
            throw new IllegalArgumentException("电影同步渠道不能为空、重复或超出支持范围");
        }
        log.debug("读取电影供应商账号 channels={}", channels);
        List<SupplierConnectorDO> connectors = connectorMapper.selectList(
                new LambdaQueryWrapper<SupplierConnectorDO>()
                        .in(SupplierConnectorDO::getAdapterType, channels.stream().map(MovieSupplierChannel::adapterType).toList())
                        .eq(SupplierConnectorDO::getStatus, CONNECTOR_ENABLED)
                        .orderByAsc(SupplierConnectorDO::getId));
        List<MovieSupplierAccountDTO> result = new ArrayList<>();
        Set<String> accountRefs = new HashSet<>();
        Set<MovieSupplierChannel> found = EnumSet.noneOf(MovieSupplierChannel.class);
        for (SupplierConnectorDO connector : connectors) {
            SupplierDO supplier = supplierMapper.selectById(connector.getSupplierId());
            if (supplier == null || !Objects.equals(supplier.getStatus(), SupplierStatus.ACTIVE.getCode())
                    || !STANDARD_SUPPLIER.equals(supplier.getSupplierType())) {
                continue;
            }
            MovieSupplierChannel channel = channels.stream()
                    .filter(value -> value.adapterType().equals(connector.getAdapterType())).findFirst().orElseThrow();
            if (connector.getCurrentVersionId() == null) {
                throw invalid(connector, "尚未指定current_version_id");
            }
            SupplierConnectorVersionDO version = versionMapper.selectById(connector.getCurrentVersionId());
            if (version == null || !Objects.equals(version.getConnectorId(), connector.getId())
                    || !Objects.equals(version.getStatus(), ConnectorVersionStatus.PUBLISHED.getCode())) {
                throw invalid(connector, "当前配置必须属于本账号且status=10");
            }
            MovieSupplierAccountDTO account = parseAccount(connector, version, channel);
            if (!accountRefs.add(account.getAccountRef())) {
                throw invalid(connector, "accountRef重复，会造成排期空间冲突");
            }
            verifyCredentialBinding(connector, version);
            Map<String, char[]> secrets = credentialSetResolver.resolveSet(version.getCredentialSetId());
            try {
                char[] secret = secrets.get(APP_SECRET);
                if (!secrets.keySet().equals(Set.of(APP_SECRET)) || secret == null
                        || secret.length == 0 || new String(secret).isBlank()) {
                    throw invalid(connector, "供应商密钥未配置或凭据项不完整");
                }
                account.setAppSecret(new String(secret));
            } finally {
                secrets.values().forEach(chars -> { if (chars != null) Arrays.fill(chars, '\0'); });
            }
            result.add(account);
            found.add(channel);
        }
        if (!found.containsAll(channels)) {
            Set<MovieSupplierChannel> missing = EnumSet.copyOf(channels);
            missing.removeAll(found);
            throw new IllegalStateException("所选电影渠道没有启用的供应商账号：" + missing);
        }
        log.debug("电影供应商账号读取完成 channels={} accounts={}", channels, result.size());
        return List.copyOf(result);
    }

    /**
     * 在同一只读事务内校验加密凭据归属、启用状态及已发布版本冻结的身份。
     * 只验证当前账号读取所需的 appSecret，不注册适配器或修改公共校验器。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#电影密钥读取局部调整2026-10-08
     */
    private void verifyCredentialBinding(SupplierConnectorDO connector, SupplierConnectorVersionDO version) {
        if (version.getCredentialSlotId() != null || version.getCredentialSetId() == null) {
            throw invalid(connector, "电影密钥必须绑定加密凭据集");
        }
        CredentialSetDO set = credentialSetMapper.selectById(version.getCredentialSetId());
        if (set == null || !Objects.equals(set.getSupplierId(), connector.getSupplierId())
                || !Objects.equals(set.getStatus(), CredentialStatus.ACTIVE.getCode())) {
            throw invalid(connector, "凭据集未启用或不属于当前供应商");
        }
        List<CredentialItemDO> items = credentialItemMapper.selectListBySetId(set.getId()).stream()
                .filter(item -> Objects.equals(item.getStatus(), CredentialStatus.ACTIVE.getCode())).toList();
        if (items.size() != 1 || !APP_SECRET.equals(items.getFirst().getItemKey())) {
            throw invalid(connector, "启用凭据项必须恰为appSecret");
        }
        CredentialItemDO item = items.getFirst();
        if (!ENVELOPE.equals(item.getProviderType()) || item.getId() == null
                || !Objects.equals(item.getCredentialSetId(), set.getId())
                || item.getActiveSecretVersion() == null || item.getActiveSecretVersion() < 1) {
            throw invalid(connector, "电影凭据必须是具有有效身份和版本的ENVELOPE项");
        }
        // 与既有发布快照格式一致，仅比较身份版本，不读取或输出秘密引用。
        var expected = objectMapper.createArrayNode();
        expected.addObject().put("itemId", item.getId()).put("itemKey", APP_SECRET)
                .put("providerType", ENVELOPE).put("activeSecretVersion", item.getActiveSecretVersion());
        if (version.getCredentialItemSnapshot() == null) {
            throw invalid(connector, "配置版本缺少凭据身份快照");
        }
        try {
            JsonNode frozen = objectMapper.reader()
                    .without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature())
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(version.getCredentialItemSnapshot());
            // 统一 JSON 数字节点类型，避免 Long 主键与反序列化的 Int 节点产生假不等。
            if (!objectMapper.readTree(expected.toString()).equals(frozen)) {
                throw invalid(connector, "凭据身份或密钥版本与发布快照不一致");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("电影凭据身份快照格式无效，connectorId=" + connector.getId(), failure);
        }
    }

    /** 校验现有JSON契约与DTO映射；只输出内部连接器ID作为错误上下文。 */
    private MovieSupplierAccountDTO parseAccount(SupplierConnectorDO connector, SupplierConnectorVersionDO version,
                                                 MovieSupplierChannel channel) {
        final JsonNode config;
        try {
            config = objectMapper.reader().without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature())
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(version.getAdapterConfigJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("电影配置JSON无效，connectorId=" + connector.getId(), exception);
        }
        if (config == null || !config.isObject()) {
            throw invalid(connector, "adapter_config_json必须是对象");
        }
        config.fieldNames().forEachRemaining(field -> {
            if (!CONFIG_FIELDS.contains(field)) {
                throw invalid(connector, "配置包含未支持字段");
            }
        });
        String accountRef = requiredText(config, "accountRef");
        if (!accountRef.matches("[A-Za-z0-9_-]{1,64}") || !accountRef.equals(connector.getConnectorCode())
                || !channel.name().equals(requiredText(config, "channel"))) {
            throw invalid(connector, "账号引用或渠道与连接器不一致");
        }
        String appId = requiredText(config, "appId");
        if (appId.length() > 128 || appId.chars().anyMatch(Character::isISOControl)) {
            throw invalid(connector, "appId长度或字符不合法");
        }
        validateUrl(version.getBaseUrl());
        if (version.getVersionNo() == null || version.getVersionNo() < 1
                || !Objects.equals(version.getAdapterConfigSchemaVersion(), CONFIG_SCHEMA_VERSION)
                || !PROTOCOL_VERSION.equals(version.getProtocolVersion())
                || !positiveWithin(version.getConnectTimeoutMs(), 30000)
                || !positiveWithin(version.getReadTimeoutMs(), 120000)) {
            throw invalid(connector, "配置版本或超时范围不合法");
        }
        JsonNode interval = config.get("minRequestIntervalMs");
        if (interval == null || !interval.isIntegralNumber() || !interval.canConvertToInt()
                || interval.intValue() < 0 || interval.intValue() > 60000) {
            throw invalid(connector, "minRequestIntervalMs必须介于0和60000");
        }
        String delivery = null;
        if (channel == MovieSupplierChannel.JC) {
            delivery = requiredText(config, "deliverMinutes");
            List<String> options = Arrays.asList(delivery.split(",", -1));
            if (!JC_OPTIONS.containsAll(options) || new HashSet<>(options).size() != options.size()) {
                throw invalid(connector, "JC订单类型不合法或重复");
            }
        } else if (config.has("deliverMinutes")) {
            throw invalid(connector, "非JC渠道不能配置JC订单类型");
        }
        MovieSupplierAccountDTO account = new MovieSupplierAccountDTO();
        account.setAccountRef(accountRef);
        account.setChannel(channel);
        account.setConfigVersion("v" + version.getVersionNo());
        account.setBaseUrl(version.getBaseUrl());
        account.setAppId(appId);
        account.setConnectTimeoutMs(version.getConnectTimeoutMs());
        account.setReadTimeoutMs(version.getReadTimeoutMs());
        account.setMinRequestIntervalMs(interval.intValue());
        account.setDeliverMinutes(delivery);
        return account;
    }

    /** 允许已确认JC测试HTTP地址，保留TLS校验；禁止URL内夹带凭据或查询参数。 */
    private static void validateUrl(String value) {
        final URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("电影供应商地址格式无效", exception);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("电影供应商地址必须是无凭证和查询参数的HTTP/HTTPS地址");
        }
    }

    /** 严格读取字符串，避免将数字应用ID隐式截断或把缺字段变成空串。 */
    private static String requiredText(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("电影配置缺少非空字符串字段：" + field);
        }
        return value.textValue();
    }

    /** 请求超时必须正数且不超过平台允许上限。 */
    private static boolean positiveWithin(Integer value, int maximum) {
        return value != null && value > 0 && value <= maximum;
    }

    /** 异常信息不包含账号JSON或秘密材料。 */
    private static IllegalStateException invalid(SupplierConnectorDO connector, String reason) {
        return new IllegalStateException("电影连接器配置错误，connectorId=" + connector.getId() + "：" + reason);
    }
}
