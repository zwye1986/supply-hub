package com.aifuli.framework.apilog.core.util;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 敏感请求的安全异常因果定位：只输出异常类型与栈帧，不读取message/局部变量/请求内容。
 * 同时保留cause与suppressed，防循环并使用集中技术上限，避免异常图放大日志。
 * 仅登记的直充限频管理路径调用，其他进程和路径沿用原契约。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
public final class SensitiveExceptionLogUtils {
    private static final int MAX_CAUSAL_NODES=32;
    private static final int MAX_STACK_FRAMES=200;
    private SensitiveExceptionLogUtils() { }

    /** 白名单异常类名；不调用Throwable.toString或读取原报文。 */
    public static String types(Throwable ex) {
        return causalNodes(ex).stream().map(current->current.getClass().getName()).collect(Collectors.joining(" caused/suppressed "));
    }
    /** 白名单类名和栈帧，保留受控故障与业务拒绝的安全因果定位。 */
    public static String stack(Throwable ex) {
        var result=new StringBuilder(); int remaining=MAX_STACK_FRAMES;
        for(Throwable current:causalNodes(ex)) {
            result.append(current.getClass().getName()).append('\n');
            for(var frame:current.getStackTrace()) {
                if(remaining<=0) {break;}
                result.append("\tat ").append(frame).append('\n'); remaining--;
            }
        }
        if(remaining==0) {result.append("[stack frame limit]\n");}
        return result.toString();
    }
    private static List<Throwable> causalNodes(Throwable ex) {
        if(ex==null) {return List.of();}
        var result=new ArrayList<Throwable>(); var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var pending=new ArrayDeque<Throwable>();pending.add(ex);
        while(!pending.isEmpty() && result.size()<MAX_CAUSAL_NODES) {
            var current=pending.removeFirst();if(!seen.add(current)){continue;}
            result.add(current);
            if(current.getCause()!=null) {pending.addLast(current.getCause());}
            // 队列同样有技术上限，避免大量suppressed导致无界内存。
            for(var suppressed:current.getSuppressed()) {
                if(pending.size()>=MAX_CAUSAL_NODES) {break;}
                pending.addLast(suppressed);
            }
        }
        return result;
    }
}
