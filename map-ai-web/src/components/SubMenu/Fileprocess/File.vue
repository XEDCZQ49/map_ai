<template>
  <div class="file-upload-container">
    <h2>文件上传与处理</h2>

    <div class="upload-section">
      <input
        id="fileInput"
        ref="fileInput"
        type="file"
        accept=".txt,.md,.json,.csv"
        style="display: none"
        @change="handleFileUpload"
      >
      <button class="upload-btn" @click="loadcommand">加载测试命令</button>
      <span v-if="selectedFile" class="file-name">{{ selectedFile.name }}</span>
      <button class="upload-btn upload-btn-right" @click="triggerFileInput">上传文件</button>
    </div>

    <!-- 文件内容显示区域 -->
    <div class="content-section">
      <h3>文件内容：</h3>
      <pre class="file-content">{{ fileContent }}</pre>
    </div>

    <div class="state-section">
      <h3>状态：</h3>
      <pre class="file-content">{{ stateInfo }}</pre>
    </div>
    <!-- 绘制按钮 -->
    <div class="action-section">
      <button
        class="draw-btn"
        @click="handleDraw"
        :disabled="!fileContent || isDrawing"
      >
        {{ isDrawing ? '绘制中...' : '绘制图形' }}
      </button>
    </div>

<!--    &lt;!&ndash; 状态提示 &ndash;&gt;-->
<!--    <div class="status-message" :class="{ 'error': errorMessage }">-->
<!--      {{ statusMessage || errorMessage }}-->
<!--    </div>-->
  </div>
</template>

<script>
import {mapActions, mapState} from 'vuex';
import {splitCmdStreamSubmit} from "../../../api/ApiLLM.js";
import testCommandsText from './test.txt?raw'

export default {
  name: 'FileUpload',
  data() {
    return {
      selectedFile: null,
      fileContent: '',
      stateInfo: '',
      isDrawing: false,
      statusMessage: '',
      errorMessage: ''
    }
  },
  computed:{
    ...mapState(['error']),
  },

  watch: {
    error: {
      handler(newError) {
        if(newError == 'finish'){
          this.stateInfo = '绘制完成。'
        }else{
          console.error(`错误来源: ${newError.source}`, newError.message);
          this.stateInfo = '模型返回结果出错，请重试...error:'+newError.message;
        // 可根据错误来源定制 UI 反馈
        }
      },
      deep: true,  // 深度监听对象变化
    },
  },

  methods: {
    ...mapActions(['updateSharedObject']),

    appendState(text) {
      this.stateInfo = this.stateInfo ? `${this.stateInfo}\n${text}` : text
    },

    // 触发文件选择对话框
    triggerFileInput() {
      this.$refs.fileInput.click()
    },
    loadcommand() {
      // 直接读取 Fileprocess/test.txt，按文件内换行作为命令分隔展示。
      this.fileContent = testCommandsText.trim()
      this.stateInfo = '已加载 test.txt 测试命令。'
    },
    // async processResultData(resultList) {
    //   // for (let i = 0; i < resultList.length; i++) {
    //   //   const command = resultList[i];
    //   //   console.log(command)
    //   //   console.log(typeof(command))
    //   //   const result = await nlpChat("该指令可能是："+command.classification+"，内容如下："+command.message);
    //   //   console.log("指令执行结果:", result);
    //   //   await this.updateSharedObject(result);
    //   // }
    //   await this.updateSharedObject(resultList);
    // },

    // 处理文件上传
    handleFileUpload(event) {
      const file = event.target.files[0]
      if (!file) return

      this.selectedFile = file
      this.errorMessage = ''
      this.statusMessage = '正在读取文件...'

      // 使用FileReader读取文件内容
      const reader = new FileReader()
      reader.onload = (e) => {
        this.fileContent = e.target.result
        this.statusMessage = '文件读取完成'
        this.stateInfo = '等待部署...'
        setTimeout(() => {
          this.statusMessage = ''
        }, 2000)
      }
      reader.onerror = () => {
        this.errorMessage = '文件读取失败'
      }
      reader.readAsText(file)
    },

    // 调用后端绘制接口
    async handleDraw() {
      if (!this.fileContent) {
        this.errorMessage = '请先上传文件'
        return
      }
      // const resultList = this.fileContent
      // console.log(resultList)
      // await this.processResultData(resultList)
      this.isDrawing = true
      this.errorMessage = ''
      this.statusMessage = '正在调用绘制接口...'
      this.stateInfo = ''
      this.appendState('开始切分并部署指令...')

      try {
        let execChain = Promise.resolve()
        let streamError = null
        await splitCmdStreamSubmit(this.fileContent, {}, ({ event, data }) => {
          if (event === 'start') {
            this.appendState(`已切分指令：${data?.split_count || 0} 条`)
            return
          }
          if (event === 'item') {
            const commands = Array.isArray(data?.commands) ? data.commands : []
            execChain = execChain.then(async () => {
              commands.forEach((cmd) => this.appendState(`部署指令：${cmd?.function_name || 'unknown'}`))
              if (commands.length) {
                await this.updateSharedObject({ commands })
                await new Promise((resolve) => setTimeout(resolve, 80))
              }
            })
            return
          }
          if (event === 'error') {
            streamError = data?.message || '流式部署失败'
            return
          }
          if (event === 'done') {
            this.appendState(`部署完成，共执行 ${data?.merged_count || 0} 条动作。`)
          }
        })
        await execChain
        if (streamError) {
          throw new Error(streamError)
        }

      } catch (error) {
        this.errorMessage = `绘制失败: ${error.message}`
      } finally {
        this.isDrawing = false
      }
    },

  }
}
</script>

<style scoped>
.file-upload-container {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  margin: 0 auto;
  padding: 12px;
  font-family: Arial, sans-serif;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  min-width: 0;
  overflow: hidden;
}

h2 {
  color: #333;
  text-align: center;
  margin: 0 0 8px 0;
  flex: 0 0 auto;
}

.upload-section {
  margin: 8px 0;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 30px;
  width: 100%;
  flex: 0 0 auto;
}

.upload-btn-right {
  margin-left: auto;
}

.content-section {
  margin: 8px 0;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 8px;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 1 1 auto;
  min-height: 0;
}

.state-section {
  margin: 8px 0;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 8px;
  height: 140px;
  min-height: 120px;
  max-height: 35%;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 0 0 auto;
}

.content-section h3 {
  margin: 0 0 6px 0;
  flex: 0 0 auto;
}

.file-content {
  white-space: pre-wrap;
  word-wrap: break-word;
  background-color: #f9f9f9;
  padding: 8px;
  border-radius: 4px;
  height: auto;
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  width: 100%;
  box-sizing: border-box;
}

.action-section {
  margin: 8px 0 0 0;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  width: 100%;
  flex: 0 0 auto;
}

.status-message {
  margin-top: 1%;
  padding: 1%;
  border-radius: 4px;
  background-color: #e6f7ff;
  color: #1890ff;
  height: 8%;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
}

.status-message.error {
  background-color: #fff2f0;
  color: #f5222d;
}

.upload-btn, .draw-btn {
  padding: 1% 2%;
  background-color: #4CAF50;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 16px;
  transition: background-color 0.3s;
  height: 100%;
  box-sizing: border-box;
}

.upload-btn:hover, .draw-btn:hover {
  background-color: #45a049;
}

.draw-btn:disabled {
  background-color: #cccccc;
  cursor: not-allowed;
}

.file-name {
  color: #666;
  font-style: italic;
  height: 100%;
  display: flex;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 50%;
}
</style>
