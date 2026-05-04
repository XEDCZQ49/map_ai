<template>
  <div className="voice_chat">
    <div v-if="!onReco" className="voice_chat_wrapper">
      <div className="voice_chat_btn" @click="startRecorder()"></div>
      <div className="voice_chat_btn_title">点击开始聊天</div>
      <div className="voice_chat_btn_prompt">聊天前请允许浏览器获取麦克风权限</div>
    </div>

    <div v-else className="voice_chat_dialog_wrapper">
      <div className="dialog_box">
        <ul className="dialog_content">
          <li id="speech_list">
            <div className="dialog_content_img_pp"></div>
            <div className="dialog_content_dialogue_pp">{{ nlpResult }}</div>
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
import { submitMsg } from '../../../api/ApiLLM';

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
      asrResult: '',
      nlpResult: '',
      textareaResultRunse: '',
      onUserinput: false,
      ws: null,
      speakingText: '正在等待您说话...',
      recordTimer: null,
      asrFinalText: '',
      asrInterimText: '',
    };
  },
  mounted() {
    this.ws = new WebSocket(apiURL.ASR_SOCKET_RECORD);
    this.ws.addEventListener('error', () => {
      this.$message.error('ASR websocket 连接异常');
    });
    this.ws.addEventListener('message', (event) => {
      let text = '';
      let isFinal = false;
      try {
        const temp = JSON.parse(event.data);
        if (temp.error) {
          this.$message.error(temp.error);
          return;
        }
        text = temp.result || '';
        isFinal = !!temp.final;
      } catch (e) {
        text = event.data || '';
      }
      if (!text || this.isStatusMessage(text)) return;
      if (!this.onUserinput) {
        if (isFinal) {
          this.asrFinalText = this.mergeAsrText(this.asrFinalText, text);
          this.asrInterimText = '';
        } else {
          this.asrInterimText = text;
        }
        const merged = (this.asrFinalText + ' ' + this.asrInterimText).trim();
        this.textareaResultRunse = merged;
        this.asrResult = merged;
      }
    });
  },
  beforeUnmount() {
    this.stopChunkTimer();
  },
  methods: {
    stopChunkTimer() {
      if (!this.recordTimer) return;
      clearInterval(this.recordTimer);
      this.recordTimer = null;
    },
    startChunkTimer() {
      this.stopChunkTimer();
      this.recordTimer = setInterval(() => {
        const newData = safeGetNextData();
        if (!newData.length) return;
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
        // started
      }, () => {
        this.stopChunkTimer();
        this.$message.error('录音启动失败，请检查麦克风权限');
      });
    },
    handleKeydown(event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        if (this.textareaResultRunse.trim()) {
          this.send2NLP();
        }
      }
    },
    turnRecorder() {
      if (this.onUserinput) {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
          this.$message.error('ASR websocket 未连接，无法切回语音输入');
          return;
        }
        this.onUserinput = false;
        this.asrResult = '正在通过语音输入...';
        this.ws.send(JSON.stringify({ name: 'test.wav', nbest: 5, signal: 'start' }));
        this.stopRecorderSafely();
        this.asrFinalText = '';
        this.asrInterimText = '';
        this.startVoiceCapture();
        this.textareaResultRunse = '';
      } else {
        this.onUserinput = true;
        this.asrResult = '正在通过键盘输入...';
        this.stopChunkTimer();
        this.stopRecorderSafely();
        safeRecorderClear();
      }
    },
    async send2NLP() {
      const message = (this.textareaResultRunse || '').trim();
      if (!message) return;

      this.asrResult = message;
      try {
        const result = await submitMsg(message, { scene: 'wenshu_gen' });
        const ok = this.isSubmitSuccess(result);
        this.nlpResult = ok ? '发送成功（文书拟制消息已提交）' : `发送失败：${result?.message || 'unknown error'}`;
      } catch (e) {
        this.nlpResult = '发送失败：network error';
      }

      this.textareaResultRunse = '';
      this.asrFinalText = '';
      this.asrInterimText = '';
    },
    startRecorder() {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        this.$message.error('websocket 链接失败，请检查 Websocket 后端服务是否正确开启');
        return;
      }
      if (!this.onReco) {
        this.asrFinalText = '';
        this.asrInterimText = '';
        this.asrResult = this.speakingText;
        this.ws.send(JSON.stringify({ name: 'test.wav', nbest: 5, signal: 'start' }));
        this.startVoiceCapture();
        this.onReco = true;
        this.nlpResult = '文书拟制会话已开启';
      } else {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.ws.send(JSON.stringify({ name: 'test.wav', nbest: 5, signal: 'end' }));
        }
        this.stopChunkTimer();
        safeRecorderClear();
        this.stopRecorderSafely();
        this.onReco = false;
        this.asrResult = '';
        this.textareaResultRunse = '';
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
      return text.includes('流式识别已开始') ||
        text.includes('识别中...已接收') ||
        text.includes('流式结束，接收分片') ||
        text.includes('connected');
    },
    mergeAsrText(current, segment) {
      const a = (current || '').trim();
      const b = (segment || '').trim();
      if (!a) return b;
      if (!b) return a;
      if (a.endsWith(b)) return a;
      if (b.startsWith(a)) return b;
      return `${a} ${b}`.trim();
    },
    isSubmitSuccess(result) {
      if (!result) return false;
      if (result.success === true) return true;
      if (result.code === 0 || result.code === 200) return true;
      if (typeof result.message === 'string' && result.message.toLowerCase() === 'success') return true;
      return !result.error;
    },
  },
};
</script>

<style lang="less" scoped>
@import "./style.less";
</style>
