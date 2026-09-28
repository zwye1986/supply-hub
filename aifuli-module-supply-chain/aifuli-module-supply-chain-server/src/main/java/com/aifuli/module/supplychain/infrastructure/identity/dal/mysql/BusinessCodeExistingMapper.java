package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通过各业务表已有的永久编码唯一索引，只查询本次分配可能命中的历史编码。
 * 候选集合最多 999 个，禁止无条件读取整张业务表。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
 */
@Mapper
public interface BusinessCodeExistingMapper {
    List<String> selectExistingCodes(@Param("kind") String kind,
                                     @Param("candidates") List<String> candidates);

    /** 旧 SPU/分销商的父段归属查询；无业务行时保留占号记录的通用作用域错误。 */
    String selectParentCodeByNumber(@Param("kind") String kind, @Param("number") int number);
}
