package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.AuthBuyerProjectMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectCapabilityMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目应用凭证服务：按应用、项目、凭证、解密的固定顺序完成身份认证；操作能力另行授权。
 *
 * <p>任一步缺失、状态或窗口不符、解密失败均统一抛 {@code BUYER_AUTH_INVALID}，避免暴露
 * 内部阶段；appKey 明文不落日志、不进缓存。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@Slf4j
@Service
public class ProjectAppCredentialService {

    /** 到店购能力编码，与 016 DDL capability_code 语义一致。 */
    static final String CAPABILITY_INSTORE = "INSTORE";

    private final ProjectApplicationMapper applicationMapper;
    private final AuthBuyerProjectMapper buyerProjectMapper;
    private final ProjectAppCredentialVersionMapper credentialMapper;
    private final ProjectCapabilityMapper capabilityMapper;
    private final AppKeySecretResolver secretResolver;
    private final Supplier<LocalDateTime> currentTime;

    /** 生产入口固定使用数据库时钟；包内构造入口仅供边界单测注入固定绝对时刻。 */
    @Autowired
    public ProjectAppCredentialService(ProjectApplicationMapper applicationMapper,
                                       AuthBuyerProjectMapper buyerProjectMapper,
                                       ProjectAppCredentialVersionMapper credentialMapper,
                                       ProjectCapabilityMapper capabilityMapper,
                                       AppKeySecretResolver secretResolver,
                                       SupplyChainDatabaseClock databaseClock) {
        this(applicationMapper, buyerProjectMapper, credentialMapper, capabilityMapper,
                secretResolver, databaseClock::now);
    }

    ProjectAppCredentialService(ProjectApplicationMapper applicationMapper,
                                AuthBuyerProjectMapper buyerProjectMapper,
                                ProjectAppCredentialVersionMapper credentialMapper,
                                ProjectCapabilityMapper capabilityMapper,
                                AppKeySecretResolver secretResolver,
                                Clock clock) {
        this(applicationMapper, buyerProjectMapper, credentialMapper, capabilityMapper, secretResolver,
                () -> LocalDateTime.ofInstant(clock.instant(), SupplyChainDatabaseClock.DATABASE_ZONE));
    }

    private ProjectAppCredentialService(ProjectApplicationMapper applicationMapper,
                                        AuthBuyerProjectMapper buyerProjectMapper,
                                        ProjectAppCredentialVersionMapper credentialMapper,
                                        ProjectCapabilityMapper capabilityMapper,
                                        AppKeySecretResolver secretResolver,
                                        Supplier<LocalDateTime> currentTime) {
        this.applicationMapper = applicationMapper;
        this.buyerProjectMapper = buyerProjectMapper;
        this.credentialMapper = credentialMapper;
        this.capabilityMapper = capabilityMapper;
        this.secretResolver = secretResolver;
        this.currentTime = currentTime;
    }

    /**
     * 认证项目应用并返回本次请求使用的身份快照。
     *
     * <p>该入口属于高频安全路径，入口和拒绝阶段仅记 debug；日志不包含 appKey、masterKey
     * 或 secretRef，避免攻击流量制造日志风暴。设计见
     * {@code docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#3-a-项目应用发码}。</p>
     *
     * @param appId 项目全局唯一稳定应用标识
     * @param keyVersion 调用方携带的密钥版本号
     * @return 含项目、采购商与本次验签 appKey 的认证快照
     */
    public AuthenticatedApp authenticate(String appId, int keyVersion) {
        return authenticateWithPolicy(appId, keyVersion, ProjectAuthOperations.ProjectStatePolicy.ACTIVE_ONLY);
    }

    /**
     * 按服务端不可变注册表的操作授权项目状态；调用方只能传操作码，不能声明放宽策略。
     * 应用启用、密钥状态、有效窗口和解密链保持相同顺序，能力仍在签名成功后单独校验。
     * 详见 {@code docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界}。
     */
    public AuthenticatedApp authenticate(String appId, int keyVersion, String operationCode) {
        log.debug("[project-auth] 按操作凭证认证开始 appId={}, keyVersion={}, operationCode={}",
                appId, keyVersion, operationCode);
        ProjectAuthOperations.Operation operation = ProjectAuthOperations.findByOperationCode(operationCode)
                .orElseThrow(() -> authInvalid(appId, keyVersion, "操作未登记"));
        return authenticateWithPolicy(appId, keyVersion, operation.projectStatePolicy());
    }

