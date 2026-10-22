package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.testsupport.SupplierExternalSkuTestSupport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实编码唯一性、批量回滚及供应商锁门禁。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#35-外部商品编码占用预检与管理端错误契约2026-10-22。
 */
class SupplierExternalSkuConflictIntegrationTest extends SupplierExternalSkuTestSupport {
    @ParameterizedTest
    @ValueSource(shorts = {0, 10})
    void draftAndPublishedIdentitiesAlsoOwnExternalCode(short status) {
        var f = fixture();
        var occupied = service.createPublished(f.supplierCode(), List.of(line(f.first(), "OWNED")), 1L).getFirst();
        // 隔离库直接准备历史身份状态，不将本用例误当作正式发布流程验收。
        jdbc.update("UPDATE sc_supplier_offer SET status=? WHERE offer_no=?", status, occupied);
        assertConflict(() -> service.createPublished(f.supplierCode(), List.of(line(f.second(), "OWNED")), 1L),
                "OWNED", f.first().getSkuCode());
        assertThat(offerCount(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHARED", "shared", "sharéd"})
    void suspendedOfferOwnsCodeUsingDatabaseCollation(String candidate) {
        var f = fixture();
        service.createPublished(f.supplierCode(), List.of(line(f.first(), "SHARED")), 1L);
        assertConflict(() -> service.createPublished(f.supplierCode(), List.of(line(f.second(), candidate)), 1L),
                candidate, f.first().getSkuCode());
        assertThat(offerCount(f)).isEqualTo(1);
    }

    @Test
    void editExcludesSelfAndRejectsAnotherOwnerWithoutChangingDraft() {
        var f = fixture();
        var numbers = service.createPublished(f.supplierCode(),
                List.of(line(f.first(), "FIRST"), line(f.second(), "SECOND")), 1L);
        service.updateCommercial(f.supplierCode(), numbers.getFirst(), line(f.first(), "FIRST"), 1L);
        var before = offerMapper.selectByNo(numbers.getFirst());
        assertConflict(() -> service.updateCommercial(f.supplierCode(), numbers.getFirst(), line(f.first(), "SECOND"), 1L),
                "SECOND", f.second().getSkuCode());
        assertThat(offerMapper.selectByNo(numbers.getFirst())).isEqualTo(before);
    }

    @Test
    void retiredOfferReleasesCodeAndKeepsHistory() {
        var f = fixture();
        var original = service.createPublished(f.supplierCode(), List.of(line(f.first(), "REUSABLE")), 1L).getFirst();
        service.retire(f.supplierCode(), original, 1L);
        var replacement = service.createPublished(f.supplierCode(), List.of(line(f.second(), "REUSABLE")), 1L).getFirst();
        assertThat(offerMapper.selectByNo(original).getStatus()).isEqualTo((short) 90);
        assertThat(offerMapper.selectByNo(original).getExternalSkuCode()).isEqualTo("REUSABLE");
        assertThat(offerMapper.selectByNo(replacement).getStatus()).isEqualTo((short) 20);
        assertThat(offerCount(f)).isEqualTo(2);
    }

    @Test
    void differentSuppliersCanUseSameCode() {
        var first = fixture();
        var second = fixture();
        service.createPublished(first.supplierCode(), List.of(line(first.first(), "SAME")), 1L);
        service.createPublished(second.supplierCode(), List.of(line(second.first(), "SAME")), 1L);
        assertThat(offerCount(first)).isEqualTo(1);
        assertThat(offerCount(second)).isEqualTo(1);
    }

    @Test
    void duplicateWithinBatchRollsBackFirstInsert() {
        var f = fixture();
        assertConflict(() -> service.createDrafts(f.supplierCode(),
                List.of(line(f.first(), "BATCH"), line(f.second(), "batch")), 1L), "batch", f.first().getSkuCode());
        assertThat(offerCount(f)).isZero();
    }

    @Test
    void laterExistingConflictRollsBackEarlierInsert() {
        var f = fixture();
        var occupied = service.createPublished(f.supplierCode(), List.of(line(f.first(), "TAKEN")), 1L).getFirst();
        var before = offerMapper.selectByNo(occupied);
        var third = fixture();
        assertConflict(() -> service.createPublished(f.supplierCode(),
                List.of(line(f.second(), "FREE"), line(third.first(), "TAKEN")), 1L), "TAKEN", f.first().getSkuCode());
        assertThat(offerCount(f)).isEqualTo(1);
        assertThat(offerMapper.selectByNo(occupied)).isEqualTo(before);
    }

    @Test
    void concurrentCreatesHaveOneWinnerAndOneBusinessRejection() throws Exception {
        var f = fixture();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(f.first(), f.second()).stream().map(sku -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发启动超时");
                try {
                    service.createPublished(f.supplierCode(), List.of(line(sku, "RACE")), 1L);
                    return 0;
                } catch (ServiceException rejection) {
                    assertThat(rejection.getMessage()).contains("RACE", "下架商品仍占用编码");
                    return rejection.getCode();
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(tasks.getFirst().get(15, TimeUnit.SECONDS), tasks.getLast().get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1_050_001_183);
        }
        assertThat(offerCount(f)).isEqualTo(1);
    }

    private static void assertConflict(Runnable action, String external, String skuCode) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class)
                .hasMessage("供应商商品编码「" + external + "」已被合作商品「" + skuCode
                        + "」占用，下架商品仍占用编码，请核对后修改。")
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_050_001_183);
    }
}
