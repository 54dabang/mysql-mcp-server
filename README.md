# Mysql-Mcp-Server

## Project Features:
    -- mcp server for Java version
    -- Support for multiple data sources
    -- STDIO 
    -- springboot starter

```bash 
  ./mvnw clean package -Dmaven.test.skip=true
```

## Docker 部署与离线交付

本项目支持通过 Docker 部署。镜像内只运行 `mysql-mcp-server` 服务，不会额外启动
MySQL；MySQL 地址、账号、密码、数据库名等信息都通过环境变量或 `.env` 文件配置。

整体逻辑如下：

```text
项目源码
  -> Dockerfile 在容器内执行 Maven 打包
  -> 生成包含 Java 21 JRE 和应用 Jar 的运行镜像
  -> docker-compose.yml 或 docker run 启动容器
  -> .env / -e 环境变量传入 MySQL 等配置
  -> Spring Boot application.yml 读取环境变量并启动服务
```

### Docker 相关文件说明

| 文件 | 作用 | 是否包含敏感信息 |
| --- | --- | --- |
| `Dockerfile` | 定义镜像如何构建，包括 Maven 打包阶段和 Java 运行阶段。 | 否 |
| `.dockerignore` | 排除不需要进入 Docker 构建上下文的文件，例如 `.git`、`target`、`.env`、日志和 tar 包。 | 否 |
| `docker-compose.yml` | 定义如何启动容器、端口映射、环境变量、日志卷和重启策略。 | 否，默认值不建议放真实生产密码 |
| `.env.example` | 环境变量模板，用于生成本机 `.env`。 | 否 |
| `.env` | 本机实际运行配置，例如 MySQL 地址、账号、密码。 | 是，不要提交到 Git |
| `src/main/resources/application.yml` | Spring Boot 配置文件，从环境变量读取端口、数据库和业务配置。 | 否，默认值不建议放真实生产密码 |

`.env` 的读取顺序是：

```text
.env
  -> docker-compose.yml 的 environment
  -> 容器内环境变量
  -> application.yml 中的 ${变量名:默认值}
  -> 应用最终使用的配置
```

例如：

```yaml
SPRING_DATASOURCE_PASSWORD: "${SPRING_DATASOURCE_PASSWORD:-oneapimmysql}"
```

含义是：如果 `.env` 或当前 Shell 环境中配置了 `SPRING_DATASOURCE_PASSWORD`，就使用配置值；
如果没有配置或值为空，就使用默认值 `oneapimmysql`。

### Docker 构建逻辑

`Dockerfile` 使用多阶段构建：

1. 构建阶段使用 `maven:3.9.9-eclipse-temurin-21`，这个镜像里有 Maven 和 JDK 21。
2. 构建阶段在容器内执行 `mvn -B -Dmaven.test.skip=true package`，生成 Spring Boot Jar。
3. 运行阶段使用 `eclipse-temurin:21-jre`，这个镜像里只有 Java 21 运行环境。
4. 最终镜像只复制构建阶段生成的 Jar 到 `/app/app.jar`。
5. 容器启动时执行 `java $JAVA_OPTS -jar /app/app.jar`。

因此最终运行镜像包含 Java 21 JRE，但不包含 Maven，也不包含完整 JDK 编译工具。部署机器只需要
Docker，不需要额外安装 Java、JDK 或 Maven。

只有以下情况通常需要重新打包镜像：

```text
修改了 Java 源码
修改了 pom.xml 依赖
修改了 Dockerfile
修改了 application.yml 中的默认配置或应用配置结构
```

以下情况不需要重新打包镜像，只需要修改 `.env` 并重启容器：

```text
修改 MySQL 地址
修改 MySQL 端口
修改 MySQL 用户名或密码
修改数据库名
修改 SERVER_PORT、JAVA_OPTS 等运行参数
```

### 本机 Docker Compose 构建运行

第一次运行：

```bash
cd /Users/leixingbang/sanxiaProject/mysql-mcp-server
cp .env.example .env
```

修改 `.env` 中的 MySQL 配置：

```env
SERVER_PORT=8083

MYSQL_HOST=host.docker.internal
MYSQL_PORT=3306
MYSQL_DATABASE=ledger
MYSQL_SERVER_TIMEZONE=Asia/Shanghai

SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=oneapimmysql
SPRING_DATASOURCE_DATABASE=ledger

CONFIG_DATABASE_READ_ROWS_LIMIT=200
JAVA_OPTS=
```

