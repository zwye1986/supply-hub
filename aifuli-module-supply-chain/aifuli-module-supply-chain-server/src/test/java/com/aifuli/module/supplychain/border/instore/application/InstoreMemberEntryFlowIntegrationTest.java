package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.OrderDetailEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.BuyerContext;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ChannelContext;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ProjectContext;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberProfileResult;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberResolutionStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.ResolveMemberCommand;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.DetailUrlCommand;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.EntryUrlResult;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.HomeUrlCommand;
import com.aifuli.module.supplychain.interfaceapi.rpc.InstoreBuyerEntryApiImpl;
import com.aifuli.module.supplychain.interfaceapi.rpc.InstoreChannelMemberApiImpl;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 纵向测试：SaaS 商城 HOME/DETAIL 入口签发 → 旅通最终 URL 外呼 →
 * 旅通接口①持短期 token 解析会员资料的端到端链路。
 *
 * <p>测试导入真实 Mapper/Service/Facade/RPC Adapter，只以两个确定性 Test Adapter 替换
 * 出站端口：{@link RecordingEntryUrlPort} 把短期 token 嵌入最终 URL 模拟旅通返回形态，
 * {@link RecordingMemberProfilePort} 记录外呼身份三元组并返回固定资料。
 * 验证点：HOME 返回 URL 后 token 摘要可被接口①一次性消费；DETAIL 只提交 orderNo，
 * 外呼收到订单固化的 tradeId/companyCode；两个项目共享 SaaS 租户时凭证不能跨项目消费，
 * 稳定绑定也不得合并。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
class InstoreMemberEntryFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.test.jdbc-url");

    private static final String CHANNEL_RT_MART = "RT_MART";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String COMPANY_A = "COMPANY_A";
    private static final String COMPANY_B = "COMPANY_B";

    private static final long FLOW_BUYER = 2001L;
    private static final long FLOW_PROJECT = 201L;
    private static final long FLOW_PROFILE = 811L;
    private static final long FLOW_VERSION = 911L;
    private static final long FLOW_BINDING = 601L;
    private static final long FLOW_SUPPLIER_BINDING = 651L;
    private static final long FLOW_ORDER = 701L;
    private static final long FLOW_INSTORE_ORDER = 751L;
    private static final long FLOW_PAYMENT = 781L;
    private static final String FLOW_ORDER_NO = "SCO_FLOW_1";
    private static final String FLOW_TRADE_ID = "TRADE_FLOW_1";
    private static final String FLOW_BUYER_USER = "opaque-user";
    private static final String FLOW_TENANT = "tenant-flow-1";

    private static final long SHARED_BUYER = 2101L;
    private static final long SHARED_PROJECT_P1 = 211L;
    private static final long SHARED_PROJECT_P2 = 212L;
    private static final long SHARED_PROFILE_P1 = 821L;
    private static final long SHARED_PROFILE_P2 = 822L;
    private static final long SHARED_VERSION_P1 = 921L;
    private static final long SHARED_VERSION_P2 = 922L;
    private static final long SHARED_SUPPLIER_BINDING = 661L;
    private static final String SHARED_TENANT = "tenant-shared";
    private static final String SHARED_BUYER_USER = "shared-user";

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void homeAndDetailEntryUrlsResolveMemberEndToEnd() {
        try (ConfigurableApplicationContext context = applicationContext(COMPANY_A)) {
            InstoreBuyerEntryApiImpl buyerEntryApi = context.getBean(InstoreBuyerEntryApiImpl.class);
            InstoreChannelMemberApiImpl channelMemberApi =
                    context.getBean(InstoreChannelMemberApiImpl.class);
            StubContextAccessor contextAccessor = context.getBean(StubContextAccessor.class);
            RecordingEntryUrlPort entryUrlPort = context.getBean(RecordingEntryUrlPort.class);
            RecordingMemberProfilePort memberPort = context.getBean(RecordingMemberProfilePort.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertFlowFixtures(jdbc);

            // HOME：SaaS 商城以可信买家上下文换取旅通首页最终入口 URL
            contextAccessor.useBuyer(new BuyerContext(FLOW_PROJECT, FLOW_BUYER, FLOW_BUYER_USER));
            InstoreEntryUrlRespDTO home = buyerEntryApi
                    .getHomeEntry(homeRequest()).getCheckedData();
            assertThat(entryUrlPort.homeCommands()).hasSize(1);
            HomeUrlCommand homeCommand = entryUrlPort.homeCommands().getFirst();
            assertThat(homeCommand.projectId()).isEqualTo(FLOW_PROJECT);
            assertThat(homeCommand.channelCode()).isEqualTo(CHANNEL_RT_MART);
            assertThat(homeCommand.companyCode()).isEqualTo(COMPANY_A);
            assertThat(homeCommand.token()).isNotBlank();
            assertThat(home.getEntryUrl())
                    .isEqualTo("https://rtmart.test/home?token=" + homeCommand.token());

            // 接口①：旅通持 URL 中的短期 token 按冻结档案解析会员资料
            contextAccessor.useChannel(new ChannelContext(FLOW_PROJECT, CHANNEL_RT_MART));
            InstoreMemberRespDTO member = channelMemberApi
                    .resolveMember(resolveRequest(homeCommand.token())).getCheckedData();
            assertThat(member.getUserCode()).isEqualTo(FLOW_BUYER_USER);
            assertThat(member.getMobile()).isEqualTo("13800000000");
            assertThat(member.getUserName()).isEqualTo("测试会员");
            assertThat(member.getCompanyCode()).isEqualTo(COMPANY_A);
            assertThat(memberPort.commands()).hasSize(1);
            ResolveMemberCommand portCommand = memberPort.commands().getFirst();
            assertThat(portCommand.route().profileVersionId()).isEqualTo(FLOW_VERSION);
            assertThat(portCommand.route().sourceSystem()).isEqualTo(SOURCE_SYSTEM);
            assertThat(portCommand.route().externalTenantId()).isEqualTo(FLOW_TENANT);
            assertThat(portCommand.buyerUserId()).isEqualTo(FLOW_BUYER_USER);

            // HOME 凭证一次性消费后进入 USED 终态，同 token 重放被拒绝
            assertThat(credentialStatus(jdbc, "HOME", FLOW_VERSION)).isEqualTo(20);
            assertThatThrownBy(() -> channelMemberApi
                    .resolveMember(resolveRequest(homeCommand.token())))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED.getCode());

            // DETAIL：调用方只提交 orderNo，companyCode/tradeId 全部取自订单侧固化快照
            contextAccessor.useBuyer(new BuyerContext(FLOW_PROJECT, FLOW_BUYER, FLOW_BUYER_USER));
            OrderDetailEntryReqDTO detailRequest = new OrderDetailEntryReqDTO();
            detailRequest.setOrderNo(FLOW_ORDER_NO);
            InstoreEntryUrlRespDTO detail = buyerEntryApi
                    .getOrderDetailEntry(detailRequest).getCheckedData();
            assertThat(entryUrlPort.detailCommands()).hasSize(1);
            DetailUrlCommand detailCommand = entryUrlPort.detailCommands().getFirst();
            assertThat(detailCommand.projectId()).isEqualTo(FLOW_PROJECT);
            assertThat(detailCommand.companyCode()).isEqualTo(COMPANY_A);
            assertThat(detailCommand.tradeId()).isEqualTo(FLOW_TRADE_ID);
            assertThat(detail.getEntryUrl())
                    .isEqualTo("https://rtmart.test/detail?token=" + detailCommand.token());

            contextAccessor.useChannel(new ChannelContext(FLOW_PROJECT, CHANNEL_RT_MART));
            InstoreMemberRespDTO detailMember = channelMemberApi
                    .resolveMember(resolveRequest(detailCommand.token())).getCheckedData();
            assertThat(detailMember.getUserCode()).isEqualTo(FLOW_BUYER_USER);
            assertThat(detailMember.getCompanyCode()).isEqualTo(COMPANY_A);

            // HOME 与 DETAIL 凭证相互独立，消费成功后均为 USED 终态
            assertThat(credentialStatus(jdbc, "DETAIL", FLOW_VERSION)).isEqualTo(20);
        }
    }

    @Test
    void sharedSaasTenantProjectsCannotConsumeEachOthersCredentials() {
        try (ConfigurableApplicationContext context = applicationContext(COMPANY_B)) {
            InstoreBuyerEntryApiImpl buyerEntryApi = context.getBean(InstoreBuyerEntryApiImpl.class);
            InstoreChannelMemberApiImpl channelMemberApi =
                    context.getBean(InstoreChannelMemberApiImpl.class);
            StubContextAccessor contextAccessor = context.getBean(StubContextAccessor.class);
            RecordingEntryUrlPort entryUrlPort = context.getBean(RecordingEntryUrlPort.class);
            RecordingMemberProfilePort memberPort = context.getBean(RecordingMemberProfilePort.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertSharedTenantFixtures(jdbc);

            // 两个项目路由到同一 SaaS 租户，各自签发首页凭证
            contextAccessor.useBuyer(new BuyerContext(SHARED_PROJECT_P1, SHARED_BUYER, SHARED_BUYER_USER));
            buyerEntryApi.getHomeEntry(homeRequest());
            contextAccessor.useBuyer(new BuyerContext(SHARED_PROJECT_P2, SHARED_BUYER, SHARED_BUYER_USER));
            buyerEntryApi.getHomeEntry(homeRequest());
            assertThat(entryUrlPort.homeCommands()).hasSize(2);
            String tokenP1 = entryUrlPort.homeCommands().get(0).token();
            String tokenP2 = entryUrlPort.homeCommands().get(1).token();
            assertThat(tokenP1).isNotEqualTo(tokenP2);

            // 跨项目消费一律失败，且失败发生在凭证 CAS 之前，不触发会员外呼
            contextAccessor.useChannel(new ChannelContext(SHARED_PROJECT_P2, CHANNEL_RT_MART));
            assertThatThrownBy(() -> channelMemberApi.resolveMember(resolveRequest(tokenP1)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
            contextAccessor.useChannel(new ChannelContext(SHARED_PROJECT_P1, CHANNEL_RT_MART));
            assertThatThrownBy(() -> channelMemberApi.resolveMember(resolveRequest(tokenP2)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
            assertThat(memberPort.commands()).isEmpty();

            // 本项目消费成功，会员外呼使用各自项目冻结的 MEMBER_PROFILE 档案版本
            InstoreMemberRespDTO memberP1 = channelMemberApi
                    .resolveMember(resolveRequest(tokenP1)).getCheckedData();
            assertThat(memberP1.getUserCode()).isEqualTo(SHARED_BUYER_USER);
            assertThat(memberPort.commands().get(0).route().profileVersionId())
                    .isEqualTo(SHARED_VERSION_P1);
            assertThat(memberPort.commands().get(0).route().externalTenantId())
                    .isEqualTo(SHARED_TENANT);
            contextAccessor.useChannel(new ChannelContext(SHARED_PROJECT_P2, CHANNEL_RT_MART));
            InstoreMemberRespDTO memberP2 = channelMemberApi
                    .resolveMember(resolveRequest(tokenP2)).getCheckedData();
            assertThat(memberP2.getUserCode()).isEqualTo(SHARED_BUYER_USER);
            assertThat(memberPort.commands().get(1).route().profileVersionId())
                    .isEqualTo(SHARED_VERSION_P2);

            // 共享 SaaS 租户不合并稳定绑定：两个项目各有一条独立绑定
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_instore_channel_user_binding "
                            + "WHERE buyer_id = ? AND buyer_user_id = ?",
                    Integer.class, SHARED_BUYER, SHARED_BUYER_USER)).isEqualTo(2);
        }
    }

    private static void insertFlowFixtures(JdbcTemplate jdbc) {
        insertBuyer(jdbc, FLOW_BUYER, "BUYER_FLOW_1", "流程采购商");
        insertProject(jdbc, FLOW_PROJECT, "INSTORE_FLOW_P1", FLOW_BUYER);
        insertMemberRoute(jdbc, FLOW_PROFILE, FLOW_VERSION, FLOW_PROJECT, FLOW_BUYER, FLOW_TENANT);
        insertSupplierChannelBinding(jdbc, FLOW_SUPPLIER_BINDING, COMPANY_A);
        insertStableBinding(jdbc, FLOW_BINDING, FLOW_BUYER, FLOW_PROJECT,
                FLOW_BUYER_USER, FLOW_TENANT);
        insertInstoreOrderChain(jdbc);
    }

    private static void insertSharedTenantFixtures(JdbcTemplate jdbc) {
        insertBuyer(jdbc, SHARED_BUYER, "BUYER_SHARED_TENANT", "共享租户采购商");
        insertProject(jdbc, SHARED_PROJECT_P1, "INSTORE_SHARED_P1", SHARED_BUYER);
        insertProject(jdbc, SHARED_PROJECT_P2, "INSTORE_SHARED_P2", SHARED_BUYER);
        insertMemberRoute(jdbc, SHARED_PROFILE_P1, SHARED_VERSION_P1,
                SHARED_PROJECT_P1, SHARED_BUYER, SHARED_TENANT);
        insertMemberRoute(jdbc, SHARED_PROFILE_P2, SHARED_VERSION_P2,
                SHARED_PROJECT_P2, SHARED_BUYER, SHARED_TENANT);
        insertSupplierChannelBinding(jdbc, SHARED_SUPPLIER_BINDING, COMPANY_B);
    }

    private static void insertBuyer(JdbcTemplate jdbc, long buyerId, String buyerCode, String buyerName) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SAAS_MALL', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, buyerId, buyerCode, buyerName, buyerCode);
    }

    private static void insertProject(JdbcTemplate jdbc, long projectId, String projectCode, long buyerId) {
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INDEPENDENT', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, projectId, projectCode, projectCode + "名称", buyerId);
    }

    /** 插入 ACTIVE 档案、PUBLISHED 当前版本和启用 MEMBER_PROFILE 能力，形成可解析的 SaaS 路由。 */
    private static void insertMemberRoute(JdbcTemplate jdbc, long profileId, long versionId,
                                          long projectId, long buyerId, String externalTenantId) {
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile(
                    id, profile_no, buyer_id, project_id, profile_name, current_version_id,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, profileId, "PROFILE_" + profileId, buyerId, projectId,
                "项目会员档案" + profileId, versionId);
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile_version(
                    id, profile_id, version_no, status, effective_at, published_at,
                    change_reason, content_digest, current_flag, version, created_at, updated_at)
                VALUES (?, ?, 1, 10, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),
                        '会员入口路由首发', ?, 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, versionId, profileId, new byte[32]);
        jdbc.update("""
                INSERT INTO sc_buyer_integration_capability(
                    profile_version_id, capability_code, transport_mode, adapter_code,
                    contract_version, source_system, external_tenant_id, http_base_url,
                    credential_set_id, connect_timeout_ms, read_timeout_ms, total_timeout_ms,
                    max_response_bytes, bulkhead_concurrency, rate_limit_per_second,
                    circuit_breaker_policy, retry_policy_code, status, created_at)
                VALUES (?, 'MEMBER_PROFILE', 'RPC', 'SAAS_MEMBER_RPC', 'v1', ?, ?, NULL, NULL,
                        500, 1500, 2000, 65536, 20, 100, 'DEFAULT', 'QUERY_SAME_KEY', 10,
                        UTC_TIMESTAMP(3))
                """, versionId, SOURCE_SYSTEM, externalTenantId);
    }

    /** 插入 RT_MART 渠道当前启用商户绑定，满足 HOME 入口的 companyCode 校验。 */
    private static void insertSupplierChannelBinding(JdbcTemplate jdbc, long bindingId, String companyCode) {
        jdbc.update("""
                INSERT INTO sc_supplier_channel_binding(
                    id, channel_code, external_merchant_id, supplier_id, mapping_version,
                    valid_from, current_flag, status, version, created_at, updated_at)
                VALUES (?, 'RT_MART', ?, 301, 1, UTC_TIMESTAMP(3), 1, 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bindingId, companyCode);
    }

    private static void insertStableBinding(JdbcTemplate jdbc, long bindingId, long buyerId,
                                            long projectId, String buyerUserId, String externalTenantId) {
        jdbc.update("""
                INSERT INTO sc_instore_channel_user_binding(
                    id, binding_no, buyer_id, project_id, source_system, external_tenant_id,
                    buyer_user_id, channel_code, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RT_MART', 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bindingId, "IUB_" + bindingId, buyerId, projectId,
                SOURCE_SYSTEM, externalTenantId, buyerUserId);
    }

    /** 插入 INSTORE 总订单、渠道订单头和唯一支付流水，并回填总订单 detail_id。 */
    private static void insertInstoreOrderChain(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_supply_chain_order(
                    id, order_no, order_type, buyer_id, buyer_code_snapshot, buyer_name_snapshot,
                    buyer_user_id, buyer_order_no, acceptance_receipt_id, display_supplier_id,
                    buyer_settlement_amount, currency, order_status, aftersale_status,
                    business_stage, detail_type, detail_id, detail_version,
                    request_fingerprint, version, created_at, updated_at)
                VALUES (?, ?, 'INSTORE', ?, 'BUYER_FLOW_1', '流程采购商', ?, 'RT_FLOW_ORDER_1',
                        NULL, 301, 10000, 'CNY', 20, 0, 'PAID', 'INSTORE_CHANNEL', NULL, 0,
                        UNHEX(SHA2('flow-order', 256)), 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, FLOW_ORDER, FLOW_ORDER_NO, FLOW_BUYER, FLOW_BUYER_USER);
        jdbc.update("""
                INSERT INTO sc_instore_channel_order(
                    id, instore_order_no, order_id, buyer_id, source_system, external_tenant_id,
                    project_id, cashier_profile_version_id, buyer_user_id, channel_code,
                    channel_user_binding_id, channel_order_id, company_code,
                    supplier_channel_binding_id, supplier_id, supplier_code_snapshot,
                    supplier_name_snapshot, total_amount, total_paid_amount, currency,
                    order_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RT_MART', ?, 'RT_FLOW_ORDER_1', ?, ?,
                        301, 'SUP_RTMART', '大润发', 10000, 10000, 'CNY', 20, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, FLOW_INSTORE_ORDER, "ICO_FLOW_1", FLOW_ORDER, FLOW_BUYER,
                SOURCE_SYSTEM, FLOW_TENANT, FLOW_PROJECT, FLOW_VERSION,
                FLOW_BUYER_USER, FLOW_BINDING, COMPANY_A, FLOW_SUPPLIER_BINDING);
        jdbc.update("""
                INSERT INTO sc_instore_payment_flow(
                    id, payment_flow_no, instore_order_id, order_id, flow_sequence,
                    channel_code, trade_id, user_code, pay_amount, order_type, pay_status,
                    out_trade_no, buyer_paid_amount, external_transaction_id,
                    request_fingerprint, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'RT_MART', ?, ?, 10000, 1, 20, ?, 10000, ?,
                        UNHEX(SHA2('flow-payment', 256)), 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, FLOW_PAYMENT, "IPF_FLOW_1", FLOW_INSTORE_ORDER, FLOW_ORDER,
                FLOW_TRADE_ID, FLOW_BUYER_USER, "OT_FLOW_1", "TXN_FLOW_1");
        jdbc.update("""
                UPDATE sc_supply_chain_order
                SET detail_id = ?, updated_at = UTC_TIMESTAMP(3)
                WHERE id = ?
                """, FLOW_INSTORE_ORDER, FLOW_ORDER);
    }

    private static int credentialStatus(JdbcTemplate jdbc, String purpose, long profileVersionId) {
        return jdbc.queryForObject(
                "SELECT status FROM sc_instore_entry_credential "
                        + "WHERE purpose = ? AND member_profile_version_id = ?",
                Integer.class, purpose, profileVersionId);
    }

    private static HomeEntryReqDTO homeRequest() {
        HomeEntryReqDTO request = new HomeEntryReqDTO();
        request.setLongitude("121.4737");
        request.setLatitude("31.2304");
        return request;
    }

    private static ResolveInstoreMemberReqDTO resolveRequest(String token) {
        ResolveInstoreMemberReqDTO request = new ResolveInstoreMemberReqDTO();
        request.setToken(token);
        return request;
    }

    private ConfigurableApplicationContext applicationContext(String companyCode) {
        // SpringApplicationBuilder.properties(...) 只是低优先级默认属性，会被 application.yaml 中的
        // HOME 默认商品组覆盖。集成测试必须通过命令行参数固定场景商品组，确保夹具与运行时配置一致。
        // 详见 docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程。
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--aifuli.supply-chain.enabled=true",
                        "--aifuli.supply-chain.datasource.url=" + jdbcUrl(),
                        "--aifuli.supply-chain.datasource.username=" + username(),
                        "--aifuli.supply-chain.datasource.password=" + password(),
                        "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--aifuli.supply-chain.instore.home.company-code=" + companyCode);
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "")
                : MYSQL.getPassword();
    }

    /** 测试专用可信上下文：模拟受信任认证链路写入的项目调用身份，可按场景切换。 */
    static class StubContextAccessor implements ProjectCallContextAccessor {

        private BuyerContext buyerContext;
        private ChannelContext channelContext;
        private ProjectContext projectContext;

        void useBuyer(BuyerContext context) {
            this.buyerContext = context;
        }

        void useChannel(ChannelContext context) {
            this.channelContext = context;
        }

        void useProject(ProjectContext context) {
            this.projectContext = context;
        }

        @Override
        public BuyerContext requireBuyerContext() {
            return Objects.requireNonNull(buyerContext, "buyerContext");
        }

        @Override
        public ChannelContext requireChannelContext() {
            return Objects.requireNonNull(channelContext, "channelContext");
        }

        @Override
        public ProjectContext requireProjectContext() {
            return Objects.requireNonNull(projectContext, "projectContext");
        }
    }

    /** 确定性会员 Port：记录外呼身份三元组并返回固定会员资料，不伪造 UNAVAILABLE。 */
    static class RecordingMemberProfilePort implements BuyerMemberProfilePort {

        private final List<ResolveMemberCommand> commands = new ArrayList<>();

        List<ResolveMemberCommand> commands() {
            return commands;
        }

        @Override
        public MemberProfileResult resolve(ResolveMemberCommand command) {
            commands.add(command);
            return new MemberProfileResult(MemberResolutionStatus.FOUND, "13800000000", "测试会员");
        }
    }

    /** 确定性旅通入口 Port：记录命令并把短期 token 嵌入最终 URL，模拟真实返回形态。 */
    static class RecordingEntryUrlPort implements RtmartEntryUrlPort {

        private final List<HomeUrlCommand> homeCommands = new ArrayList<>();
        private final List<DetailUrlCommand> detailCommands = new ArrayList<>();

        List<HomeUrlCommand> homeCommands() {
            return homeCommands;
        }

        List<DetailUrlCommand> detailCommands() {
            return detailCommands;
        }

        @Override
        public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
            homeCommands.add(command);
            return new EntryUrlResult("https://rtmart.test/home?token=" + command.token());
        }

        @Override
        public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
            detailCommands.add(command);
            return new EntryUrlResult("https://rtmart.test/detail?token=" + command.token());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            InstoreChannelUserBindingResolver.class, InstoreEntryCredentialService.class,
            BuyerProjectAccessResolver.class, InstoreEntryAccessService.class,
            BuyerIntegrationRouteResolver.class,
            InstoreBuyerEntryFacade.class, InstoreChannelMemberFacade.class,
            InstoreBuyerEntryApiImpl.class, InstoreChannelMemberApiImpl.class})
    static class TestConfiguration {

        @Bean
        ProjectCallContextAccessor projectCallContextAccessor() {
            return new StubContextAccessor();
        }

        @Bean
        BuyerMemberProfilePort buyerMemberProfilePort() {
            return new RecordingMemberProfilePort();
        }

        @Bean
        RtmartEntryUrlPort rtmartEntryUrlPort() {
            return new RecordingEntryUrlPort();
        }
    }
}
