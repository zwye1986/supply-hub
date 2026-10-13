package com.aifuli.module.supplychain.infrastructure.identity;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_NO_CAPACITY_EXHAUSTED;

import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.OrderNoSegmentDO;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.OrderNoSequenceDO;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.OrderNoSegmentMapper;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.OrderNoSequenceMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 复用供应链既有数据源和事务管理器，在日期行锁内原子推进高水位并插入永久号段。 返回值仅在TransactionTemplate提交成功并释放连接后可消费；提交异常或不确定时不交付号段。
 * 事务中只访问sc_order_no_*与数据库时钟，不外呼Redis、不挂起业务事务、不创建新连接池。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#42-申请号段的短事务}。
 */
public class MySqlOrderNoSegmentStore implements OrderNoSegmentStore {
  private final OrderNoSequenceMapper sequence;
  private final OrderNoSegmentMapper segment;
  private final SupplyChainDatabaseClock clock;
  private final DataSource dataSource;
  private final PlatformTransactionManager transactionManager;

  public MySqlOrderNoSegmentStore(
      OrderNoSequenceMapper sequence,
      OrderNoSegmentMapper segment,
      SupplyChainDatabaseClock clock,
      DataSource dataSource,
      PlatformTransactionManager transactionManager) {
    this.sequence = Objects.requireNonNull(sequence);
    this.segment = Objects.requireNonNull(segment);
    this.clock = Objects.requireNonNull(clock);
    this.dataSource = Objects.requireNonNull(dataSource);
    this.transactionManager = Objects.requireNonNull(transactionManager);
  }

  @Override
  public OrderNoSegment reserve(
      LocalDate date, String ownerId, int segmentSize, int count, Duration timeout) {
    OrderNoTransactionGuard.check(dataSource);
    Objects.requireNonNull(date);
    if (segmentSize < 2 || segmentSize > 10_000 || (count != 1 && count != 2))
      throw new IllegalArgumentException("订单号段预留大小或批次数非法");
    // 在事务前校验owner，避免错误参数永久推进高水位。
    new OrderNoSegment(date, 1, 1, ownerId);
    Duration allowed = OrderNoAllocationService.remaining(Objects.requireNonNull(timeout));
    if (allowed.isZero() || allowed.isNegative())
      throw new IllegalArgumentException("订单号段预留超时须为正数");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(Math.toIntExact(Math.max(1, (allowed.toMillis() + 999) / 1000)));
    OrderNoSegment reserved =
        transaction.execute(
            status -> {
              OrderNoAllocationService.remaining(allowed);
              var now = clock.now();
              sequence.ensureDate(date, now);
              OrderNoSequenceDO row =
                  sequence.selectOne(
                      new LambdaQueryWrapper<OrderNoSequenceDO>()
                          .eq(OrderNoSequenceDO::getSequenceDate, date)
                          .last("FOR UPDATE"));
              if (row == null
                  || row.getLastReservedValue() == null
                  || row.getLastReservedValue() < 0
                  || row.getLastReservedValue() > OrderNoFormatter.MAX_SEQUENCE)
                throw new IllegalStateException("订单编号日期高水位不变量故障");
              int last = row.getLastReservedValue();
              int available = OrderNoFormatter.MAX_SEQUENCE - last;
              if (available < count) throw exception(ORDER_NO_CAPACITY_EXHAUSTED);
              int end = last + Math.min(segmentSize, available);
              if (sequence.update(
                      null,
                      new LambdaUpdateWrapper<OrderNoSequenceDO>()
                          .eq(OrderNoSequenceDO::getSequenceDate, date)
                          .eq(OrderNoSequenceDO::getLastReservedValue, last)
                          .set(OrderNoSequenceDO::getLastReservedValue, end)
                          .set(OrderNoSequenceDO::getUpdatedAt, now))
                  != 1) throw new IllegalStateException("订单编号日期高水位推进影响行数异常");
              OrderNoSegmentDO record = new OrderNoSegmentDO();
              record.setSequenceDate(date);
              record.setStartValue(last + 1);
              record.setEndValue(end);
              record.setOwnerId(ownerId);
              record.setCreatedAt(now);
              if (segment.insert(record) != 1) throw new IllegalStateException("订单编号永久号段插入影响行数异常");
              OrderNoAllocationService.remaining(allowed);
              return new OrderNoSegment(date, last + 1, end, ownerId);
            });
    // 即使提交已经确认，调用预算过期也只能弃用，不能把该区间回收。
    OrderNoAllocationService.remaining(allowed);
    return Objects.requireNonNull(reserved, "订单编号预留事务未返回号段");
  }
}
