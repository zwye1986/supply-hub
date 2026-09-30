package com.aifuli.module.supplychain.architecture;

import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

class SupplyChainModuleArchitectureTest {

    private static JavaClasses importedClasses;

    static final ArchRule routing_is_pure = noClasses()
            .that().resideInAPackage("..routing.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "org.springframework..", "org.apache.ibatis..");

    /**
     * Offer 身份上的人工评分是路由预备输入，引擎不依赖评分服务。
     * PRD 三策略模型（STABLE/COMPOSITE）经 {@code RoutingCandidate} 不可变快照注入评分，
     * 该快照类为唯一豁免；其余 routing.domain 类仍不得持有评分字段。
     * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#7-与路由的边界} 与
     * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。
     */
    static final ArchRule routing_domain_does_not_depend_on_fulfillment_score_service = noClasses()
            .that().resideInAPackage("..routing.domain..")
            .should().dependOnClassesThat()
            .haveSimpleName("OfferFulfillmentScoreService");

    static final ArchRule routing_domain_has_no_fulfillment_score_fields = noFields()
            .that().areDeclaredInClassesThat(
                    // PRD 三策略模型：RoutingCandidate 快照为评分字段唯一豁免。
                    JavaClass.Predicates.resideInAPackage("..routing.domain..")
                            .and(DescribedPredicate.describe("is not RoutingCandidate snapshot",
                                    clazz -> !"RoutingCandidate".equals(clazz.getSimpleName()))))
            .should().haveNameMatching("(?i).*(stability|timeliness).*score.*");

    /**
     * catalog 应用层不得直接写 Offer 评分；商品侧 Controller 只调用 tradecore.supply 单一写入服务。
     * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#2-领域不变量}。
     */
    static final ArchRule catalog_application_does_not_write_offer_scores = noClasses()
            .that().resideInAPackage("..tradecore.catalog.application..")
            .should().dependOnClassesThat()
            .haveSimpleName("OfferFulfillmentScoreService");

    static final ArchRule adapter_does_not_write_orders = noClasses()
            .that().resideInAPackage("..supplieradapter..")
            .should().dependOnClassesThat().resideInAPackage("..border..dal..");

    /** Facade 只做参数校验、加密与委派，不得跨层直接访问 Mapper/DO 等持久化对象。 */
    static final ArchRule facade_does_not_access_persistence = noClasses()
            .that().resideInAPackage("..application.facade..")
            .should().dependOnClassesThat().resideInAnyPackage("..dal..");

    /**
     * 供货命中、标品SKU池供应商过滤、分销商品列表展示禁止直接依赖 ProductCategoryMapper：
     * 类目树数据所有权在 catalog。三码写入期由 {@code ProductClassificationValidationService} 解析类目编码；
     * 供货命中的归属判断（配置码为 SPU 码祖先或相等，2026-09-23 起取代字符串全等）由 catalog 应用层
     * {@code ProductCategoryCoverageService} 封装，supply 侧只能经它委托，不得自己回退去走类目树。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}（d3）、
     * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
     */
    static final ArchRule supply_matching_does_not_read_category_tree = noClasses()
            .that().haveSimpleName("SupplierCooperativeProductService")
            .or().haveSimpleName("PlatformSkuQueryService")
            .or().haveSimpleName("BuyerProductQueryService")
            .should().dependOnClassesThat().haveSimpleName("ProductCategoryMapper");

    /** border 场景层不得直接读写账户 DAL 或账本，资金动作必须经由 tradecore.account/ledger 的应用服务。 */
    static final ArchRule border_does_not_access_account_dal_or_ledger = noClasses()
            .that().resideInAPackage("..border..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..tradecore.account.dal..", "..tradecore.ledger..");

    /** 项目集成层只解析版本化路由，不得越界读写 O2O/到店购场景私有持久化。 */
    static final ArchRule integration_buyer_does_not_access_scenario_persistence = noClasses()
            .that().resideInAPackage("..integration.buyer..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..border.o2o.dal..", "..border.instore.dal..");

    /** INSTORE Facade 只能通过 Resolver 解析项目档案，禁止跨层直接读取项目集成档案 Mapper/DO。 */
    static final ArchRule instore_facade_does_not_access_buyer_integration_persistence = noClasses()
            .that().resideInAPackage("..application.facade..")
            .and().haveSimpleNameStartingWith("Instore")
            .should().dependOnClassesThat().resideInAPackage("..integration.buyer.dal..");

    /** 外部会员标识是项目作用域内的不透明字符串，任何模块均不得把 buyerUserId 声明为 Long。 */
    static final ArchRule buyer_user_id_is_never_long = noFields()
            .that().haveName("buyerUserId")
            .should().haveRawType(Long.class);

    /**
     * SaaS 会员 Adapter 不得把 buyerUserId 解析为数值：兼容解释只允许发生在 member-server。
     * 设计见 {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#33-supply-chain-server}。
     */
    static final ArchRule saas_member_adapter_never_calls_long_parse = noClasses()
            .that().resideInAPackage("..integration.saasmall..")
            .should().callMethod(Long.class, "parseLong", String.class);

    /** 同上：禁止 valueOf(String) 形式的数值化回退。 */
    static final ArchRule saas_member_adapter_never_calls_long_value_of = noClasses()
            .that().resideInAPackage("..integration.saasmall..")
            .should().callMethod(Long.class, "valueOf", String.class);

    /**
     * INSTORE RPC 契约层不得反向依赖集成出站端口或 DAL：项目与路由字段只由服务端
     * 可信上下文提供，DTO 保持纯传输对象。设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。
     */
    static final ArchRule api_instore_does_not_depend_on_integration_or_dal = noClasses()
            .that().resideInAPackage("..api.instore..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..integration..", "..dal..");

    /**
     * 会员资料响应 DTO 只能在 API 与应用编排层流转，DAL、Outbox 与 Timer 不得引用，
     * 防止把对外会员字段持久化或跨模块投递。设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。
     */
    static final ArchRule dal_and_infrastructure_do_not_touch_member_response = noClasses()
            .that().resideInAnyPackage("..dal..", "..infrastructure.outbox..", "..infrastructure.timer..")
            .should().dependOnClassesThat().haveSimpleName("InstoreMemberRespDTO");

    /**
     * 应用层禁止调用 014 过渡态租户四元组查询：共享 SaaS 租户的多个采购项目必须按
     * 项目会员三元组解析绑定。与 Resolver Mockito 测试共同保证生产路径不回退到废弃入口。
     * 设计见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#6-稳定绑定}。
     */
    static final ArchRule application_never_calls_tenant_tuple_binding_lookup = noClasses()
            .that().resideInAnyPackage("..application..", "..application.facade..")
            .should().callMethod(InstoreChannelUserBindingMapper.class, "selectByTenantUser",
                    String.class, String.class, String.class, String.class);

    /**
     * INSTORE RPC 实现层只允许依赖 Facade 与可信上下文访问器，禁止直连 DAL、集成层、
     * 场景应用层或账户核心，保证 Wire 编排不绕过应用服务边界。
     * 设计见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#7-测试门禁}。
     */
    static final ArchRule instore_rpc_impls_are_thin_delegates = noClasses()
            .that().resideInAPackage("..interfaceapi.rpc..")
            .and().haveSimpleNameStartingWith("Instore")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dal..", "..integration..", "..border..", "..tradecore..");

    /**
     * 项目应用认证是独立基础设施边界，只负责验签并产出可信上下文，禁止反向依赖业务编排、
     * 场景或交易核心。设计见
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁}。
     */
    static final ArchRule project_auth_infrastructure_is_isolated = noClasses()
            .that().resideInAPackage("..infrastructure.auth..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..border..", "..application..", "..tradecore..", "..interfaceapi..");

    /** 认证包外只能通过 ProjectCallContextAccessor 读取可信上下文，不得触碰 ThreadLocal 容器。 */
    static final ArchRule only_auth_package_touches_project_auth_context_holder = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.auth..")
            .should().dependOnClassesThat().haveSimpleName("ProjectAuthContextHolder");

    /** 管理端 Controller 不得访问供应商 Adapter。 */
    static final ArchRule admin_controllers_do_not_depend_on_supplier_adapter = noClasses()
            .that().resideInAPackage("..interfaceapi.admin..")
            .should().dependOnClassesThat()
            .resideInAPackage("..supplieradapter..");

    /** 开票应用服务不得访问供应商 Adapter。设计见 2026-09-07 开票信息设计 §10。 */
    static final ArchRule invoice_services_do_not_depend_on_supplier_adapter = noClasses()
            .that().haveSimpleNameEndingWith("InvoiceService")
            .should().dependOnClassesThat()
            .resideInAPackage("..supplieradapter..");

    /**
     * 本轮运营权限只做功能 RBAC，禁止把无 tenant_id 的 sc_* 资源误套 yudao 部门数据权限。
     * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#7-后续数据范围边界c本轮不实现}。
     */
    static final ArchRule admin_controllers_do_not_use_framework_data_permission = noClasses()
            .that().resideInAPackage("..interfaceapi.admin..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.aifuli.framework.datapermission.core.annotation.DataPermission");

    /**
     * 管理端每个对外 HTTP 映射必须有方法级或类级 {@code @PreAuthorize}。
     * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#9-测试门禁}。
     */
    static final ArchRule admin_http_endpoints_have_pre_authorize = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..interfaceapi.admin..")
            .should(havePreAuthorizeIfHttpMapping());

    /**
     * {@code system_menu.application_code} 是菜单维护兼容字段，不得影响供应链业务接口授权。
     * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。
     */
    static final ArchRule admin_api_does_not_depend_on_menu_application_code = noClasses()
            .that().resideInAPackage("..interfaceapi.admin..")
            .should().dependOnClassesThat()
            .haveSimpleName("MenuApplicationCodeConstants");

    /**
     * 目录与定价编排不得写采购商账本或账户 DAL；期初只允许 buyer 应用层调开户服务。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
     */
    static final ArchRule catalog_pricing_does_not_write_ledger = noClasses()
            .that().resideInAPackage("..tradecore.catalog..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..tradecore.ledger..", "..tradecore.account.dal..");

    /**
     * 021 已发布的模板 L2 表只作数据库兼容预留；本期目录应用服务不得重新依赖其 DO 或 Mapper，
     * 有效结算价固定按 L3 &gt; 手工 L2 &gt; L1 解析。设计见
     * {@code docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#52-l2-与-follow_template}。
     */
    static final ArchRule catalog_application_does_not_use_disabled_l2_template = noClasses()
            .that().resideInAPackage("..tradecore.catalog.application..")
            .should().dependOnClassesThat()
            .haveSimpleNameStartingWith("BuyerProductSettlementPrice");

    /** 认证包外不得直接引用包级上下文对象，避免业务层绕过限定 scope 的访问器。 */
    static final ArchRule only_auth_package_touches_project_auth_context = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.auth..")
            .should().dependOnClassesThat().haveSimpleName("ProjectAuthContext");

    /**
     * 显式导入生产类并由 JUnit 5 执行规则，避免 ArchUnit TestEngine 与当前 JUnit Platform
     * 组合出现“发现测试类但执行 0 条规则”的假绿。
     * 详见 docs/集成测试.md#34-契约与架构门禁。
     */
    @BeforeAll
    static void importProductionClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.aifuli.module.supplychain");
    }

