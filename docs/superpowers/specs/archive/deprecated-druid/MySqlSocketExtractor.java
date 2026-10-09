package com.aifuli.module.supplychain.infrastructure.configuration;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.net.Socket;
import java.sql.Connection;

/**
 * 依据 R02 规范：提取 Connector/J 底层物理连接与实际 Socket 引用。
 * 沿 physical.getSession().getProtocol().getSocketConnection().getMysqlSocket() 公开 API 链获取，
 * 不访问 Druid protected holder.socket，不修改驱动或池内部。
 */
@Slf4j
public final class MySqlSocketExtractor {

    private MySqlSocketExtractor() {}

    public static Socket extractSocket(Connection physical) {
        if (physical == null) {
            return null;
        }
        try {
            if (physical.isWrapperFor(Connection.class)) {
                physical = physical.unwrap(Connection.class);
            }
        } catch (Throwable ignored) {}
        if (!"com.mysql.cj.jdbc.ConnectionImpl".equals(physical.getClass().getName())) {
            return null;
        }
        try {
            Method getSession = physical.getClass().getMethod("getSession");
            Object session = getSession.invoke(physical);
            if (session == null) {
                return null;
            }
            Method getProtocol = session.getClass().getMethod("getProtocol");
            Object protocol = getProtocol.invoke(session);
            if (protocol == null) {
                return null;
            }
            Method getSocketConnection = protocol.getClass().getMethod("getSocketConnection");
            Object socketConn = getSocketConnection.invoke(protocol);
            if (socketConn == null) {
                return null;
            }
            Method getMysqlSocket = socketConn.getClass().getMethod("getMysqlSocket");
            return (Socket) getMysqlSocket.invoke(socketConn);
        } catch (Throwable e) {
            log.warn("提取 Connector/J Socket 异常: {}", e.getMessage());
            return null;
        }
    }

    public static void abortInternal(Connection physical) {
        if (physical == null) {
            return;
        }
        try {
            if (physical.isWrapperFor(Connection.class)) {
                physical = physical.unwrap(Connection.class);
            }
        } catch (Throwable ignored) {}
        try {
            Method m = physical.getClass().getMethod("abortInternal");
            m.invoke(physical);
        } catch (Throwable t) {
            try {
                physical.abort(Runnable::run);
            } catch (Throwable ignored) {}
        }
    }
}
