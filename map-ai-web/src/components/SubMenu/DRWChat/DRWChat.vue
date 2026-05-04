<template>
  <div className="voice_chat">
    <!-- 开始聊天 -->
    <div v-if="!onReco" className="voice_chat_wrapper">
      <div className="voice_chat_btn" @click="startRecorder()"></div>
      <div className="voice_chat_btn_title">点击开始聊天</div>
      <div className="voice_chat_btn_prompt">聊天前请允许浏览器获取麦克风权限</div>
    </div>
    <!-- 结束聊天 -->
    <div v-else className="voice_chat_dialog_wrapper">
      <div className="dialog_box">
        <ul className="dialog_content">
          <li id="speech_list">
            <div className="dialog_content_img_pp"></div>
            <div className="dialog_content_dialogue_pp markdown-body" v-html="nlpResult"></div>
          </li>
          <li id="speech_list" className="move_dialogue">
            <div className="dialog_content_dialogue_user">{{ asrResult }}</div>
            <div className="dialog_content_img_user"></div>
          </li>
        </ul>
      </div>
      <div class="public_recognition_result">
        <textarea
          v-model="textareaResultRunse"
          rows="1"
          placeholder="输入消息，按 Enter 发送，Shift+Enter 换行"
          @focus="handleFocus"
          @blur="handleBlur"
          @keydown="handleKeydown"
        ></textarea>
      </div>
      <div className="btn_record_dialog" @click="turnRecorder()">
        <span></span>
        <span v-if="onUserinput">键盘输入</span>
        <span v-else>语音输入</span>
      </div>
      <div className="btn_send_dialog" @click="send2NLP()">
        <span></span>
        <span>发送</span>
      </div>
      <div className="btn_end_dialog" @click="startRecorder()">
        <span></span>
        <span>结束聊天</span>
      </div>
    </div>
  </div>
</template>

<script>
import { apiURL } from '../../../api/API';
import Recorder from 'js-audio-recorder';
import { graphRun } from '../../../api/ApiLLM';
import { mapActions } from 'vuex';

const recorder = new Recorder({
  sampleBits: 16,
  sampleRate: 16000,
  numChannels: 1,
  compiling: true,
});

let recorderChunkCursor = 0;
const safeRecorderClear = () => {
  recorderChunkCursor = 0;
  if (Array.isArray(recorder.lBuffer)) recorder.lBuffer.length = 0;
  if (Array.isArray(recorder.rBuffer)) recorder.rBuffer.length = 0;
  recorder.size = 0;
  recorder.fileSize = 0;
  recorder.duration = 0;
};
const safeGetNextData = () => {
  const chunks = [];
  const lBuffer = recorder.lBuffer || [];
  const rBuffer = recorder.rBuffer || [];
  while (recorderChunkCursor < lBuffer.length) {
    const left = lBuffer[recorderChunkCursor];
    const right = rBuffer[recorderChunkCursor] || left;
    const pcmView = recorder.transformIntoPCM(left, right);
    chunks.push(new Uint8Array(pcmView.buffer));
    recorderChunkCursor += 1;
  }
  return chunks;
};