    @Test
    void routingIsPure() {
        routing_is_pure.check(importedClasses);
    }

    @Test
    void routingDomainDoesNotDependOnFulfillmentScoreService() {
        routing_domain_does_not_depend_on_fulfillment_score_service.check(importedClasses);
    }

    @Test
    void routingDomainHasNoFulfillmentScoreFields() {
        routing_domain_has_no_fulfillment_score_fields.check(importedClasses);
    }

    @Test
    void catalogApplicationDoesNotWriteOfferScores() {
        catalog_application_does_not_write_offer_scores.check(importedClasses);
    }

    @Test
    void adapterDoesNotWriteOrders() {
        adapter_does_not_write_orders.check(importedClasses);
    }

    @Test
    void facadeDoesNotAccessPersistence() {
        facade_does_not_access_persistence.check(importedClasses);
    }

    @Test
    void supplyMatchingDoesNotReadCategoryTree() {
        supply_matching_does_not_read_category_tree.check(importedClasses);
    }

    @Test
    void borderDoesNotAccessAccountDalOrLedger() {
        border_does_not_access_account_dal_or_ledger.check(importedClasses);
    }

    @Test
    void integrationBuyerDoesNotAccessScenarioPersistence() {
        integration_buyer_does_not_access_scenario_persistence.check(importedClasses);
    }

