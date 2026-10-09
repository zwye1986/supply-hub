package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 创建、校验、发布及运行时共享的凭据身份守卫，不解析或返回秘密。
 * 集与槽恰一引用；集归属、ACTIVE 项键及冻结身份版本全部一致才允许执行。
 * 集模式绑定先锁集再当前读项，发布调用者遵守集→连接器→版本→项锁序。
 * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。
 */
@Component
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class ConnectorCredentialBindingValidator {
    private final CredentialSlotMapper slots;
    private final CredentialSetMapper sets;
    private final CredentialItemMapper items;
    private final ClosedJsonSchemaValidator schema;
    private final ObjectMapper json;

    /**
     * 草稿引用只接受元数据声明的一个输入。集按供应商编号定位后锁行，项以当前读构造有序身份快照，
     * 从而与追加项共享串行化边界；快照只含身份和版本，不含 secretRef 或明文。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。
     */
    public Binding bindDraft(AdapterMetadata metadata, Long supplierId, String slotNo, String setCode) {
        log.info("[bindDraft] supplierId={} adapterType={} bindingMode={} slotNo={} setCode={}",supplierId,metadata.adapterType(),metadata.credentialBindingMode(),slotNo,setCode);
        boolean slot=hasText(slotNo), set=hasText(setCode);
        if (slot==set || (metadata.credentialBindingMode()==CredentialBindingMode.SINGLE_SLOT)!=slot) {
            throw new IllegalArgumentException("Credential reference must exactly match the adapter binding mode");
        }
        if (slot) {
            CredentialSlotDO row=slots.selectByNo(slotNo);
            if (row==null || !active(row.getStatus())) throw new IllegalArgumentException("Active credential slot does not exist");
            return new Binding(row.getId(),null,null);
        }
        CredentialSetDO found=sets.selectBySupplierAndCode(supplierId,setCode);
        if (found==null || !supplierId.equals(found.getSupplierId())) throw new IllegalArgumentException("Credential set does not belong to supplier");
        CredentialSetDO locked=sets.selectByIdForUpdate(found.getId());
        requireSet(locked,supplierId);
        return new Binding(null,locked.getId(),snapshot(metadata,items.selectListBySetIdForUpdate(locked.getId())));
    }

    /**
     * 重复校验显式模式、状态、供应商归属、完整项键与冻结身份版本。
     * 发布 currentRead=true 时集和项都必须使用当前读，不能在等待集锁后复用 RR 旧快照；
     * 发布调用者已先按集→连接器→版本取锁，此处重复锁同一集后再锁项。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。
     */
    public void verify(AdapterMetadata metadata, Long supplierId, SupplierConnectorVersionDO version, boolean currentRead) {
        metadata.validateMerchantAccount(version.getMerchantAccount());
        log.debug("[verifyCredentialBinding] supplierId={} connectorVersionId={} bindingMode={} slotId={} setId={} currentRead={}",
                supplierId,version.getId(),metadata.credentialBindingMode(),version.getCredentialSlotId(),version.getCredentialSetId(),currentRead);
        boolean slot=version.getCredentialSlotId()!=null,set=version.getCredentialSetId()!=null;
        if(slot==set || (metadata.credentialBindingMode()==CredentialBindingMode.SINGLE_SLOT)!=slot) {
            throw new IllegalStateException("Credential binding mode or exclusive reference is invalid");
        }
        if(slot) {
            CredentialSlotDO row = currentRead
                    ? slots.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CredentialSlotDO>()
                            .eq(CredentialSlotDO::getId, version.getCredentialSlotId()).last("FOR UPDATE"))
                    : slots.selectById(version.getCredentialSlotId());
            if(row==null || !active(row.getStatus())) throw new IllegalStateException("Credential slot is not active");
            return;
        }
        CredentialSetDO row=currentRead?sets.selectByIdForUpdate(version.getCredentialSetId()):sets.selectById(version.getCredentialSetId());
        requireSet(row,supplierId);
        String actual=snapshot(metadata,currentRead?items.selectListBySetIdForUpdate(row.getId()):items.selectListBySetId(row.getId()));
        if(version.getCredentialItemSnapshot()==null || !parse(actual).equals(parse(version.getCredentialItemSnapshot()))) {
            throw new IllegalStateException("Credential item identity or active version changed");
        }
    }

    private void requireSet(CredentialSetDO row,Long supplierId) {
        if(row==null || !active(row.getStatus()) || !supplierId.equals(row.getSupplierId())) {
            throw new IllegalArgumentException("Active credential set does not belong to supplier");
        }
    }

    private String snapshot(AdapterMetadata metadata,List<CredentialItemDO> rows) {
        var active=rows.stream().filter(item->active(item.getStatus())).sorted(Comparator.comparing(CredentialItemDO::getItemKey)).toList();
        Set<String> keys=new HashSet<>(); active.forEach(item->keys.add(item.getItemKey()));
        if(!keys.equals(metadata.credentialKeys()) || keys.size()!=active.size()) {
            throw new IllegalArgumentException("Active credential item keys must exactly match adapter required keys");
        }
        var snapshot=json.createArrayNode();
        for(var item:active) {
            if(item.getId()==null || item.getProviderType()==null || item.getActiveSecretVersion()==null) {
                throw new IllegalArgumentException("Credential item identity is incomplete");
            }
            snapshot.addObject().put("itemId",item.getId()).put("itemKey",item.getItemKey())
                    .put("providerType",item.getProviderType()).put("activeSecretVersion",item.getActiveSecretVersion());
        }
        return snapshot.toString();
    }

    /** 只从版本快照投影非秘密身份，不读取当前项来拼替代版本；设计见华昇生产闭环§3.3。 */
    public List<com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity> frozenItems(SupplierConnectorVersionDO version) {
        if (version.getCredentialSetId() == null) return List.of();
        var node = parse(version.getCredentialItemSnapshot());
        if (node == null || !node.isArray() || node.isEmpty()) throw new IllegalStateException("冻结凭据快照不完整");
        var result = new ArrayList<com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity>();
        for (var item : node) {
            if (!item.isObject() || item.size() != 4 || !item.path("itemId").isIntegralNumber() || !item.path("itemId").canConvertToLong()
                    || !item.path("itemKey").isTextual() || !item.path("providerType").isTextual()
                    || !item.path("activeSecretVersion").isIntegralNumber() || !item.path("activeSecretVersion").canConvertToInt()) {
                throw new IllegalStateException("冻结凭据快照不完整");
            }
            result.add(new com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity(item.get("itemId").longValue(),
                    item.get("itemKey").textValue(), item.get("providerType").textValue(), item.get("activeSecretVersion").intValue()));
        }
        return List.copyOf(result);
    }

    /** 封闭 Schema 之后只投影非秘密标量；嵌套 JSON 或秘密键不能变成运行时任意参数。 */
    public Map<String,String> extraParameters(AdapterMetadata metadata,String config) {
        JsonNode node=parse(config==null?"{}":config);
        schema.validate(metadata.extraConfigSchemaResource(),node);
        metadata.validateExtraConfig(node);
        Map<String,String> result=new TreeMap<>();
        node.fields().forEachRemaining(entry->{
            String key=entry.getKey().toLowerCase(Locale.ROOT); JsonNode value=entry.getValue();
            if(key.contains("secret") || key.contains("token") || key.contains("privatekey") || key.contains("publickey")
                    || key.contains("password") || !value.isValueNode() || value.isNull()) {
                throw new IllegalArgumentException("Extra config must contain only declared non-secret scalar parameters");
            }
            result.put(entry.getKey(),value.asText());
        });
        return Collections.unmodifiableMap(result);
    }

    /** 按字段名排序但保留 JSON 标量类型，保证配置摘要不受输入字段顺序影响。 */
    public String normalizeExtra(AdapterMetadata metadata,String config) {
        extraParameters(metadata,config);
        JsonNode node=parse(config==null?"{}":config);
        var sorted=json.createObjectNode();
        Set<String> keys=new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.forEach(key->sorted.set(key,node.get(key)));
        return sorted.toString();
    }
    private JsonNode parse(String text) {
        try { return json.readTree(text); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Credential snapshot or extra config is invalid JSON",failure);
        }
    }
    private static boolean active(Short status) { return status!=null && status==CredentialStatus.ACTIVE.getCode(); }
    private static boolean hasText(String value) { return value!=null && !value.isBlank(); }
    public record Binding(Long credentialSlotId,Long credentialSetId,String itemSnapshot) {}
}
