package com.aifuli.module.im.controller.admin.manager.message;

import cn.hutool.core.collection.CollUtil;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.common.util.collection.MapUtils;
import com.aifuli.framework.common.util.object.BeanUtils;
import com.aifuli.module.im.controller.admin.manager.message.vo.group.ImGroupMessageManagerPageReqVO;
import com.aifuli.module.im.controller.admin.manager.message.vo.group.ImGroupMessageManagerRespVO;
import com.aifuli.module.im.dal.dataobject.group.ImGroupDO;
import com.aifuli.module.im.dal.dataobject.message.ImGroupMessageDO;
import com.aifuli.module.im.service.group.ImGroupService;
import com.aifuli.module.im.service.message.ImGroupMessageService;
import com.aifuli.module.system.api.user.AdminUserApi;
import com.aifuli.module.system.api.user.dto.AdminUserRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.common.util.collection.CollectionUtils.convertList;
import static com.aifuli.framework.common.util.collection.CollectionUtils.convertSet;
import static com.aifuli.framework.common.util.collection.CollectionUtils.convertSetByFlatMap;
import static com.aifuli.module.im.enums.ImCommonConstants.AT_USER_ID_ALL;

@Tag(name = "管理后台 - IM 群聊消息")
@RestController
@RequestMapping("/im/manager/message/group")
@Validated
public class ImGroupMessageManagerController {

    @Resource
    private ImGroupMessageService groupMessageService;
    @Resource
    private ImGroupService groupService;
    @Resource
    private AdminUserApi adminUserApi;

    @GetMapping("/page")
    @Operation(summary = "获得群聊消息分页")
    @PreAuthorize("@ss.hasPermission('im:manager:message:query')")
    public CommonResult<PageResult<ImGroupMessageManagerRespVO>> getGroupMessagePage(
            @Valid ImGroupMessageManagerPageReqVO pageReqVO) {
        // 1. 分页查询
        PageResult<ImGroupMessageDO> pageResult = groupMessageService.getGroupMessagePage(pageReqVO);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(PageResult.empty(pageResult.getTotal()));
        }
        // 2.1 批量查询群名称、发送人昵称、@ 用户昵称（-1 表示 @所有人，跳过查询，由前端判断渲染）
        Map<Long, ImGroupDO> groupMap = groupService.getGroupMap(
                convertSet(pageResult.getList(), ImGroupMessageDO::getGroupId));
        Set<Long> userIds = convertSetByFlatMap(pageResult.getList(), m -> Stream.concat(
                Stream.of(m.getSenderId()),
                CollUtil.emptyIfNull(m.getAtUserIds()).stream()
                        .filter(id -> !Objects.equals(id, AT_USER_ID_ALL))));
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(userIds);
        // 2.2 转换为 VO，填充群名 / 发送人昵称 / @ 用户昵称（-1 位置留 null，由前端展示「@所有人」）
        return success(BeanUtils.toBean(pageResult, ImGroupMessageManagerRespVO.class, vo -> {
            MapUtils.findAndThen(groupMap, vo.getGroupId(), group -> vo.setGroupName(group.getName()));
            MapUtils.findAndThen(userMap, vo.getSenderId(), user -> vo.setSenderNickname(user.getNickname()));
            if (CollUtil.isNotEmpty(vo.getAtUserIds())) {
                vo.setAtUserNicknames(convertList(vo.getAtUserIds(), id -> {
                    AdminUserRespDTO user = userMap.get(id);
                    return user != null ? user.getNickname() : null;
                }));
            }
        }));
    }

    @GetMapping("/get")
    @Operation(summary = "获得群聊消息详情")
    @Parameter(name = "id", description = "消息编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('im:manager:message:query')")
    public CommonResult<ImGroupMessageManagerRespVO> getGroupMessage(@RequestParam("id") Long id) {
        ImGroupMessageDO message = groupMessageService.getGroupMessage(id);
        return success(BeanUtils.toBean(message, ImGroupMessageManagerRespVO.class));
    }

}
