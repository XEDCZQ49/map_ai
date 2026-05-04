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
          @keydown="handleKeydown"
        ></textarea>
      </div>

      <div className="button-container">
        <div className="btn_select_area" @click="selectArea()">
          <span></span>
          <span>选中地区</span>
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
  </div>
</template>

<script>
import { apiURL } from '../../../api/API';
import Recorder from 'js-audio-recorder';
import { envAnalysisStreamSubmit } from '../../../api/ApiLLM';

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

      isSelectingArea: false,
      isMouseSelecting: false,
      selectedAreaText: '',
      selectedArea: null,
      selectionStart: null,
      selectionEnd: null,
      cesiumHandler: null,
      selectionEntity: null,

      envStageTitles: [],
      envStageDetails: [],
      envFinalMarkdown: '',
      processCollapsed: false,
      isAnalyzing: false,
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
        this.textareaResultRunse = this.composeFinalInputText(merged);
        this.asrResult = merged;
      }
    });
  },
  beforeUnmount() {
    this.stopChunkTimer();
    this.stopAreaSelection();
    this.clearSelection();
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
        if (this.textareaResultRunse.trim()) this.send2NLP();
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
      } else {
        this.onUserinput = true;
        this.asrResult = '正在通过键盘输入...';
        this.stopChunkTimer();
        this.stopRecorderSafely();
        safeRecorderClear();
      }
    },

    async send2NLP() {
      const rawInput = (this.textareaResultRunse || '').trim();
      const message = this.composeFinalInputText(rawInput).trim();
      if (!message) return;

      this.asrResult = message;
      this.envStageTitles = [];
      this.envStageDetails = [];
      this.envFinalMarkdown = '';
      this.isAnalyzing = true;
      this.processCollapsed = false;
      this.renderEnvOutput();

      try {
        await envAnalysisStreamSubmit(message, { scene: 'env_analysis' }, ({ event, data }) => {
          if (event === 'stage') {
            this.consumeStageEvent(data);
            return;
          }
          if (event === 'final') {
            this.envFinalMarkdown = data?.reply || '分析完成，但无结果文本';
            this.isAnalyzing = false;
            this.processCollapsed = true;
            this.renderEnvOutput();
            return;
          }
          if (event === 'error') {
            const err = data?.detail || data?.message || 'stream failed';
            this.envStageDetails.push(`错误：${err}`);
            this.isAnalyzing = false;
            this.processCollapsed = false;
            this.renderEnvOutput();
          }
        });
      } catch (e) {
        this.envStageDetails.push(`错误：${e?.message || 'network error'}`);
        this.isAnalyzing = false;
        this.processCollapsed = false;
        this.renderEnvOutput();
      }

      this.textareaResultRunse = '';
      this.asrFinalText = '';
      this.asrInterimText = '';
      this.clearSelection();
    },

    consumeStageEvent(data) {
      const status = data?.status || '';
      let message = data?.message || '';
      if (!message) return;
      // 去掉后端的步骤序号前缀，前端只展示语义文本。
      message = message.replace(/^步骤\d+\/\d+：/, '').trim();
      message = this.normalizeCoordinateInMessage(message);
      if (status === 'start') {
        this.envStageTitles.push(message);
      } else {
        this.envStageDetails.push(message);
      }
      this.renderEnvOutput();
    },

    normalizeCoordinateInMessage(message) {
      return message.replace(/(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)/g, (_, lon, lat) => {
        return `${Number(lon).toFixed(2)},${Number(lat).toFixed(2)}`;
      });
    },

    renderEnvOutput() {
      const processHtml = this.renderProcessHtml();
      const resultMd = this.envFinalMarkdown || '';
      const resultHtml = resultMd ? this.markdownToHtml(resultMd) : '';
      this.nlpResult = `${processHtml}${resultHtml ? '<hr/>' + resultHtml : ''}`;
    },

    renderProcessHtml() {
      const titleItems = this.envStageTitles.map((t) => `<div class="env-stage-title">${this.escapeHtml(t)}</div>`).join('');
      const detailItems = this.envStageDetails.map((d) => `<div class="env-stage-sub">${this.escapeHtml(d)}</div>`).join('');
      const body = `${titleItems}${detailItems || (this.isAnalyzing ? '<div class="env-stage-sub">正在等待阶段信息...</div>' : '')}`;
      const openAttr = this.processCollapsed ? '' : ' open';
      return `
        <details class="env-process-box"${openAttr}>
          <summary><strong>分析过程</strong></summary>
          <div class="env-process-body">${body}</div>
        </details>
      `;
    },

    escapeHtml(s) {
      return (s || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    },

    markdownToHtml(md) {
      const escapeHtml = (s) => s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

      let html = escapeHtml(md || '');
      html = html.replace(/^###\s+(.+)$/gm, '<h3>$1</h3>');
      html = html.replace(/^##\s+(.+)$/gm, '<h2>$1</h2>');
      html = html.replace(/^#\s+(.+)$/gm, '<h1>$1</h1>');
      html = html.replace(/^\-\s+(.+)$/gm, '<li>$1</li>');
      html = html.replace(/(<li>.*<\/li>\n?)+/g, (m) => `<ul>${m}</ul>`);
      html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
      html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
      html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
      html = html.replace(/^---$/gm, '<hr/>');
      html = html.replace(/\n\n/g, '</p><p>');
      html = `<p>${html}</p>`;
      html = html.replace(/<p>\s*<h([1-3])>/g, '<h$1>').replace(/<\/h([1-3])>\s*<\/p>/g, '</h$1>');
      html = html.replace(/<p>\s*<ul>/g, '<ul>').replace(/<\/ul>\s*<\/p>/g, '</ul>');
      html = html.replace(/<p>\s*<hr\/>\s*<\/p>/g, '<hr/>');
      return html;
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
        this.envStageTitles = [];
        this.envStageDetails = [];
        this.envFinalMarkdown = '';
        this.isAnalyzing = false;
        this.processCollapsed = false;
        this.nlpResult = '环境分析会话已开始';
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
        this.clearSelection();
        this.stopAreaSelection();
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

    selectArea() {
      if (this.isSelectingArea) this.stopAreaSelection();
      else this.startAreaSelection();
    },
    startAreaSelection() {
      this.clearSelection();
      this.isSelectingArea = true;
      this.isMouseSelecting = false;
      this.disableMapDrag();
      this.addMapEventListeners();
      this.nlpResult = '请在地图上按住鼠标左键拖拽选择区域';
    },
    stopAreaSelection() {
      this.isSelectingArea = false;
      this.isMouseSelecting = false;
      this.enableMapDrag();
      this.removeMapEventListeners();
    },
    disableMapDrag() {
      if (!window.CESIUM_VIEWER) return;
      const ctrl = window.CESIUM_VIEWER.scene.screenSpaceCameraController;
      ctrl.enableRotate = false;
      ctrl.enableTranslate = false;
      ctrl.enableZoom = false;
      ctrl.enableTilt = false;
      ctrl.enableLook = false;
    },
    enableMapDrag() {
      if (!window.CESIUM_VIEWER) return;
      const ctrl = window.CESIUM_VIEWER.scene.screenSpaceCameraController;
      ctrl.enableRotate = true;
      ctrl.enableTranslate = true;
      ctrl.enableZoom = true;
      ctrl.enableTilt = true;
      ctrl.enableLook = true;
    },
    addMapEventListeners() {
      if (!window.CESIUM_VIEWER) return;
      if (this.cesiumHandler) {
        this.cesiumHandler.destroy();
        this.cesiumHandler = null;
      }
      this.cesiumHandler = new Cesium.ScreenSpaceEventHandler(window.CESIUM_VIEWER.scene.canvas);
      this.cesiumHandler.setInputAction((movement) => this.handleCesiumMouseDown(movement), Cesium.ScreenSpaceEventType.LEFT_DOWN);
      this.cesiumHandler.setInputAction((movement) => this.handleCesiumMouseMove(movement), Cesium.ScreenSpaceEventType.MOUSE_MOVE);
      this.cesiumHandler.setInputAction((movement) => this.handleCesiumMouseUp(movement), Cesium.ScreenSpaceEventType.LEFT_UP);
    },
    removeMapEventListeners() {
      if (!this.cesiumHandler) return;
      this.cesiumHandler.destroy();
      this.cesiumHandler = null;
    },
    handleCesiumMouseDown(movement) {
      if (!this.isSelectingArea || !movement?.position) return;
      const start = this.pixelToLatLng(movement.position.x, movement.position.y);
      if (!start) return;
      this.isMouseSelecting = true;
      this.selectionStart = { x: movement.position.x, y: movement.position.y, lat: start.lat, lng: start.lng };
      this.selectionEnd = { ...this.selectionStart };
      this.ensureSelectionEntity();
      this.requestRender();
    },
    handleCesiumMouseMove(movement) {
      if (!this.isSelectingArea || !this.isMouseSelecting || !movement?.endPosition) return;
      const end = this.pixelToLatLng(movement.endPosition.x, movement.endPosition.y);
      if (!end) return;
      this.selectionEnd = { x: movement.endPosition.x, y: movement.endPosition.y, lat: end.lat, lng: end.lng };
      this.requestRender();
    },
    handleCesiumMouseUp(movement) {
      if (!this.isSelectingArea || !this.isMouseSelecting || !movement?.position) return;
      const end = this.pixelToLatLng(movement.position.x, movement.position.y);
      if (end) this.selectionEnd = { x: movement.position.x, y: movement.position.y, lat: end.lat, lng: end.lng };
      this.completeSelection();
    },
    ensureSelectionEntity() {
      if (!window.CESIUM_VIEWER || this.selectionEntity) return;
      this.selectionEntity = window.CESIUM_VIEWER.entities.add({
        rectangle: {
          coordinates: new Cesium.CallbackProperty(() => {
            if (!this.selectionStart || !this.selectionEnd) return undefined;
            const minLat = Math.min(this.selectionStart.lat, this.selectionEnd.lat);
            const maxLat = Math.max(this.selectionStart.lat, this.selectionEnd.lat);
            const minLng = Math.min(this.selectionStart.lng, this.selectionEnd.lng);
            const maxLng = Math.max(this.selectionStart.lng, this.selectionEnd.lng);
            return Cesium.Rectangle.fromDegrees(minLng, minLat, maxLng, maxLat);
          }, false),
          material: Cesium.Color.fromCssColorString('#2932E1').withAlpha(0.35),
          outline: true,
          outlineColor: Cesium.Color.fromCssColorString('#2932E1'),
          outlineWidth: 2,
          height: 0,
        },
        name: `selected-area-${Date.now()}`,
      });
    },
    completeSelection() {
      if (!this.selectionStart || !this.selectionEnd) {
        this.isMouseSelecting = false;
        return;
      }
      const minLat = Math.min(this.selectionStart.lat, this.selectionEnd.lat);
      const maxLat = Math.max(this.selectionStart.lat, this.selectionEnd.lat);
      const minLng = Math.min(this.selectionStart.lng, this.selectionEnd.lng);
      const maxLng = Math.max(this.selectionStart.lng, this.selectionEnd.lng);
      this.isMouseSelecting = false;

      if (maxLat - minLat < 0.0005 || maxLng - minLng < 0.0005) {
        this.nlpResult = '选区过小，请重新拖拽';
        this.clearSelection();
        this.stopAreaSelection();
        return;
      }

      this.selectedArea = { minLat, maxLat, minLng, maxLng };
      this.displaySelectionResult(minLat, maxLat, minLng, maxLng);
      this.stopAreaSelection();
      this.requestRender();
    },
    pixelToLatLng(x, y) {
      if (!window.CESIUM_VIEWER) return null;
      const cartesian = window.CESIUM_VIEWER.camera.pickEllipsoid(
        new Cesium.Cartesian2(x, y),
        window.CESIUM_VIEWER.scene.globe.ellipsoid
      );
      if (!cartesian) return null;
      const cartographic = Cesium.Cartographic.fromCartesian(cartesian);
      return { lat: Cesium.Math.toDegrees(cartographic.latitude), lng: Cesium.Math.toDegrees(cartographic.longitude) };
    },
    displaySelectionResult(minLat, maxLat, minLng, maxLng) {
      const formatLatLng = (lat, lng) => `${lat >= 0 ? '北纬' : '南纬'}${Math.abs(lat).toFixed(4)}° ${lng >= 0 ? '东经' : '西经'}${Math.abs(lng).toFixed(4)}°`;
      const start = formatLatLng(minLat, minLng);
      const end = formatLatLng(maxLat, maxLng);
      this.selectedAreaText = `选中地区：${start} 至 ${end}`;
      this.textareaResultRunse = this.composeFinalInputText((this.textareaResultRunse || '').replace(this.selectedAreaText, '').trim());
      this.nlpResult = '已完成区域选择，可发送分析请求';
    },
    clearSelection() {
      this.selectionStart = null;
      this.selectionEnd = null;
      this.selectedArea = null;
      if (this.selectionEntity && window.CESIUM_VIEWER) {
        try { window.CESIUM_VIEWER.entities.remove(this.selectionEntity); } catch (e) {}
      }
      this.selectionEntity = null;
      if (this.selectedAreaText) {
        this.textareaResultRunse = (this.textareaResultRunse || '').replace(this.selectedAreaText, '').trim();
      }
      this.selectedAreaText = '';
      this.requestRender();
    },
    requestRender() {
      if (window.CESIUM_VIEWER?.scene) window.CESIUM_VIEWER.scene.requestRender();
    },

    composeFinalInputText(rawText) {
      const body = (rawText || '').replace(this.selectedAreaText, '').trim();
      if (this.selectedAreaText && body) return `${this.selectedAreaText}\n${body}`;
      if (this.selectedAreaText) return this.selectedAreaText;
      return body;
    },
    isStatusMessage(text) {
      return text.includes('流式识别已开始') || text.includes('识别中...已接收') || text.includes('流式结束，接收分片') || text.includes('connected');
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
  },
};
</script>

<style lang="less" scoped>
@import "./style.less";
</style>
