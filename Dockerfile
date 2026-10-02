# 城市餐厨废油回收追踪服务镜像：多阶段构建
# 构建：docker build -t waste-oil-tracking:1.0.0 .
# 一键运行整套：docker compose up -d --build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先复制 pom 预热依赖缓存
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && chown -R app:app /app
USER app
COPY --from=build --chown=app:app /build/target/waste-oil-tracking-1.0.0.jar app.jar
EXPOSE 8080
# 容器存活探活（temurin 基础镜像自带 bash）
HEALTHCHECK --interval=10s --timeout=3s --retries=15 --start-period=40s \
    CMD bash -c 'exec 3<>/dev/tcp/localhost/8080' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
