package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 查询七类对象的历史永久编码，避免旧数据与新流水直接重名。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#3-手填与自动分配。
 */
@Mapper
public interface BusinessCodeExistingMapper {
    List<String> selectCodes(@Param("kind") String kind);
}
