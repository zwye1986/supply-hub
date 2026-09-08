package com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_timer_execution")
@Data
@Builder
public class TimerExecutionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String executionNo;
    private Long taskId;
    private Integer attemptNo;
    private Long fencingToken;
    private String workerId;
    private Short status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private byte[] errorDigest;
    private byte[] resultDigest;
    private LocalDateTime createdAt;
}
