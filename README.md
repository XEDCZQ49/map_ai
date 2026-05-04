# 大模型地图标注系统

这是一个“地图态势 + 大模型指挥辅助”的前后端一体项目，支持自然语言/语音输入，完成地图部署、图形标绘、环境分析、知识检索和文书拟制等业务流程。

## 项目结构

```text
大模型标注4.6/
├── map-ai-server/   # Spring Boot 后端（LLM编排、ASR、RAG、计划管理、MCP工具）
├── map-ai-web/      # Vue3 前端（地图展示、交互、标绘、部署、知识库与配置界面）
└── README.md        # 当前文件（仓库级说明）
```

## 功能概览

- 环境分析：对输入内容进行分阶段分析展示
- 要图标绘：将自然语言解析为结构化绘图/部署命令并落图
- 一键部署：批量命令切分、识别、执行
- 文书拟制：基于任务上下文生成文档内容
- 情报查询：RAG 知识库上传、检索、问答
- 智能配置：在线查看/编辑提示词节点并热更新
- 战场方案：`plan_id` 维度保存/加载/切换/删除地图态势

## 技术栈

- 前端：Vue 3、Vite、Element Plus、Ant Design Vue、Cesium
- 后端：Spring Boot 3、Spring AI Alibaba、Redis、DashScope SDK
- 能力集成：ASR WebSocket、RAG 向量检索、MCP（天气/地理等工具）

## 文档说明

- 前端启动与配置：请查看 `map-ai-web` 目录下说明（如需可补充独立 README）
- 后端启动与配置：请查看 [map-ai-server/README.md](map-ai-server/README.md)

本 README 仅描述项目定位与结构，不重复前后端的详细启动步骤。

