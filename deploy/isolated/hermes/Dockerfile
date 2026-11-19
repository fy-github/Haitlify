# Hermes 执行单元镜像（在官方 Hermes 镜像上加一层 TCP↔stdio 桥）。
#
# 基础镜像不放进仓库：由操作者显式提供（HAITLIFY_ISOLATED_HERMES_IMAGE），
# 构建/拉取方式见 deploy/isolated/README.md「Hermes 执行单元」。
# 这样仓库里不含 Hermes 源码，也不会在构建时静默拉一个会漂移的 tag。
ARG HERMES_BASE_IMAGE
FROM ${HERMES_BASE_IMAGE}

USER 0
COPY --chmod=0755 bridge.py /opt/hermes-bridge.py

# 只留最小运行时目录：状态目录单独用命名卷挂载，其余根文件系统只读。
ENV HERMES_HOME=/state \
    HERMES_BRIDGE_HOST=0.0.0.0 \
    HERMES_BRIDGE_PORT=9420 \
    PYTHONUNBUFFERED=1

USER 10001:10001
EXPOSE 9420
ENTRYPOINT ["python3", "/opt/hermes-bridge.py"]
