# Hedwig

血糖数据 API 网关服务，用于把国内 CGM 厂商的数据统一采集并同步到 [Nightscout](http://www.nightscout.info/)。

目前支持：

- 欧泰 (Ottai)
- 硅基轻享 (SiSensing)
- 微泰 (MicroTech / AiDEX X)

各渠道差异：

| 渠道 | 接入方式 | 数据节奏 | 默认轮询 |
| --- | --- | --- | --- |
| 欧泰 | 微信小程序抓包粘贴 access token | 5 分钟 | 5 分钟 |
| 硅基轻享 | 账号密码直连，或粘贴 access token | 5 分钟 | 5 分钟 |
| 微泰（AiDEX X） | 分享/关注（网关登录被分享账号） | 1 分钟 | 1 分钟（可在仪表盘调整） |

> 微泰接口的逆向结论见 [doc/微泰/微泰-AiDEX-API逆向分析.md](doc/微泰/微泰-AiDEX-API逆向分析.md)。
>
> ⚠️ 微泰是**单会话**账号体系：同一账号登录会顶掉上一个会话（接口返回 `802`），
> 而 AiDEX X 的数据要靠手机 App 蓝牙采集上传，主账号被顶下线就没有新数据了。
>
> ✅ 因此微泰渠道采用**双账号（分享/关注）**接入：
> 1. 在主账号的 App 里把数据分享给第二个微泰账号（一次即可）；
> 2. 在 Hedwig「连接设备 → 微泰」里填**被分享账号**的手机号与密码；
> 3. 网关用第二个账号登录并带 `userId=<主账号>` 读取数据，手机主账号不受影响。
>
> 接口细节见文档 §4.4。
>
> ⏱ 延迟参考：微泰手机端上传本身有 13~42s 抖动，Hedwig 轮询再加 1~14s，
> **端到端典型 30~35s、最坏约 1 分钟**；不丢数据（唯一键 + 断连回填），只是可能晚到。详见文档 §6.4。

## 架构概览

单体发布模式，适合国内 Ubuntu 服务器部署：

- 前端在构建时直接打进 Spring Boot `jar`，单端口提供 API 和前端页面
- 使用 PostgreSQL 数据库
- 支持多个 Nightscout 推送目标（按用户管理，配置存储在数据库中）
- 自适应轮询：根据 CGM 厂商数据上报节奏动态调整采集频率
- 不依赖 Docker、Nginx

推荐流程：本地构建 → 上传发布包 → 服务器运行

## 项目结构

```text
hedwig/
├── backend/                  # Spring Boot 3.2.5 后端 (MyBatis-Plus + FastJSON2)
├── frontend-react/           # React 18 + TypeScript + Ant Design 5 前端 (Vite)
├── build/                    # 本地构建产物 (hedwig.jar)，git 跟踪，CI 直接部署
├── scripts/                  # 构建、启动、systemd 安装脚本
├── release/                  # 完整发布包输出目录
└── doc/                      # 厂商 API 逆向分析文档
```

## 本地开发

### 环境要求

- Java 17+
- Maven 3.6+
- Node.js 18+
- npm 9+
- PostgreSQL（默认连接 `localhost:15432/hedwig`）

### 启动后端

```bash
cd backend
JWT_SECRET=dev-only-secret-change-me mvn spring-boot:run
```

后端默认端口 `3000`（由 `application.yml` 中 `server.port` 决定）。

### 启动前端

```bash
cd frontend-react
npm install
npm run dev
```

前端默认访问 `http://localhost:5173`，并把 `/api/` 代理到 `http://127.0.0.1:3000`。

## 构建与部署

采用**本地构建 + CI 部署**模式：本地执行构建，产物 `build/hedwig.jar` 提交到 git，Woodpecker CI 只负责 SCP 部署到服务器。这样避免了低配服务器上构建 OOM 的问题。

注意：当前 CI 不会在服务器上重新构建源码，它只会上传仓库里的 `build/hedwig.jar`。
如果你改了 `backend/` 或 `frontend-react/` 但没有重新生成并提交新的 `build/hedwig.jar`，流水线以前会“部署成功但线上代码不变”；现在 CI 会直接失败提醒。
同样，`deploy/`、`scripts/run-http.sh`、`scripts/install-systemd.sh` 这类运行时文件目前不会被自动部署到服务器。

### 工作流

```
本地开发 → ./scripts/build-and-stage.sh → git add build/ → git push
                                                              │
                            Gitea ──Webhook──► Woodpecker (仅部署)
                                                  │ SCP build/hedwig.jar
                                                  │ systemctl restart hedwig
                                                  ▼
                                               阿里云服务器
```

### 一键构建并推送

```bash
# 方式一：使用 git alias（构建 + 暂存 + 推送一步到位）
git bd

# 方式二：手动执行
./scripts/build-and-stage.sh
git push
```

`build-and-stage.sh` 会依次执行：前端 `npm ci && npm run build` → 后端 `mvn clean package` → 拷贝 jar 到 `build/hedwig.jar` → 自动 `git add` 当前工作区里准备发布的源码改动和 `build/hedwig.jar`。
Woodpecker 在部署前会校验当前提交是否遗漏了 `build/hedwig.jar`。

### 完整发布包（可选）

如果需要生成可上传的独立发布包（包含启动脚本、环境变量模板等）：

```bash
./scripts/build-release.sh

# 跳过测试
SKIP_TESTS=1 ./scripts/build-release.sh
```

产出 `release/hedwig/` 目录和 `release/hedwig-YYYYMMDD-HHMMSS.tar.gz` 压缩包。

## 服务器部署

服务器上通过 systemd 管理 Hedwig 服务。CI Pipeline 会自动将 `build/hedwig.jar` SCP 到服务器并重启服务，同时输出本地和远端 jar 的 SHA-256，方便确认这次上线的确是新产物。

### 首次部署

1. 将发布包上传到服务器并解压到 `/opt/hedwig/`
2. 配置环境变量：`cp app.env.example app.env && vim app.env`
3. 安装 systemd 服务：`./install-systemd.sh`
4. 如果通过 Woodpecker 的 `deploy` 用户自动发布，需要给它最小化的免密 sudo 权限，否则 CI 无法重启服务：

```bash
command -v systemctl
sudo visudo -f /etc/sudoers.d/hedwig-deploy
```

把实际输出路径代入后填入，例如很多 Ubuntu 是 `/usr/bin/systemctl`：

```text
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart hedwig, /usr/bin/systemctl is-active hedwig
```

然后校验：

```bash
sudo visudo -cf /etc/sudoers.d/hedwig-deploy
```

### 常用命令

```bash
sudo systemctl status hedwig     # 查看状态
sudo systemctl restart hedwig    # 重启
sudo systemctl stop hedwig       # 停止
sudo journalctl -u hedwig -f     # 查看日志
```

## 关键环境变量

以下变量在 `app.env` 中配置（或通过系统环境变量覆盖）：

| 变量             | 默认值                                          | 说明                                  |
|------------------|------------------------------------------------|---------------------------------------|
| `SERVER_ADDRESS` | `0.0.0.0`                                      | 监听地址                              |
| `SERVER_PORT`    | `8080`（脚本启动） / `3000`（直接运行 jar）       | HTTP 端口                             |
| `TZ`             | `Asia/Shanghai`                                | 时区                                  |
| `JWT_SECRET`     | 自动生成                                        | JWT 签名密钥，**生产环境务必修改**     |
| `JWT_EXPIRATION` | `86400000`                                     | JWT 过期时间（毫秒），默认 24 小时     |
| `APP_DATA_DIR`   | `./data`                                       | 数据存储目录                          |
| `JAVA_OPTS`      | `-Xms256m -Xmx512m`                           | JVM 参数                              |
| `DB_URL`         | `jdbc:postgresql://localhost:15432/hedwig`      | PostgreSQL JDBC 连接地址              |
| `DB_USERNAME`    | `postgres`                                     | 数据库用户名                          |
| `DB_PASSWORD`    | `postgres`                                     | 数据库密码                            |

## API 接口

### 认证

| 方法 | 路径                   | 说明     | 认证 |
|------|------------------------|----------|------|
| POST | `/api/auth/register`   | 用户注册 | 否   |
| POST | `/api/auth/login`      | 用户登录 | 否   |

### 设备连接管理

| 方法   | 路径                                     | 说明                 |
|--------|------------------------------------------|----------------------|
| GET    | `/api/vendors/connections`               | 获取设备连接列表     |
| POST   | `/api/vendors/connections/token`         | 通过 Token 连接设备  |
| POST   | `/api/vendors/connections/login`         | 通过账号密码连接设备 |
| POST   | `/api/vendors/connections/{id}/refresh`  | 刷新设备 Token       |
| PATCH  | `/api/vendors/connections/{id}/polling-interval` | 调整轮询间隔（秒），`{"seconds": null}` 恢复厂商默认值 |
| DELETE | `/api/vendors/connections/{id}`          | 断开设备连接         |

### 血糖数据

| 方法 | 路径                                                                    | 说明                     |
|------|-------------------------------------------------------------------------|--------------------------|
| GET  | `/api/glucose/subjects/{subjectId}/readings`                            | 查询血糖历史记录         |
| GET  | `/api/glucose/subjects/{subjectId}/readings/latest`                     | 查询最新血糖数据         |
| POST | `/api/glucose/connections/{connId}/subjects/{subjectId}/fetch/latest`   | 从厂商实时拉取最新数据   |
| POST | `/api/glucose/connections/{connId}/subjects/{subjectId}/fetch/history`  | 从厂商拉取近 24h 历史    |
| POST | `/api/glucose/subjects/{subjectId}/sync`                                | 同步最近历史记录         |

### Nightscout 管理

| 方法   | 路径                                | 说明                         |
|--------|-------------------------------------|------------------------------|
| GET    | `/api/nightscout/targets`           | 获取 Nightscout 推送目标列表 |
| GET    | `/api/nightscout/targets/{id}`      | 获取单个推送目标详情         |
| POST   | `/api/nightscout/targets`           | 创建推送目标                 |
| PUT    | `/api/nightscout/targets/{id}`      | 修改推送目标                 |
| DELETE | `/api/nightscout/targets/{id}`      | 删除推送目标                 |
| POST   | `/api/nightscout/targets/{id}/toggle` | 启用/停用推送目标          |
| POST   | `/api/nightscout/sync`              | 手动触发同步到 Nightscout    |

### 系统状态

| 方法 | 路径           | 说明                                       |
|------|----------------|--------------------------------------------|
| GET  | `/api/status`  | 系统状态（连接数、血糖条数、Nightscout 等） |

## License

MIT
