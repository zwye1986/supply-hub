package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConfigPublishRecordDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConfigPublishRecordMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;

/** 将事务外验证结论以短事务、乐观锁方式落库。 */
@Service
@RequiredArgsConstructor
public class ConnectorValidationRecordService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplierConnectorVersionMapper versionMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final SupplierConfigPublishRecordMapper publishRecordMapper;
    private final BusinessNoGenerator businessNoGenerator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void record(RecordValidationCommand command) {
        SupplierConnectorVersionDO version = versionMapper.selectByIdForUpdate(command.connectorVersionId());
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()
                || version.getVersion() != command.expectedVersion()
                || !Arrays.equals(version.getConfigDigest(), command.configDigest())) {
            throw new IllegalStateException("Connector draft changed during validation");
        }
        if (!capabilityMapper.selectByConnectorVersionId(version.getId()).isEmpty()) {
            throw new IllegalStateException("Connector capabilities have already been recorded");
        }
        for (Capability capability : command.capabilities()) {
            capabilityMapper.insert(SupplierConnectorCapabilityDO.builder()
                    .connectorVersionId(version.getId()).capabilityCode(capability.name())
                    .enabled((short) 1).metadataVersion(1).createdAt(command.validatedAt()).build());
        }
        version.setValidationStatus((short) 10);
        version.setValidatedAt(command.validatedAt());
        version.setValidationExpiresAt(command.validationExpiresAt());
        version.setStatus(ConnectorVersionStatus.VALIDATED.getCode());
        version.setUpdatedBy(command.operatorId());
        version.setUpdatedAt(command.validatedAt());
        if (versionMapper.updateById(version) != 1) {
            throw new IllegalStateException("Connector validation lost optimistic lock");
        }
        publishRecordMapper.insert(SupplierConfigPublishRecordDO.builder()
                .recordNo(businessNoGenerator.next("CPR_")).connectorId(version.getConnectorId())
                .targetVersionId(version.getId()).actionType("VALIDATE").validationResult("PASSED")
                .operatorId(command.operatorId()).reason(command.reason())
                .configDigest(version.getConfigDigest()).traceId(command.traceId())
                .createdAt(command.validatedAt()).build());
    }

    public record RecordValidationCommand(Long connectorVersionId, long expectedVersion,
                                          byte[] configDigest, Set<Capability> capabilities,
                                          LocalDateTime validatedAt, LocalDateTime validationExpiresAt,
                                          Long operatorId, String reason, String traceId) {
        public RecordValidationCommand {
            configDigest = Arrays.copyOf(configDigest, configDigest.length);
            capabilities = Set.copyOf(capabilities);
        }
    }
}
