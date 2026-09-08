package com.aifuli.module.supplychain.border.o2o.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelUserBindingDO;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.o2o.domain.O2OChannelCode;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_CHANNEL_USER_BINDING_MISSING;

/**
 * O2O 渠道用户绑定解析器：以渠道匿名用户标识安全映射采购商及其 C 端用户。
 *
 * <p>缺失、歧义或停用的绑定在建单和冻结前拒绝；H5 入口按 (buyerId,buyerUserId,channelCode) 读取或创建绑定，
 * 并生成不可枚举的高熵随机 channelUserId。内部 buyerId/buyerUserId 不得直接暴露给渠道。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#3.6-O2O-应用编排}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class O2OChannelUserBindingResolver {

    private final SupplyChainDatabaseClock databaseClock;

    /** 绑定状态：10 启用，20 停用。 */
    private static final short STATUS_ACTIVE = 10;

    private final O2OChannelUserBindingMapper bindingMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SecureRandom random = new SecureRandom();

    /**
     * 按 (channelCode, channelUserId) 解析启用绑定。
     *
     * <p>缺失或停用直接拒绝，建单冻结前不得猜测归属。详见
     * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#3.6-O2O-应用编排}。</p>
     */
    public BindingSnapshot resolveActive(String channelCode, String channelUserId) {
        log.info("[O2O][binding] resolveActive 入口 channelCode={}, channelUserId={}", channelCode, channelUserId);
        O2OChannelUserBindingDO binding = bindingMapper.selectActiveByChannelUser(channelCode, channelUserId);
        if (binding == null) {
            log.warn("[O2O][binding] 启用绑定不存在 channelCode={}, channelUserId={}", channelCode, channelUserId);
            throw exception(O2O_CHANNEL_USER_BINDING_MISSING);
        }
        return new BindingSnapshot(binding.getId(), binding.getBuyerId(),
                binding.getBuyerUserId(), binding.getChannelUserId());
    }

    /**
     * H5 入口按 (buyerId, buyerUserId, channelCode) 读取或创建绑定，返回匿名 channelUserId。
     *
     * <p>并发首创建靠唯一键冲突后回读；停用绑定不得复用。内部 buyerId 不得暴露给渠道。</p>
     */
    public BindingSnapshot resolveOrCreateForEntry(Long buyerId, String buyerUserId, String channelCode) {
        log.info("[O2O][binding] resolveOrCreateForEntry 入口 buyerId={}, buyerUserId={}, channelCode={}",
                buyerId, buyerUserId, channelCode);
        O2OChannelUserBindingDO existing = bindingMapper.selectByBuyerUser(buyerId, buyerUserId, channelCode);
        if (existing != null) {
            if (existing.getStatus() != STATUS_ACTIVE) {
                log.warn("[O2O][binding] 绑定已停用 buyerId={}, channelCode={}, bindingId={}",
                        buyerId, channelCode, existing.getId());
                throw exception(O2O_CHANNEL_USER_BINDING_MISSING);
            }
            log.info("[O2O][binding] 复用已有绑定 bindingId={}, channelUserId={}",
                    existing.getId(), existing.getChannelUserId());
            return new BindingSnapshot(existing.getId(), existing.getBuyerId(),
                    existing.getBuyerUserId(), existing.getChannelUserId());
        }
        return createBinding(buyerId, buyerUserId, channelCode);
    }

    private BindingSnapshot createBinding(Long buyerId, String buyerUserId, String channelCode) {
        requireSupportedChannel(channelCode);
        LocalDateTime now = databaseClock.now();
        O2OChannelUserBindingDO binding = O2OChannelUserBindingDO.builder()
                .bindingNo(businessNoGenerator.next(BusinessNoPrefixes.OUB))
                .buyerId(buyerId)
                .buyerUserId(buyerUserId)
                .channelCode(channelCode)
                .channelUserId(generateChannelUserId())
                .status(STATUS_ACTIVE)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            bindingMapper.insert(binding);
            log.info("[O2O][binding] 新建绑定 bindingId={}, channelUserId={}, buyerId={}",
                    binding.getId(), binding.getChannelUserId(), buyerId);
        } catch (DuplicateKeyException duplicate) {
            // 并发首创建：回读已创建绑定。
            O2OChannelUserBindingDO reread = bindingMapper.selectByBuyerUser(buyerId, buyerUserId, channelCode);
            if (reread == null) {
                throw duplicate;
            }
            return new BindingSnapshot(reread.getId(), reread.getBuyerId(),
                    reread.getBuyerUserId(), reread.getChannelUserId());
        }
        return new BindingSnapshot(binding.getId(), binding.getBuyerId(),
                binding.getBuyerUserId(), binding.getChannelUserId());
    }

    /** 生成 256bit 高熵随机匿名渠道用户标识，禁止可枚举。 */
    private String generateChannelUserId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "ch-anon-" + HexFormat.of().formatHex(bytes);
    }

    private static void requireSupportedChannel(String channelCode) {
        try {
            O2OChannelCode.valueOf(channelCode);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported O2O channel code: " + channelCode);
        }
    }

    /** 绑定快照：受理时固化的归属与匿名标识。 */
    public record BindingSnapshot(Long bindingId, Long buyerId,
                                   String buyerUserId, String channelUserId) {
    }
}
