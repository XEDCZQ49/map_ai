# map-ai-web 前端启动手册

本目录是前端项目，基于 Vue3 + Vite + Cesium，用于地图展示、要图标绘、环境分析、文书拟制、情报查询、智能配置等功能。

## 1. 环境要求

- Node.js 18+（建议 20 LTS）
- npm 9+

检查版本：

```bash
node -v
npm -v
```

## 2. 进入前端目录

```bash
cd map-ai-web
```

## 3. 安装依赖

```bash
npm install
```

## 4. 配置前端环境变量

在 `map-ai-web` 目录创建 `.env.local`：

```bash
cat > .env.local <<'EOF'
GAODE_MAP_KEY=你的高德Key
GAODE_MAP_SAK=你的高德安全密钥
EOF
```

说明：

- 项目使用 `GAODE_MAP_KEY` / `GAODE_MAP_SAK` 加载底图。
- 这两个变量属于前端可见配置，请在高德平台配置白名单限制来源。

## 5. 启动开发服务器

```bash
npm run dev
```

默认访问地址：

- [http://localhost:5173](http://localhost:5173)

## 6. 与后端联调说明

前端通过 Vite 代理把 `/api` 转发到后端：

- 代理目标：`http://localhost:8080`
- 配置文件：`map-ai-web/vite.config.js`

因此你需要先启动后端 `map-ai-server`，否则前端调用会出现 502。

## 7. 语音功能说明

实时语音识别 WebSocket 默认连接：

- `ws://localhost:8080/ws/asr/onlineStream`

如果后端端口变化，需要同步修改：

- `src/api/API.js` 中 `ASR_SOCKET_RECORD`

## 8. 构建生产包

```bash
npm run build
```

构建产物目录：

- `map-ai-web/dist`

本地预览：

```bash
npm run preview
```

## 9. 常见问题

### 1) 页面请求 502 Bad Gateway

- 原因：后端未启动或不在 `8080`
- 处理：先启动后端，再刷新前端

### 2) 地图底图不显示

- 检查 `.env.local` 的高德 key 是否正确
- 检查高德控制台是否配置来源白名单

### 3) WebSocket 连接失败

- 确认后端已启动并暴露 `/ws/asr/onlineStream`
- 确认前端连接地址与后端端口一致

### 4) npm install 失败

- 检查 Node 版本（建议 20 LTS）
- 可删除 `node_modules` 和 `package-lock.json` 后重装

```bash
rm -rf node_modules package-lock.json
npm install
```

