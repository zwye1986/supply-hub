package com.aifuli.framework.jdvop.core.address;

import com.aifuli.framework.jdvop.core.product.JdAreaInfo;

/**
 * 京东 VOP 地址客户端。
 */
public interface JdVopAddressClient {

    /**
     * 根据完整收货地址解析京东四级地址。
     *
     * @param addressDetail 完整收货地址
     * @return 京东四级地址
     */
    JdAreaInfo convertFourAreaByDetailAddress(String addressDetail);

}
