# 多阶段构建：Maven 编译打包 → JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/waste-oil-tracking-1.0.0.jar app.jar
EXPOSE 8080
# 数据源与 Kafka 地址由 docker-compose 通过环境变量注入（容器网络服务名）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
