// 前端统一接口地址常量，避免在业务组件中硬编码路径。
export const apiURL = {
  // LLM
  LLM_GRAPH_RUN: '/api/llm/graph/run',
  LLM_SPLIT_CMD: '/api/llm/split_cmd',
  LLM_SPLIT_CMD_STREAM: '/api/llm/split_cmd_stream',
  LLM_PLAN_CREATE: '/api/llm/plan/create',
  LLM_PLAN_SAVE: '/api/llm/plan/save',
  LLM_PLAN_RELEASE: '/api/llm/plan/release',
  LLM_PLAN_DELETE: '/api/llm/plan/delete',
  LLM_PLAN_LIST: '/api/llm/plan/list',
  LLM_PLAN_LOAD: '/api/llm/plan/load',
  LLM_SUBMIT_MSG: '/api/llm/submit_msg',
  LLM_ENV_ANALYSIS: '/api/llm/envanalysis',
  LLM_ENV_ANALYSIS_STREAM: '/api/llm/envanalysis/stream',

  // websocket ASR（实时）
  ASR_SOCKET_RECORD: 'ws://localhost:8080/ws/asr/onlineStream',
}