export default {
  data() {
    return {
      onReco: false,
      asrResult: "",
      nlpResult: "",
      textareaResultRunse: "",
      onUserinput: false,
      ws: "",
      speakingText: "正在等待您说话...",
      recordTimer: null,
      asrFinalText: "",
      asrInterimText: "",
      drawStageTitles: [],
      drawStageDetails: [],
      drawFinalText: "",
      drawProcessing: false,
      processCollapsed: false,
    };
  },
  mounted() {
    this.wsUrl = apiURL.ASR_SOCKET_RECORD;
    this.ws = new WebSocket(this.wsUrl);
    this.ws.addEventListener('error', () => {
      this.$message.error("ASR websocket 连接异常");
    });
    var _that = this;
    this.ws.addEventListener('message', function (event) {
      let text = "";
      let isFinal = false;
      try {
        const temp = JSON.parse(event.data);
        if (temp.error) {
          _that.asrResult = "ASR错误: " + temp.error;
          _that.$message.error(temp.error);
          return;
        }
        text = temp.result || "";
        isFinal = !!temp.final;
      } catch (e) {
        text = event.data || "";
      }
      if (!text) return;
      if (_that.isStatusMessage(text)) return;
      if (!_that.onUserinput) {
        if (isFinal) {
          _that.asrFinalText = _that.mergeAsrText(_that.asrFinalText, text);
          _that.asrInterimText = "";
        } else {
          _that.asrInterimText = text;
        }
        const merged = (_that.asrFinalText + " " + _that.asrInterimText).trim();
        _that.textareaResultRunse = merged;
        _that.asrResult = merged;
      }
      _that.$nextTick(() => {});
    });
  },
  beforeUnmount() {
    this.stopChunkTimer();
  },
  methods: {
    ...mapActions(['updateSharedObject', 'setCurrentPlanId']),
    waitDeployResult(requestId, timeoutMs = 8000) {
      return new Promise((resolve) => {
        let done = false;
        const onResult = (evt) => {
          const detail = evt?.detail || {};
          if (detail?.requestId !== requestId) return;
          if (done) return;
          done = true;
          window.removeEventListener('map-deploy-result', onResult);
          clearTimeout(timer);
          resolve(detail);
        };
        const timer = setTimeout(() => {
          if (done) return;
          done = true;
          window.removeEventListener('map-deploy-result', onResult);
          resolve({ requestId, timeout: true, successCount: 0, failedCount: 0, errors: ['部署回执超时'] });
        }, timeoutMs);
        window.addEventListener('map-deploy-result', onResult);
      });
    },
    stopChunkTimer() {
      if (!this.recordTimer) return;
      clearInterval(this.recordTimer);
      this.recordTimer = null;
    },
    startChunkTimer() {
      this.stopChunkTimer();
      this.recordTimer = setInterval(() => {
        let newData = safeGetNextData();
        if (!newData.length) {
          return;
        }
        this.uploadChunk(newData);
      }, 100);
    },
    stopRecorderSafely() {
      try {
        recorder.stop();
      } catch (e) {
        // ignore
      }
    },
    startVoiceCapture() {
      safeRecorderClear();
      this.startChunkTimer();
      recorder.start().then(() => {
        console.log('[ChatT] 录音器已重启，语音采集恢复');
      }, () => {
        this.stopChunkTimer();
        this.$message.error('录音启动失败，请检查麦克风权限');
      });
    },
    handleFocus(event) {
      console.log('[ChatT] 输入框获得焦点，已切换到文本编辑状态');
    },
    handleBlur(event) {
      console.log('[ChatT] 输入框失去焦点');
    },
    handleKeydown(event) {
      // Enter 发送，Shift+Enter 换行
      if (event.key === 'Enter') {
        if (event.shiftKey) {
          // Shift+Enter，允许换行，默认行为无需阻止
          return;
        } else {
          // 仅 Enter，触发发送
          event.preventDefault(); // 阻止默认换行
          if (this.textareaResultRunse.trim()) { // 确保非空
            console.log('[ChatT] 检测到 Enter，准备发送文本消息');
            this.send2NLP();
          }
        }
      }
    },
    turnRecorder() {
      if (this.onUserinput) {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
          this.$message.error("ASR websocket 未连接，无法切回语音输入");
          return;
        }
        this.onUserinput = false;
        this.asrResult = '正在通过语音输入...';
        var start = JSON.stringify({ name: "test.wav", "nbest": 5, signal: "start" });
        console.log('[ChatT] 切换为语音输入，发送 ASR start 信号');
        this.ws.send(start);
        this.stopRecorderSafely();
        this.asrFinalText = "";
        this.asrInterimText = "";
        this.startVoiceCapture();
        console.log('[ChatT] 切回语音输入，已重建录音采集');
        this.textareaResultRunse = "";
      } else {
        this.onUserinput = true;
        this.asrResult = '正在通过键盘输入...';
        // 切到键盘模式只停止本地采集，不结束后端 ASR 会话，避免切回语音时被“提前 end”打断。
        this.stopChunkTimer();
        this.stopRecorderSafely();
        safeRecorderClear();
        console.log('[ChatT] 切换为键盘输入，录音器已停止');
      }
    },
    async send2NLP() {
      const msg = (this.textareaResultRunse || "").trim();
      if (!msg) return;
      console.log('[ChatT] 发送文本消息到 Graph，字符数:', msg.length);
      this.drawStageTitles = [];
      this.drawStageDetails = [];
      this.drawFinalText = '';
      this.drawProcessing = true;
      this.processCollapsed = false;
      this.drawStageTitles.push('指令判断分类');
      this.drawStageDetails.push('正在识别输入类型...');
      this.renderDrawOutput();
      try {
        const planId = this.$store?.state?.currentPlanId || '';
        const result = await graphRun(msg, { planId });
        console.log('[ChatT] 收到后端 graph/run 原始响应:', result);
        if (typeof result?.planId === 'string' && result.planId.trim()) {
          await this.setCurrentPlanId(result.planId.trim());
        }
        const intent = result?.intent || "chat";
        this.drawStageDetails = [`分类结果：${intent}`];
        this.drawStageTitles.push('指令标注信息');
        this.renderDrawOutput();
        if (intent === 'instruction' && Array.isArray(result?.commands)) {
          const executable = result.commands.filter((cmd) => cmd?.message === 'success');
          const commandSummary = executable.length
            ? executable.map((cmd, index) => this.formatCommandLine(cmd, index)).join('\n')
            : '无可执行命令';
          this.drawStageDetails.push(commandSummary);
          this.drawStageTitles.push('部署结果');
          this.renderDrawOutput();
          console.log('[ChatT] instruction 指令总数:', result.commands.length);
          console.log('[ChatT] 可执行指令列表:', executable);
          if (!executable.length) {
            this.drawFinalText = '部署失败：无可执行命令';
          } else {
            const requestId = `drw_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
            const deployResultPromise = this.waitDeployResult(requestId);
            // 将结构化指令推送给地图执行器（MapDisplay 监听 store 执行）
            await this.updateSharedObject({ requestId, commands: executable });
            console.log('[ChatT] 已下发到地图执行器 payload:', { requestId, commands: executable });
            const deployResult = await deployResultPromise;
            console.log('[ChatT] 地图执行回执:', deployResult);
            if (!deployResult.timeout && Number(deployResult.failedCount || 0) === 0 && Number(deployResult.successCount || 0) > 0) {
              this.drawFinalText = '部署成功';
            } else {
              const reason = Array.isArray(deployResult.errors) && deployResult.errors.length
                ? deployResult.errors.join('；')
                : '地图执行失败';
              this.drawFinalText = `部署失败：${reason}`;
            }
          }
        } else if (intent === 'instruction') {
          this.drawStageDetails.push('解析结果缺少 commands 字段');
          this.drawStageTitles.push('部署结果');
          this.drawFinalText = '部署失败：无可执行命令';
        } else {
          this.drawStageDetails.push('非部署类指令，无需标注命令');
          this.drawStageTitles.push('部署结果');
          this.drawFinalText = '无需部署';
        }
        this.drawProcessing = false;
        this.processCollapsed = true;
        this.renderDrawOutput();
      } catch (e) {
        console.error('[ChatT] graph/run 调用失败:', e);
        this.drawStageTitles.push('部署结果');
        this.drawFinalText = `部署失败：${e?.message || '判定失败'}`;
        this.drawProcessing = false;
        this.processCollapsed = false;
        this.renderDrawOutput();
      }
      this.textareaResultRunse = "";
      this.asrResult = "";
      this.asrFinalText = "";
      this.asrInterimText = "";
    },
    startRecorder() {
      if (this.ws.readyState !== this.ws.OPEN) {
        this.$message.error("websocket 链接失败，请检查 Websocket 后端服务是否正确开启");
        return;
      }
      if (!this.onReco) {
        this.asrFinalText = "";
        this.asrInterimText = "";
        this.asrResult = this.speakingText;
        var start = JSON.stringify({ name: "test.wav", "nbest": 5, signal: "start" });
        console.log('[ChatT] 开始会话，发送 ASR start 信号并启动录音');
        this.ws.send(start);
        this.startVoiceCapture();
        this.onReco = true;
      } else {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.ws.send(JSON.stringify({ name: "test.wav", nbest: 5, signal: "end" }));
        }
        this.stopChunkTimer();
        safeRecorderClear();
        this.stopRecorderSafely();
        console.log('[ChatT] 已结束会话，录音器停止并清理缓存');
        this.onReco = false;
        this.asrResult = "";
        this.textareaResultRunse = "";
      }
    },
    uploadChunk(chunkDatas) {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      chunkDatas.forEach((chunkData) => {
        if (ArrayBuffer.isView(chunkData)) {
          const bytes = chunkData.buffer.slice(chunkData.byteOffset, chunkData.byteOffset + chunkData.byteLength);
          this.ws.send(bytes);
          return;
        }
        this.ws.send(chunkData);
      });
    },
    isStatusMessage(text) {
      return text.includes("流式识别已开始") ||
        text.includes("识别中...已接收") ||
        text.includes("流式结束，接收分片") ||
        text.includes("connected");
    },
    mergeAsrText(current, segment) {
      const a = (current || "").trim();
      const b = (segment || "").trim();
      if (!a) return b;
      if (!b) return a;
      if (a.endsWith(b)) return a;
      if (b.startsWith(a)) return b;
      return `${a} ${b}`.trim();
    },
    formatCommandLine(command, index) {
      const fn = command?.function_name || 'unknown';
      const color = command?.color || 'red';
      const args = command?.arguments ? JSON.stringify(command.arguments) : '{}';
      return `${index + 1}. ${fn} color=${color} args=${args}`;
    },
    renderDrawOutput() {
      const processHtml = this.renderProcessHtml();
      const resultHtml = this.drawFinalText
        ? `<p><strong>${this.escapeHtml(this.drawFinalText)}</strong></p>`
        : '';
      this.nlpResult = `${processHtml}${resultHtml ? '<hr/>' + resultHtml : ''}`;
    },
    renderProcessHtml() {
      const titleItems = this.drawStageTitles.map((t) => `<div class="env-stage-title">${this.escapeHtml(t)}</div>`).join('');
      const detailItems = this.drawStageDetails.map((d) => `<div class="env-stage-sub">${this.escapeHtml(d)}</div>`).join('');
      const body = `${titleItems}${detailItems || (this.drawProcessing ? '<div class="env-stage-sub">正在处理...</div>' : '')}`;
      const openAttr = this.processCollapsed ? '' : ' open';
      return `
        <details class="env-process-box"${openAttr}>
          <summary><strong>要图标绘过程</strong></summary>
          <div class="env-process-body">${body}</div>
        </details>
      `;
    },
    escapeHtml(s) {
      return (s || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\n/g, '<br/>');
    },
  },
};
</script>

<style lang="less" scoped>
@import "./style.less";
</style>
