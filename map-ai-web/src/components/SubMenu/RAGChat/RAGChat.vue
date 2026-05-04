<template>
  <div ref="containerRef" class="rag-layout">
    <el-aside class="left-pane" :style="{ width: `${asideWidth}px` }">
      <div class="title">知识库查询</div>

      <div class="block">
        <el-select
          v-model="selectedKb"
          placeholder="选择知识库"
          style="width: 100%;"
          :fit-input-width="true"
          @change="onKbChanged"
        >
          <el-option :value="NEW_KB_VALUE" label="+ 新建知识库" />
          <el-option
            v-for="item in kbOptions"
            :key="item.value"
            :value="item.value"
          >
            <div class="kb-option">
              <span class="kb-name">{{ item.label }}</span>
              <el-button
                type="danger"
                text
                size="small"
                @mousedown.stop.prevent
                @click.stop="removeKb(item)"
              >
                删除
              </el-button>
            </div>
          </el-option>
        </el-select>
      </div>

      <div class="block">
        <input
          ref="fileInput"
          type="file"
          multiple
          accept=".txt,.md,.pdf,.docx,.doc,.csv,.xlsx,.json"
          style="display: none"
          @change="onFileChange"
        />
        <el-button size="small" @click="triggerUpload">选择上传文件</el-button>
      </div>

      <div class="support-tip">支持：.txt .md .pdf .docx .doc .csv .xlsx .json</div>

      <div class="file-title">当前知识库文件</div>
      <div class="file-list">
        <div v-if="!selectedKb || selectedKb === NEW_KB_VALUE" class="file-empty">请先选择知识库</div>
        <div v-else-if="kbFiles.length === 0" class="file-empty">当前知识库暂无文件</div>
        <div v-else>
          <div v-for="item in kbFiles" :key="item.file_name" class="file-item">
            <div class="file-row">
              <div class="file-name">{{ item.file_name }}</div>
              <div class="file-actions">
                <el-button text size="small" type="danger" @click="deleteFile(item.file_name)">删除</el-button>
              </div>
            </div>
            <div class="file-meta">{{ item.file_size || '-' }} · {{ item.status || '-' }}</div>
          </div>
        </div>
      </div>
    </el-aside>

    <div class="pane-divider" @mousedown.stop.prevent="startResize"></div>

    <div class="right-pane">
      <el-main class="main-pane">
        <div class="messages-wrap">
          <el-card v-for="(message, index) in messages" :key="index" style="margin-bottom: 10px;">
            <div :style="{ textAlign: message.role === 'user' ? 'right' : 'left' }">
              <strong>{{ message.role === 'user' ? '用户' : '系统' }}:</strong>
              <pre style="white-space: pre-wrap; margin: 6px 0 0;">{{ message.content }}</pre>
            </div>
          </el-card>
        </div>

        <div class="input-row">
          <el-input
            v-model="inputText"
            placeholder="请输入情报问题"
            style="flex: 1;"
            @keyup.enter.native="sendMessage"
          />
          <el-button type="primary" :loading="sending" style="margin-left: 10px;" @click="sendMessage">发送</el-button>
        </div>
      </el-main>
    </div>
  </div>
</template>

<script>
import {
  ragChat,
  ragCreateKnowledgeBase,
  ragDeleteFile,
  ragDeleteKnowledgeBase,
  ragListFiles,
  ragListKnowledgeBases,
  ragUpload,
} from '../../../api/ApiRAG.js'

