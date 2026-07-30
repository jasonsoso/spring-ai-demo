# AgentScope Dev Agent 沙箱镜像：Java17 + Maven + Python（供 edit_file）
# 构建请用：docker compose -f demo2/docker/sandbox/docker-compose.yml build
# 为何预跑 mvn test：运行时 network=none，构建期先把依赖拉进本地仓库缓存层。
# maven.test.failure.ignore=true：样例 RetryPolicy 故意失败，只为下载依赖。
# python3-wrapper：兼容 AgentScope 2.0.0 edit_file 的 \n 字面量，
# 并在 Windows/docker 丢掉 -c 引号时把拆散的 argv 拼回。

FROM maven:3.9.11-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

COPY docker/sandbox/python3-wrapper /usr/local/bin/python3
RUN chmod +x /usr/local/bin/python3

WORKDIR /opt/dependency-cache

COPY workspace/project/pom.xml pom.xml
COPY workspace/project/src src

RUN mvn -q -Dmaven.test.failure.ignore=true test

RUN rm -rf /opt/dependency-cache

WORKDIR /workspace
