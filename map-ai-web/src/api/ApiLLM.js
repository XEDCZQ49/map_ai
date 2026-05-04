import { apiURL } from "./API.js";

// 统一 JSON POST 封装：兼容非 JSON 响应并返回稳定错误结构。
async function safeJsonPost(url, payload, fallback = {}) {
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const rawText = await response.text();
    let data = {};
    try {
      data = rawText ? JSON.parse(rawText) : {};
    } catch (e) {
      data = { message: rawText || response.statusText || "non-json response" };
    }
    if (!response.ok) {
      return {
        code: response.status,
        message: data?.message || response.statusText || "request failed",
        error: true,
        ...data,
      };
    }
    return data;
  } catch (e) {
    return { message: "error", error: String(e), ...fallback };
  }
}

// 执行单条图流程解析（Judge/Recognition/Chat）。
export async function graphRun(text, extra = {}) {
  return safeJsonPost(apiURL.LLM_GRAPH_RUN, { message: text, ...extra });
}

// 一次性执行“切分+并发识别”并返回聚合结果（非流式）。
export async function splitCmdRun(text, extra = {}) {
  return safeJsonPost(apiURL.LLM_SPLIT_CMD, { message: text, ...extra });
}

export async function createPlan() {
  return safeJsonPost(apiURL.LLM_PLAN_CREATE, {});
}

export async function listPlans() {
  try {
    const response = await fetch(apiURL.LLM_PLAN_LIST)
    const rawText = await response.text()
    const data = rawText ? JSON.parse(rawText) : {}
    if (!response.ok) {
      return {
        code: response.status,
        message: data?.message || response.statusText || 'request failed',
        error: true,
        ...data
      }
    }
    return data
  } catch (e) {
    return { message: 'error', error: String(e), plans: [] }
  }
}

export async function savePlan(planId, commands = [], planName = '') {
  return safeJsonPost(apiURL.LLM_PLAN_SAVE, {
    planId,
    planName,
    commands
  });
}

export async function releasePlan(planId) {
  return safeJsonPost(apiURL.LLM_PLAN_RELEASE, { planId });
}

export async function deletePlan(planId) {
  return safeJsonPost(apiURL.LLM_PLAN_DELETE, { planId })
}

export async function loadPlan(planId) {
  try {
    const response = await fetch(`${apiURL.LLM_PLAN_LOAD}?plan_id=${encodeURIComponent(planId)}`)
    const rawText = await response.text()
    const data = rawText ? JSON.parse(rawText) : {}
    if (!response.ok) {
      return {
        code: response.status,
        message: data?.message || response.statusText || 'request failed',
        error: true,
        ...data
      }
    }
    return data
  } catch (e) {
    return { message: 'error', error: String(e), commands: [] }
  }
}

// 提交批量指令并按 SSE 逐条接收解析事件。
export async function splitCmdStreamSubmit(text, extra = {}, onEvent = () => {}) {
  const response = await fetch(apiURL.LLM_SPLIT_CMD_STREAM, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ message: text, ...extra }),
  });
  if (!response.ok || !response.body) {
    let errText = "";
    try {
      errText = await response.text();
    } catch (e) {
      errText = response.statusText || "stream failed";
    }
    throw new Error(errText || `HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  const emitFrame = (frame) => {
    const lines = frame.split("\n");
    let event = "message";
    const dataLines = [];
    lines.forEach((line) => {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
    });
    if (!dataLines.length) return;
    const dataRaw = dataLines.join("\n");
    let data = dataRaw;
    try {
      data = JSON.parse(dataRaw);
    } catch (e) {
      // keep raw string
    }
    onEvent({ event, data });
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split("\n\n");
    buffer = frames.pop() || "";
    frames.forEach((f) => emitFrame(f.trim()));
  }
  if (buffer.trim()) emitFrame(buffer.trim());
}

// 通用消息提交测试接口
export async function submitMsg(text, extra = {}) {
  return safeJsonPost(apiURL.LLM_SUBMIT_MSG, { message: text, ...extra });
}

// 环境分析非流式接口（返回完整结果）。
export async function envAnalysisSubmit(text, extra = {}) {
  return safeJsonPost(apiURL.LLM_ENV_ANALYSIS, { message: text, ...extra });
}

// 环境分析流式接口：实时回调阶段事件与最终结果。
export async function envAnalysisStreamSubmit(text, extra = {}, onEvent = () => {}) {
  const response = await fetch(apiURL.LLM_ENV_ANALYSIS_STREAM, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ message: text, ...extra }),
  });
  if (!response.ok || !response.body) {
    let errText = "";
    try {
      errText = await response.text();
    } catch (e) {
      errText = response.statusText || "stream failed";
    }
    throw new Error(errText || `HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  const emitFrame = (frame) => {
    const lines = frame.split("\n");
    let event = "message";
    const dataLines = [];
    lines.forEach((line) => {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
    });
    if (!dataLines.length) return;
    const dataRaw = dataLines.join("\n");
    let data = dataRaw;
    try {
      data = JSON.parse(dataRaw);
    } catch (e) {
      // keep raw string
    }
    onEvent({ event, data });
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split("\n\n");
    buffer = frames.pop() || "";
    frames.forEach((f) => emitFrame(f.trim()));
  }
  if (buffer.trim()) emitFrame(buffer.trim());
}
