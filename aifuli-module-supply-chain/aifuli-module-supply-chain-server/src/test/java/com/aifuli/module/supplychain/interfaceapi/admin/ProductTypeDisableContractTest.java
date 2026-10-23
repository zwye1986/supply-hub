package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.tracer.core.filter.TraceFilter;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实控制器、应用校验与全局异常处理器的前端提示契约；数据库行为另由真实 MySQL 测试验证。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#441-已关联平台商品的类型禁止禁用2026-10-23}。
 */
class ProductTypeDisableContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothEndpointsReturnExplicitBusinessMessage(boolean throughEdit) throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        ProductTypeMapper types = mock(ProductTypeMapper.class);
        PlatformSpuMapper spus = mock(PlatformSpuMapper.class);
        when(types.selectByCodeForUpdate("PT_USED")).thenReturn(ProductTypeDO.builder()
                .id(1L).typeCode("PT_USED").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spus.selectCount(any())).thenReturn(1L);
        var service = new ProductTypeAdminService(types, spus, mock(PlatformSkuMapper.class),
                mock(BusinessNoGenerator.class), mock(SupplyChainDatabaseClock.class));
        var mvc = MockMvcBuilders.standaloneSetup(new ProductTypeAdminController(service))
                .setControllerAdvice(new GlobalExceptionHandler("supply-chain-server", null))
                .addFilters(new TraceFilter()).build();

        mvc.perform(put("/supply-chain/product-types/PT_USED" + (throughEdit ? "" : "/status"))
                        .header("trace-id", "product-type-disable-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(throughEdit ? """
                                {"typeName":"新名称","iconUrl":"https://example.com/icon.png","status":20}
                                """ : "{\"targetStatus\":\"20\"}"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("trace-id", "product-type-disable-contract"))
                .andExpect(jsonPath("$.code").value(1_050_001_184))
                .andExpect(jsonPath("$.msg").value("该商品类型下已存在平台商品，无法禁用。"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(types, never()).updateById(any(ProductTypeDO.class));
    }
}