    private AuthenticatedApp authenticateWithPolicy(String appId, int keyVersion,
                                                    ProjectAuthOperations.ProjectStatePolicy policy) {
        log.debug("[project-auth] 凭证链认证开始 appId={}, keyVersion={}", appId, keyVersion);
        ProjectApplicationDO application = applicationMapper.selectByAppId(appId);
        if (application == null || !ProjectAuthStatus.ENABLED.matches(application.getStatus())) {
            throw authInvalid(appId, keyVersion, "应用不存在或未启用");
        }

        AuthBuyerProjectDO project = buyerProjectMapper.selectById(application.getProjectId());
        if (project == null || !(ProjectAuthStatus.ENABLED.matches(project.getStatus())
                || (policy == ProjectAuthOperations.ProjectStatePolicy.ACTIVE_OR_SUSPENDED
                && ProjectAuthStatus.SUSPENDED.matches(project.getStatus())))) {
            throw authInvalid(appId, keyVersion, "项目不存在或未启用");
        }

        ProjectAppCredentialVersionDO credential =
                credentialMapper.selectByApplicationIdAndKeyVersion(application.getId(), keyVersion);
        if (credential == null || !ProjectAppCredentialStatus.isUsable(credential.getStatus())) {
            throw authInvalid(appId, keyVersion, "凭证不存在或状态不可用");
        }

        LocalDateTime now = currentTime.get();
        if (credential.getValidFrom() == null || credential.getValidFrom().isAfter(now)) {
            throw authInvalid(appId, keyVersion, "凭证未到生效时间");
        }
        if (credential.getValidTo() != null && !credential.getValidTo().isAfter(now)) {
            throw authInvalid(appId, keyVersion, "凭证已到失效时间");
        }

        String appKey;
        try {
            appKey = secretResolver.resolveAppKey(appId, keyVersion, credential.getSecretRef());
        } catch (IllegalArgumentException ex) {
            ServiceException authInvalid = authInvalid(appId, keyVersion, "secret_ref 解密失败");
            authInvalid.initCause(ex);
            throw authInvalid;
        }
        if (appKey == null || appKey.isBlank()) {
            throw authInvalid(appId, keyVersion, "resolver返回空appKey");
        }
        return new AuthenticatedApp(project.getId(), project.getBuyerId(), appId, keyVersion, appKey);
    }

    /**
     * 在签名通过后按服务端注册操作的能力编码授权；编码不由请求方自由指定。
     * 已开通再停用的标准品仅保留订单摘要查询，无行和未知状态仍拒绝；不缓存授权状态。
     * 设计见 {@code docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#3-http-合同}。
     */
    public void requireCapability(Long projectId, ProjectAuthOperations.Operation operation) {
        String capabilityCode = operation.requiredCapability();
        ProjectCapabilityDO capability = capabilityMapper.selectByProjectIdAndCode(projectId, capabilityCode);
        boolean historyQuery = ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(capabilityCode)
                && (ProjectAuthOperations.STANDARD_ORDER_QUERY.equals(operation.operationCode())
                || ProjectAuthOperations.STANDARD_ORDER_QUERY_V2.equals(operation.operationCode()));
        if (capability == null || !(ProjectAuthStatus.ENABLED.matches(capability.getStatus())
                || (historyQuery && ProjectAuthStatus.SUSPENDED.matches(capability.getStatus())))) {
            log.debug("[project-auth] 项目操作能力拒绝 projectId={}, capabilityCode={}", projectId, capabilityCode);
            throw exception(BUYER_AUTH_INVALID);
        }
    }

    /** 已认证应用快照：appKey accessor 仅供验签读取，禁止默认序列化或进入日志。 */
    public record AuthenticatedApp(Long projectId, Long buyerId, String appId, int keyVersion,
                                   @JsonIgnore String appKey) {

        @Override
        public String toString() {
            return "AuthenticatedApp[projectId=" + projectId + ", appId=" + appId
                    + ", keyVersion=" + keyVersion + ']';
        }
    }

    private static ServiceException authInvalid(String appId, int keyVersion, String stage) {
        log.debug("[project-auth] 凭证链拒绝 stage={}, appId={}, keyVersion={}", stage, appId, keyVersion);
        return exception(BUYER_AUTH_INVALID);
    }
}