    @Test
    void instoreFacadeDoesNotAccessBuyerIntegrationPersistence() {
        instore_facade_does_not_access_buyer_integration_persistence.check(importedClasses);
    }

    @Test
    void buyerUserIdIsNeverLong() {
        buyer_user_id_is_never_long.check(importedClasses);
    }

    @Test
    void saasMemberAdapterNeverCallsLongParse() {
        saas_member_adapter_never_calls_long_parse.check(importedClasses);
    }

    @Test
    void saasMemberAdapterNeverCallsLongValueOf() {
        saas_member_adapter_never_calls_long_value_of.check(importedClasses);
    }

    @Test
    void apiInstoreDoesNotDependOnIntegrationOrDal() {
        api_instore_does_not_depend_on_integration_or_dal.check(importedClasses);
    }

    @Test
    void dalAndInfrastructureDoNotTouchMemberResponse() {
        dal_and_infrastructure_do_not_touch_member_response.check(importedClasses);
    }

    @Test
    void applicationNeverCallsTenantTupleBindingLookup() {
        application_never_calls_tenant_tuple_binding_lookup.check(importedClasses);
    }

    @Test
    void instoreRpcImplsAreThinDelegates() {
        instore_rpc_impls_are_thin_delegates.check(importedClasses);
    }