如果 MySQL 运行在 Docker 宿主机上，通常使用：

```env
MYSQL_HOST=host.docker.internal
```

如果 MySQL 运行在其他服务器或容器网络中，将 `MYSQL_HOST` 设置为对应主机名或 IP。
如果需要完全自定义 JDBC URL，可以直接配置 `SPRING_DATASOURCE_URL`，它会覆盖由
`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE` 自动拼出的连接地址。

构建镜像并启动容器：

```bash
docker compose up -d --build
```

查看容器状态和日志：

```bash
docker compose ps
docker compose logs -f mysql-mcp-server
```

代码改动后的重新打包：

```bash
docker compose up -d --build
```

完全不使用缓存重新构建：

```bash
docker compose build --no-cache
docker compose up -d
```

只修改 `.env` 中的 MySQL 等运行配置时，不需要重新构建镜像，执行：

```bash
docker compose up -d
```

### 本机手动构建镜像

如果不使用 Compose，也可以手动构建镜像：

```bash
docker build -t mysql-mcp-server:local .
```

查看镜像架构和基础信息：

```bash
docker image inspect mysql-mcp-server:local \
  --format 'image={{.RepoTags}} os={{.Os}} arch={{.Architecture}} size={{.Size}}'
```

直接运行容器：

```bash
docker run -d \
  --name mysql-mcp-server \
  -p 8083:8083 \
  -e MYSQL_HOST=host.docker.internal \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DATABASE=ledger \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=oneapimmysql \
  mysql-mcp-server:local
```

查看日志：

```bash
docker logs -f mysql-mcp-server
```

停止并删除容器：

```bash
docker stop mysql-mcp-server
docker rm mysql-mcp-server
```

### 离线交付前确认目标机器架构

离线交付时，必须保证镜像架构和目标机器 CPU 架构匹配。先在目标机器执行：

```bash
uname -m
```

常见结果对应关系：

```text
x86_64  -> linux/amd64
aarch64 -> linux/arm64
arm64   -> linux/arm64
```

如果架构不匹配，镜像可以导入成功，但容器启动时可能出现 `exec format error`。

### 本机打包离线 tar 镜像

如果目标机器是 x86_64 Linux：

```bash
docker buildx build --platform linux/amd64 -t mysql-mcp-server:offline --load .
```

如果目标机器是 arm64 / aarch64 Linux：

```bash
docker buildx build --platform linux/arm64 -t mysql-mcp-server:offline --load .
```

验证镜像架构：

```bash
docker image inspect mysql-mcp-server:offline --format '{{.Os}}/{{.Architecture}}'
```

导出 tar 包：

```bash
docker save -o mysql-mcp-server-offline.tar mysql-mcp-server:offline
```

可选：生成校验值，便于传输到目标机器后确认文件没有损坏：

```bash
shasum -a 256 mysql-mcp-server-offline.tar
```

离线交付通常需要准备：

```text
mysql-mcp-server-offline.tar
目标机器运行时使用的 MySQL 地址、端口、数据库名、用户名、密码
```

如果目标机器希望使用 Compose 管理容器，还需要准备一个不包含 `build` 配置的离线
Compose 文件，示例见下方。

### 目标机器离线导入镜像

将 `mysql-mcp-server-offline.tar` 放到目标机器后执行：

```bash
docker load -i mysql-mcp-server-offline.tar
docker images | grep mysql-mcp-server
docker image inspect mysql-mcp-server:offline --format '{{.Os}}/{{.Architecture}}'
```

如果本机生成过校验值，可以在目标机器上再次执行并对比：

```bash
sha256sum mysql-mcp-server-offline.tar
```

macOS 上常用 `shasum -a 256`，Linux 上常用 `sha256sum`。

### 目标机器离线运行方式一：docker run

`docker run` 是最直接的离线运行方式，不依赖项目源码和 `Dockerfile`。
下面命令中的 `192.168.1.100`、`change_me` 按目标环境替换为真实 MySQL 地址和密码：

```bash
docker run -d \
  --name mysql-mcp-server \
  --restart unless-stopped \
  -p 8083:8083 \
  -v mysql_mcp_logs:/app/logs \
  -e MYSQL_HOST=192.168.1.100 \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DATABASE=ledger \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=change_me \
  -e SPRING_DATASOURCE_DATABASE=ledger \
  mysql-mcp-server:offline
```

