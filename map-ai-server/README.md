# 大模型标注系统部署手册（从 0 到启动）

本项目由两个子项目组成：

- `map-ai-server`：Spring Boot 后端（默认 `8080`）
- `map-ai-web`：Vue3 + Vite 前端（默认 `5173`）

前端通过 Vite 代理把 `/api` 转发到后端 `http://localhost:8080`。

---

## 1. 环境要求

请先安装以下软件：

- JDK 21
- Maven 3.9+
- Node.js 18+（建议 20 LTS）
- npm 9+
- Redis（本地或远程可访问实例）

可用以下命令检查版本：

```bash
java -version
mvn -version
node -v
npm -v
```

---

## 2. 获取代码

```bash
git clone <你的仓库地址>
cd 大模型标注4.6
```

---

## 3. 配置环境变量（macOS zsh）

编辑 `~/.zshrc`：

```bash
nano ~/.zshrc
```

加入以下变量（按你的真实值替换）：

```bash
# DashScope / 百炼
export QWEN_KEY="你的百炼API Key"

# Redis（建议改成你自己的地址）
export REDIS_HOST="127.0.0.1"
export REDIS_PORT="6379"
export REDIS_DB="0"

# 和风天气 MCP（可选：不用天气功能可不配）
export HEFENG_HOST="你的和风API域名"
export HEFENG_KEY="你的和风Key"
# 或者使用 JWT 三件套（与 HEFENG_KEY 二选一）
# export HEFENG_JWT_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
# export HEFENG_PROJECT_ID="你的project_id"
# export HEFENG_KEY_ID="你的key_id"
```

生效：

```bash
source ~/.zshrc
```

验证：

```bash
echo $QWEN_KEY
echo $REDIS_HOST
echo $REDIS_PORT
```

---

## 4. 后端配置说明

后端配置文件：`map-ai-server/src/main/resources/application.yml`

当前默认使用：

- `server.port=8080`
- Redis 地址写在该文件中（你可改成自己的 Redis）
- DashScope key 从环境变量 `QWEN_KEY` 读取

建议把 Redis 也改成环境变量占位符（更安全）：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      database: ${REDIS_DB:0}
```

---

## 5. 启动后端（map-ai-server）

```bash
cd map-ai-server
mvn spring-boot:run
```

后端健康检查：

```bash
curl http://localhost:8080/api/asr/health
```

看到成功响应表示后端已就绪。

---

## 6. 启动前端（map-ai-web）

新开一个终端窗口：

```bash
cd map-ai-web
npm install
npm run dev
```

默认访问：

- `http://localhost:5173`

说明：

- `vite.config.js` 已配置 `/api -> http://localhost:8080` 代理
- ASR WebSocket 默认连 `ws://localhost:8080/ws/asr/onlineStream`

---

## 7. 地图相关配置（前端）

在 `map-ai-web` 下创建 `.env.local`（不要提交到 Git）：

```bash
cat > .env.local <<'EOF'
GAODE_MAP_KEY=你的高德key
GAODE_MAP_SAK=你的高德安全密钥
EOF
```

然后重启前端：

```bash
npm run dev
```

---

## 8. 启动顺序（推荐）

1. 启动 Redis（确保可连通）
2. 启动后端 `map-ai-server`
3. 启动前端 `map-ai-web`
4. 打开浏览器访问 `http://localhost:5173`

---

## 9. 常见问题排查

### 1) 前端 502 / `/api/*` 报错

- 原因：后端没启动或端口不是 `8080`
- 检查：`curl http://localhost:8080/api/asr/health`

### 2) WebSocket 连接失败

- 报错：`ws://localhost:8080/ws/asr/onlineStream failed`
- 原因：后端未启动，或端口不一致

### 3) 提示 `QWEN_KEY 未配置`

- 原因：环境变量未生效
- 解决：`source ~/.zshrc` 后重启 IDE/终端再启动后端

### 4) 地图不显示或瓦片异常

- 检查 `GAODE_MAP_KEY / GAODE_MAP_SAK` 是否正确
- 检查高德控制台白名单限制

### 5) RAG/向量检索不可用

- 检查 Redis 是否可连接
- 检查 `application.yml` 中 Redis 配置是否正确

---

## 10. 生产与安全建议

- 不要把 `.env.local`、`.env`、私钥文件提交到 Git
- 只提交 `.env.example`
- 上传 GitHub 前跑一次密钥扫描（如 `gitleaks`）
- 推荐在 CI 中加入 secret scan

---

## 11. 一键快速启动（开发环境）

后端终端：

```bash
cd map-ai-server
mvn spring-boot:run
```

前端终端：

```bash
cd map-ai-web
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```
