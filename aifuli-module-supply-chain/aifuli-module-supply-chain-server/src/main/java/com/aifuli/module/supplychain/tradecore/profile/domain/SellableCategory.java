package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 可售品类封闭枚举：供应商供货配置、分销商档案（多选）、平台SPU 共用同一套代码，互相独立、不校验父子链。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#21-可售品类-sellablecategory}。
 */
public enum SellableCategory {
    EQUITY,
    PHYSICAL,
    MOVIE,
    JD,
    DINING,
    TAKEOUT,
    SUPERMARKET,
    HEMA,
    RTMART,
    LIFE
}