export default {
  data() {
    return {
      NEW_KB_VALUE: '__new_kb__',
      kbOptions: [],
      kbFiles: [],
      selectedKb: '',
      inputText: '',
      sending: false,
      messages: [{ role: 'assistant', content: '情报查询会话已开始。请选择知识库并输入问题。' }],
      asideWidth: 260,
      minAsideWidth: 220,
      minMainWidth: 420,
      dividerWidth: 6,
      dragging: false,
      dragStartX: 0,
      dragStartWidth: 260,
    }
  },
  methods: {
    async loadKbList() {
      try {
        const res = await ragListKnowledgeBases()
        const items = Array.isArray(res.data) ? res.data : []
        this.kbOptions = items.map((it) => ({
          value: it.kb_name,
          label: it.kb_name,
          fileCount: Number(it.file_count || 0),
        }))

        if (!this.selectedKb && this.kbOptions.length) {
          this.selectedKb = this.kbOptions[0].value
          await this.loadKbFiles()
          return
        }

        if (this.selectedKb && this.selectedKb !== this.NEW_KB_VALUE) {
          const exists = this.kbOptions.some((k) => k.value === this.selectedKb)
          if (!exists) {
            this.selectedKb = this.kbOptions.length ? this.kbOptions[0].value : ''
            await this.loadKbFiles()
          }
        }
      } catch (e) {
        this.$message.error('获取知识库列表失败：' + (e?.message || e))
      }
    },

    async promptCreateKb() {
      const name = (window.prompt('请输入新建知识库名称') || '').trim()
      if (!name) return
      try {
        await ragCreateKnowledgeBase(name)
        this.$message.success('知识库创建成功')
        await this.loadKbList()
        this.selectedKb = name
        await this.loadKbFiles()
      } catch (e) {
        this.$message.error('创建失败：' + (e?.message || e))
      }
    },

    async onKbChanged(value) {
      if (value === this.NEW_KB_VALUE) {
        await this.promptCreateKb()
        return
      }
      this.messages.push({ role: 'assistant', content: `已切换知识库：${this.selectedKb}` })
      await this.loadKbFiles()
    },

    async removeKb(item) {
      const kbName = item?.value || ''
      if (!kbName) return
      const fileCount = Number(item.fileCount || 0)
      if (fileCount > 0) {
        this.$message.warning(`知识库“${kbName}”下还有 ${fileCount} 个文件，请先删除文件后再删除知识库`)
        return
      }

      const ok = window.confirm(`是否删除该知识库：${kbName}？`)
      if (!ok) {
        return
      }

      try {
        await ragDeleteKnowledgeBase({ knowledgeBaseName: kbName })
        this.$message.success('知识库已删除')
        if (this.selectedKb === kbName) {
          this.selectedKb = ''
          this.kbFiles = []
        }
        await this.loadKbList()
      } catch (e) {
        this.$message.error('删除知识库失败：' + (e?.message || e))
      }
    },

    async loadKbFiles() {
      if (!this.selectedKb || this.selectedKb === this.NEW_KB_VALUE) {
        this.kbFiles = []
        return
      }
      try {
        const res = await ragListFiles(this.selectedKb)
        this.kbFiles = Array.isArray(res.data) ? res.data : []
      } catch (e) {
        this.kbFiles = []
        this.$message.error('获取文件列表失败：' + (e?.message || e))
      }
    },

    async deleteFile(fileName) {
      const ok = window.confirm(`是否删除该文件：${fileName}？`)
      if (!ok) {
        return
      }

      try {
        await ragDeleteFile({ knowledgeBaseName: this.selectedKb, fileName })
        this.$message.success('文件已删除')
        await this.loadKbFiles()
        await this.loadKbList()
      } catch (e) {
        this.$message.error('文件删除失败：' + (e?.message || e))
      }
    },

    triggerUpload() {
      if (!this.selectedKb || this.selectedKb === this.NEW_KB_VALUE) {
        this.$message.warning('请先选择知识库')
        return
      }
      this.$refs.fileInput.click()
    },

    async onFileChange(event) {
      const fileList = Array.from(event.target.files || [])
      if (!fileList.length) return
      try {
        const res = await ragUpload({ knowledgeBaseName: this.selectedKb, files: fileList })
        const skipped = Number(res?.skipped || 0)
        this.$message.success(skipped > 0 ? `上传完成，跳过重复文件 ${skipped} 个` : (res.message || '上传成功'))
        await this.loadKbFiles()
        await this.loadKbList()
      } catch (e) {
        this.$message.error('上传失败：' + (e?.message || e))
      } finally {
        event.target.value = ''
      }
    },

    async sendMessage() {
      const question = (this.inputText || '').trim()
      if (!question || this.sending) return
      if (!this.selectedKb || this.selectedKb === this.NEW_KB_VALUE) {
        this.$message.warning('请先选择知识库')
        return
      }

      this.messages.push({ role: 'user', content: question })
      this.inputText = ''
      this.sending = true
      try {
        const res = await ragChat({ knowledgeBaseName: this.selectedKb, question })
        this.messages.push({ role: 'assistant', content: res.reply || '已接收问题' })
      } catch (e) {
        this.messages.push({ role: 'assistant', content: '请求失败，请稍后重试。' })
      } finally {
        this.sending = false
      }
    },

    startResize(event) {
      event.stopPropagation()
      event.preventDefault()
      this.dragging = true
      this.dragStartX = event.clientX
      this.dragStartWidth = this.asideWidth
      window.addEventListener('mousemove', this.onResizing)
      window.addEventListener('mouseup', this.stopResize)
    },

    onResizing(event) {
      if (!this.dragging) return
      const deltaX = event.clientX - this.dragStartX
      this.applyAsideWidth(this.dragStartWidth + deltaX)
    },

    stopResize() {
      this.dragging = false
      window.removeEventListener('mousemove', this.onResizing)
      window.removeEventListener('mouseup', this.stopResize)
    },

    applyAsideWidth(width) {
      const containerWidth = this.$refs.containerRef?.clientWidth || 900
      const maxAside = containerWidth - this.dividerWidth - this.minMainWidth
      const clamped = Math.max(this.minAsideWidth, Math.min(width, maxAside))
      this.asideWidth = clamped
    },
  },
  mounted() {
    this.loadKbList()
  },
  beforeUnmount() {
    this.stopResize()
  },
}
</script>

<style scoped>
.rag-layout {
  width: 100%;
  height: 100%;
  display: flex;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid #eee;
}

.left-pane {
  height: 100%;
  background-color: rgb(238, 241, 246);
  padding: 12px;
  box-sizing: border-box;
  overflow: auto;
}

.title {
  font-weight: 600;
  margin-bottom: 12px;
}

.block {
  margin-bottom: 10px;
}

.support-tip {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
}

.kb-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}

.kb-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-title {
  margin-top: 10px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}

.file-list {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  padding: 6px;
  max-height: 240px;
  overflow: auto;
}

.file-empty {
  font-size: 12px;
  color: #909399;
}

.file-item {
  padding: 6px 4px;
  border-bottom: 1px solid #f2f2f2;
}

.file-item:last-child {
  border-bottom: none;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.file-name {
  font-size: 13px;
  color: #303133;
  word-break: break-all;
  flex: 1;
}

.file-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  margin-left: auto;
}

.file-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.pane-divider {
  width: 6px;
  cursor: col-resize;
  background: #dcdfe6;
  flex-shrink: 0;
}

.pane-divider:hover {
  background: #c0c4cc;
}

.right-pane {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}

.main-pane {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  padding: 10px;
}

.messages-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-bottom: 1px solid #eee;
  padding: 0 0 10px;
}

.input-row {
  display: flex;
  margin-top: 10px;
}
</style>