查看日志：

```bash
docker logs -f mysql-mcp-server
```

重启：

```bash
docker restart mysql-mcp-server
```

停止并删除容器：

```bash
docker stop mysql-mcp-server
docker rm mysql-mcp-server
```

### 目标机器离线运行方式二：Docker Compose

离线目标机器如果没有项目源码，不建议直接使用带 `build` 的 `docker-compose.yml`。
可以使用下面这种只引用镜像、不执行构建的 Compose 配置：

```yaml
services:
  mysql-mcp-server:
    image: mysql-mcp-server:offline
    container_name: mysql-mcp-server
    ports:
      - "${SERVER_PORT:-8083}:8083"
    environment:
      SERVER_PORT: "${SERVER_PORT:-8083}"
      MYSQL_HOST: "${MYSQL_HOST}"
      MYSQL_PORT: "${MYSQL_PORT:-3306}"
      MYSQL_DATABASE: "${MYSQL_DATABASE:-ledger}"
      MYSQL_SERVER_TIMEZONE: "${MYSQL_SERVER_TIMEZONE:-Asia/Shanghai}"
      SPRING_DATASOURCE_USERNAME: "${SPRING_DATASOURCE_USERNAME}"
      SPRING_DATASOURCE_PASSWORD: "${SPRING_DATASOURCE_PASSWORD}"
      SPRING_DATASOURCE_DATABASE: "${SPRING_DATASOURCE_DATABASE:-${MYSQL_DATABASE:-ledger}}"
      CONFIG_DATABASE_READ_ONLY_TABLES: "${CONFIG_DATABASE_READ_ONLY_TABLES:-ctg_ledger_annual_budget,ctg_ledger_project,ctg_ledger_project_expense_detail,ctg_ledger_project_user,sys_user,sys_dept}"
      CONFIG_DATABASE_READ_ROWS_LIMIT: "${CONFIG_DATABASE_READ_ROWS_LIMIT:-200}"
      LOG_FILE_NAME: "${LOG_FILE_NAME:-/app/logs/mcp-server.log}"
      JAVA_OPTS: "${JAVA_OPTS:-}"
    volumes:
      - mysql_mcp_logs:/app/logs
    restart: unless-stopped

volumes:
  mysql_mcp_logs:
```

配套 `.env` 示例：

```env
SERVER_PORT=8083

MYSQL_HOST=192.168.1.100
MYSQL_PORT=3306
MYSQL_DATABASE=ledger
MYSQL_SERVER_TIMEZONE=Asia/Shanghai

SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=change_me
SPRING_DATASOURCE_DATABASE=ledger

CONFIG_DATABASE_READ_ROWS_LIMIT=200
JAVA_OPTS=
```

启动：

```bash
docker compose up -d
docker compose ps
docker compose logs -f mysql-mcp-server
```

修改 `.env` 后重新应用配置：

```bash
docker compose up -d
```

### 离线交付完整顺序

本机：

```bash
cd /Users/leixingbang/sanxiaProject/mysql-mcp-server
docker buildx build --platform linux/amd64 -t mysql-mcp-server:offline --load .
docker image inspect mysql-mcp-server:offline --format '{{.Os}}/{{.Architecture}}'
docker save -o mysql-mcp-server-offline.tar mysql-mcp-server:offline
shasum -a 256 mysql-mcp-server-offline.tar
```

目标机器。下面命令中的 `192.168.1.100`、`change_me` 按目标环境替换：

```bash
docker load -i mysql-mcp-server-offline.tar
docker image inspect mysql-mcp-server:offline --format '{{.Os}}/{{.Architecture}}'
docker run -d \
  --name mysql-mcp-server \
  --restart unless-stopped \
  -p 8083:8083 \
  -v mysql_mcp_logs:/app/logs \
  -e MYSQL_HOST=192.168.1.100 \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DATABASE=ledger \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=change_me \
  mysql-mcp-server:offline
docker logs -f mysql-mcp-server
```

```json
    {
      "mcpServers": {
        "mysql-mcp-server": {
          "command": "java",
          "args": [
            "-Dspring.datasource.url= *",
            "-Dspring.datasource.username= *",
            "-Dspring.datasource.password= *",
            "-jar",
            "/absolute/path/mysql-mcp-server-0.0.1-SNAPSHOT.jar"
          ]
        }
      }
    }
```
