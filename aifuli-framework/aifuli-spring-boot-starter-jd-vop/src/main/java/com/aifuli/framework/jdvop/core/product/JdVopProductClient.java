package com.aifuli.framework.jdvop.core.product;

import java.util.List;

/**
 * 京东 VOP 商品客户端。
 */
public interface JdVopProductClient {

    /**
     * 校验商品是否可采购。
     *
     * <p>校验范围包括商品池、主站上下架、预约预售、合同购买权限和区域限售。</p>
     *
     * @param request SKU 数量及京东四级地址
     * @return 每个京东 SKU 的可采购结果
     */
    List<JdSkuSaleState> checkProduct(JdProductCheckRequest request);

}