    @Test
    void projectAuthInfrastructureIsIsolated() {
        project_auth_infrastructure_is_isolated.check(importedClasses);
    }

    @Test
    void onlyAuthPackageTouchesProjectAuthContextHolder() {
        only_auth_package_touches_project_auth_context_holder.check(importedClasses);
    }

    @Test
    void onlyAuthPackageTouchesProjectAuthContext() {
        only_auth_package_touches_project_auth_context.check(importedClasses);
    }

    @Test
    void adminControllersDoNotDependOnSupplierAdapter() {
        admin_controllers_do_not_depend_on_supplier_adapter.check(importedClasses);
    }

    @Test
    void invoiceServicesDoNotDependOnSupplierAdapter() {
        invoice_services_do_not_depend_on_supplier_adapter.check(importedClasses);
    }

    @Test
    void adminControllersDoNotUseFrameworkDataPermission() {
        admin_controllers_do_not_use_framework_data_permission.check(importedClasses);
    }

    @Test
    void catalogPricingDoesNotWriteLedger() {
        catalog_pricing_does_not_write_ledger.check(importedClasses);
    }

    @Test
    void catalogApplicationDoesNotUseDisabledL2Template() {
        catalog_application_does_not_use_disabled_l2_template.check(importedClasses);
    }

    @Test
    void adminHttpEndpointsHavePreAuthorize() {
        admin_http_endpoints_have_pre_authorize.check(importedClasses);
    }

    @Test
    void adminApiDoesNotDependOnMenuApplicationCode() {
        admin_api_does_not_depend_on_menu_application_code.check(importedClasses);
    }

    private static ArchCondition<JavaMethod> havePreAuthorizeIfHttpMapping() {
        return new ArchCondition<>("HTTP 映射方法在方法或类上声明 @PreAuthorize") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!isHttpMapping(method)) {
                    return;
                }
                boolean authorized = method.isAnnotatedWith(PreAuthorize.class)
                        || method.getOwner().isAnnotatedWith(PreAuthorize.class);
                if (authorized) {
                    events.add(SimpleConditionEvent.satisfied(method,
                            method.getFullName() + " 已声明 @PreAuthorize"));
                } else {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " 缺少 @PreAuthorize"));
                }
            }
        };
    }

    private static boolean isHttpMapping(JavaMethod method) {
        return method.isAnnotatedWith(RequestMapping.class)
                || method.isAnnotatedWith(GetMapping.class)
                || method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class);
    }
}
