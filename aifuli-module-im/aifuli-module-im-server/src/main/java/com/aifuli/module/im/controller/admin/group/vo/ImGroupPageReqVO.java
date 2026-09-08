package com.aifuli.module.im.controller.admin.group.vo;

import lombok.*;
import io.swagger.v3.oas.annotations.media.Schema;
import com.aifuli.framework.common.pojo.PageParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

import static com.aifuli.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 群分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ImGroupPageReqVO extends PageParam {

    @Schema(description = "群名称", example = "芋艿")
    private String name;

    @Schema(description = "群主用户编号", example = "31460")
    private Long ownerUserId;

    @Schema(description = "群公告")
    private String notice;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}