FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="supply-chain-server" \
      org.opencontainers.image.description="供应链中台测试环境运行镜像"

WORKDIR /app

# 固定数值身份，避免运行时依赖镜像中的用户名或 root 权限；见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#9-docker-镜像设计。
# --create-home: Nacos 客户端需要 ${user.home}/nacos/config/ 写入本地配置快照。
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin app

COPY --chown=10001:10001 target/aifuli-module-supply-chain-server.jar /app/app.jar

# 镜像提供可移植的 JVM 基线；部署环境可用 JAVA_OPTS 整串覆盖。见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#91-构建。
ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

USER 10001:10001

EXPOSE 58190

# 不依赖 Actuator；仅确认 JVM 正在监听既定 RPC 端口；见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#9-docker-镜像设计。
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 CMD bash -c '</dev/tcp/127.0.0.1/58190' || exit 1

# 启动参数只由受控环境变量提供；见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#9-docker-镜像设计。
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
