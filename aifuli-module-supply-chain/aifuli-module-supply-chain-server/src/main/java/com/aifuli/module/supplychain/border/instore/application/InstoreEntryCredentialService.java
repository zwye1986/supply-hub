package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver.BindingSnapshot;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreEntryCredentialDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreEntryCredentialMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialPurpose;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_EXPIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;

/**
 * 到店购首页与订单详情独立短期凭证服务。
 *
 * <p>签发按项目冻结 MEMBER_PROFILE 档案版本与 companyCode 快照；稳定会员绑定与可多次
 * 签发的一次性凭证分离；原始 token 仅在签发返回值中出现，数据库和日志不保存明文。
 * 消费使用 ACTIVE→PROCESSING→USED 三阶段 CAS：会员资料成功返回才标记 USED，可确定的
 * 查询失败可在未过期时释放回 ACTIVE；入口 URL 外呼结果未知或冻结档案退役时撤销为
 * REVOKED 终态。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InstoreEntryCredentialProperties.class)
public class InstoreEntryCredentialService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final InstoreEntryCredentialMapper credentialMapper;
    private final InstoreChannelUserBindingResolver bindingResolver;
    private final BusinessNoGenerator businessNoGenerator;
    private final InstoreEntryCredentialProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 为项目会员签发首页凭证，每次调用都生成独立 token，并冻结会员路由版本与企业组快照。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public IssuedCredential issueHome(Long buyerId, Long projectId, String buyerUserId,
                                      String channelCode, BuyerIntegrationRoute memberRoute,
                                      String companyCode) {
        return issue(buyerId, projectId, buyerUserId, channelCode, memberRoute, companyCode,
                InstoreEntryCredentialPurpose.HOME, null);
    }

    /** 为项目会员签发绑定到店购订单详情的凭证，同样冻结路由版本与企业组快照。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public IssuedCredential issueDetail(Long buyerId, Long projectId, String buyerUserId,
                                        String channelCode, BuyerIntegrationRoute memberRoute,
                                        String companyCode, Long instoreOrderId) {
        if (instoreOrderId == null) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
        return issue(buyerId, projectId, buyerUserId, channelCode, memberRoute, companyCode,
                InstoreEntryCredentialPurpose.DETAIL, instoreOrderId);
    }

    /**
     * 抢占凭证用于一次会员资料查询。
     *
     * <p>固定顺序：摘要查询 → 项目/渠道/用途校验 → 终态判断 → 过期 CAS →
     * ACTIVE→PROCESSING CAS。项目/渠道不匹配与用途脏值发生在任何 CAS 之前，
     * 不改变凭证状态。本方法故意不包裹会因 {@link ServiceException} 回滚的大事务：
     * 过期标记和抢占都是单条 CAS SQL，之后即使向调用方返回业务异常，已确认的状态也必须保留。</p>
     */
    public ClaimedCredential claimForMemberLookup(Long projectId, String channelCode,
                                                  String rawToken) {
        log.info("[INSTORE][credential] claimForMemberLookup 入口 projectId={}, channelCode={}",
                projectId, channelCode);
        if (rawToken == null || rawToken.isBlank()) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }

        InstoreEntryCredentialDO credential = credentialMapper.selectByTokenDigest(sha256(rawToken));
        if (credential == null) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
        if (!Objects.equals(projectId, credential.getProjectId())) {
            log.warn("[INSTORE][credential] 凭证项目不匹配 credentialNo={}, credentialProjectId={}, requestProjectId={}",
                    credential.getCredentialNo(), credential.getProjectId(), projectId);
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }

        BindingSnapshot binding = bindingResolver.resolveActiveById(credential.getBindingId());
        if (!Objects.equals(channelCode, binding.channelCode())) {
            log.warn("[INSTORE][credential] 凭证渠道不匹配 credentialNo={}, bindingChannelCode={}, requestChannelCode={}",
                    credential.getCredentialNo(), binding.channelCode(), channelCode);
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }

        InstoreEntryCredentialPurpose purpose = credentialPurpose(credential.getPurpose());

        InstoreEntryCredentialStatus status = credentialStatus(credential.getStatus());
        if (status == InstoreEntryCredentialStatus.USED) {
            throw exception(INSTORE_CREDENTIAL_ALREADY_USED);
        }
        if (status == InstoreEntryCredentialStatus.EXPIRED) {
            throw exception(INSTORE_CREDENTIAL_EXPIRED);
        }
        if (status == InstoreEntryCredentialStatus.REVOKED) {
            throw exception(INSTORE_CREDENTIAL_REVOKED);
        }
        if (status == InstoreEntryCredentialStatus.PROCESSING) {
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }

        LocalDateTime now = now();
        if (credential.getExpiresAt() == null || !credential.getExpiresAt().isAfter(now)) {
            credentialMapper.markExpired(credential.getId(), credential.getVersion(), now);
            throw exception(INSTORE_CREDENTIAL_EXPIRED);
        }

        int claimed = credentialMapper.claimProcessing(
                credential.getId(), credential.getVersion(), now);
        if (claimed != 1) {
            InstoreEntryCredentialDO latest = credentialMapper.selectById(credential.getId());
            throw currentStateException(latest, now);
        }

        long processingVersion = credential.getVersion() + 1;
        log.info("[INSTORE][credential] 凭证抢占成功 credentialNo={}, credentialId={}, projectId={}, processingVersion={}",
                credential.getCredentialNo(), credential.getId(), credential.getProjectId(), processingVersion);
        return new ClaimedCredential(credential.getId(), processingVersion,
                credential.getProjectId(), credential.getMemberProfileVersionId(),
                credential.getCompanyCode(), binding.buyerUserId(), binding.channelCode(),
                purpose, credential.getInstoreOrderId());
    }

    /** 会员资料已成功返回时以补偿事务将独占凭证标记为 USED。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW)
    public void markLookupSucceeded(Long credentialId, long processingVersion) {
        log.info("[INSTORE][credential] markLookupSucceeded 入口 credentialId={}, processingVersion={}",
                credentialId, processingVersion);
        if (credentialMapper.markUsed(credentialId, processingVersion, now()) != 1) {
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }
    }

    /**
     * 可确定的会员资料查询失败后以补偿事务释放凭证；如果此时已过期，则只能转为 EXPIRED。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW)
    public void releaseAfterLookupFailure(Long credentialId, long processingVersion) {
        log.info("[INSTORE][credential] releaseAfterLookupFailure 入口 credentialId={}, processingVersion={}",
                credentialId, processingVersion);
        InstoreEntryCredentialDO current = credentialMapper.selectById(credentialId);
        if (current == null) {
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }
        LocalDateTime now = now();
        int changed;
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            changed = credentialMapper.markExpired(credentialId, processingVersion, now);
        } else {
            changed = credentialMapper.releaseProcessing(credentialId, processingVersion, now);
        }
        if (changed != 1) {
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }
    }

    /**
     * 入口 URL 外呼结果未知或冻结档案退役时，以独立补偿事务撤销凭证为 REVOKED 终态。
     *
     * <p>URL 调用失败统一视为结果未知：撤销当前 token，调用方重试时签发新 token；
     * 获取 URL 成功但调用方未收到响应时不会撤销，旧 token 保持 ACTIVE。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW)
    public void revoke(Long credentialId, long expectedVersion, InstoreCredentialRevokeReason reason) {
        log.info("[INSTORE][credential] revoke 入口 credentialId={}, expectedVersion={}, reason={}",
                credentialId, expectedVersion, reason);
        if (credentialMapper.revoke(credentialId, expectedVersion, reason, now()) != 1) {
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }
    }

    private IssuedCredential issue(Long buyerId, Long projectId, String buyerUserId,
                                   String channelCode, BuyerIntegrationRoute memberRoute,
                                   String companyCode,
                                   InstoreEntryCredentialPurpose purpose, Long instoreOrderId) {
        log.info("[INSTORE][credential] issue 入口 buyerId={}, projectId={}, channelCode={}, purpose={}, instoreOrderId={}, profileVersionId={}",
                buyerId, projectId, channelCode, purpose, instoreOrderId, memberRoute.profileVersionId());
        if (companyCode == null || companyCode.isBlank()) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
        BindingSnapshot binding = bindingResolver.resolveOrCreateStableBinding(
                buyerId, projectId, buyerUserId, channelCode, memberRoute);
        String rawToken = generateRawToken();
        LocalDateTime now = now();
        LocalDateTime expiresAt = now.plus(properties.getTtl());
        InstoreEntryCredentialDO credential = InstoreEntryCredentialDO.builder()
                .credentialNo(businessNoGenerator.next(BusinessNoPrefixes.IEC))
                .bindingId(binding.bindingId())
                .projectId(projectId)
                .memberProfileVersionId(memberRoute.profileVersionId())
                .companyCode(companyCode)
                .tokenDigest(sha256(rawToken))
                .purpose(purpose.name())
                .instoreOrderId(instoreOrderId)
                .status(InstoreEntryCredentialStatus.ACTIVE.getCode())
                .expiresAt(expiresAt)
                .processingStartedAt(null)
                .usedAt(null)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        credentialMapper.insert(credential);
        log.info("[INSTORE][credential] 凭证签发完成 credentialNo={}, bindingId={}, projectId={}, purpose={}, expiresAt={}",
                credential.getCredentialNo(), credential.getBindingId(), projectId, purpose, expiresAt);
        return new IssuedCredential(credential.getId(), credential.getCredentialNo(),
                credential.getVersion(), rawToken, purpose, instoreOrderId, expiresAt);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "tk-" + HexFormat.of().formatHex(bytes);
    }

    private static byte[] sha256(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /** HOME 与 DETAIL 都是合法会员查询用途；用途脏值在抢占前失败。 */
    private static InstoreEntryCredentialPurpose credentialPurpose(String purpose) {
        if (purpose == null) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
        try {
            return InstoreEntryCredentialPurpose.valueOf(purpose);
        } catch (IllegalArgumentException invalidPurpose) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
    }

    private static InstoreEntryCredentialStatus credentialStatus(Short code) {
        if (code == null) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
        try {
            return InstoreEntryCredentialStatus.fromCode(code);
        } catch (IllegalArgumentException invalidStatus) {
            throw exception(INSTORE_CREDENTIAL_INVALID);
        }
    }

    private static ServiceException currentStateException(InstoreEntryCredentialDO current,
                                                          LocalDateTime now) {
        if (current == null) {
            return exception(INSTORE_CONCURRENT_UPDATE);
        }
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            return exception(INSTORE_CREDENTIAL_EXPIRED);
        }
        InstoreEntryCredentialStatus status = credentialStatus(current.getStatus());
        if (status == InstoreEntryCredentialStatus.USED) {
            return exception(INSTORE_CREDENTIAL_ALREADY_USED);
        }
        if (status == InstoreEntryCredentialStatus.EXPIRED) {
            return exception(INSTORE_CREDENTIAL_EXPIRED);
        }
        if (status == InstoreEntryCredentialStatus.REVOKED) {
            return exception(INSTORE_CREDENTIAL_REVOKED);
        }
        return exception(INSTORE_CONCURRENT_UPDATE);
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    /**
     * 签发结果；token 原文只能通过此结果返回一次。
     * credentialVersion 供 URL 外呼失败后按签发版本撤销。
     */
    public record IssuedCredential(Long credentialId, String credentialNo, long credentialVersion,
                                   String token, InstoreEntryCredentialPurpose purpose,
                                   Long instoreOrderId, LocalDateTime expiresAt) {
    }

    /**
     * 凭证抢占结果；携带凭证冻结的项目、会员档案版本与企业组快照，
     * 以及后续会员资料查询所需的会员身份，不要求请求方提交租户路由。
     */
    public record ClaimedCredential(Long credentialId, long processingVersion,
                                    Long projectId, Long memberProfileVersionId,
                                    String companyCode, String buyerUserId, String channelCode,
                                    InstoreEntryCredentialPurpose purpose,
                                    Long instoreOrderId) {
    }
}
