<template>
  <div ref="containerRef" class="smart-config-root" @wheel.stop>
    <div class="left-pane" :style="{ width: `${asideWidth}px` }" @wheel.stop>
      <div class="aside-header">
        <h3>智能配置</h3>
        <el-button size="small" @click="loadTreeData">刷新</el-button>
      </div>

      <el-tree
        ref="nodeTree"
        class="node-tree"
        :data="moduleTree"
        node-key="id"
        default-expand-all
        highlight-current
        :expand-on-click-node="false"
        @node-click="onTreeNodeClick"
      >
        <template #default="{ data }">
          <span :class="data.type === 'module' ? 'module-label' : 'node-label'">{{ data.label }}</span>
        </template>
      </el-tree>
    </div>

    <div class="pane-divider" @mousedown.stop.prevent="startResize"></div>

    <div class="right-pane" @wheel.stop>
      <div class="smart-config-header">当前节点：{{ selectedNodeKey || '未选择' }}</div>

      <div class="smart-config-main">
        <div class="panel">
          <div class="panel-title">当前提示词（now，可编辑）</div>
          <el-input
            v-model="nowPrompt"
            type="textarea"
            :rows="21"
            resize="none"
            placeholder="请选择节点"
            :disabled="!selectedNodeKey || loading"
          />
          <div class="actions">
            <el-button type="primary" :disabled="!selectedNodeKey || loading" @click="saveNowPrompt">
              保存并热更新
            </el-button>
            <el-button :disabled="!selectedNodeKey || loading" @click="resetNowFromDefault">
              恢复默认配置
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { promptGet, promptNodes, promptSaveNow } from '../../../api/ApiPrompt.js'

export default {
  data() {
    return {
      moduleTree: [],
      selectedNodeKey: '',
      nowPrompt: '',
      loading: false,
      asideWidth: 300,
      minAsideWidth: 220,
      minMainWidth: 360,
      dividerWidth: 6,
      dragging: false,
      dragStartX: 0,
      dragStartWidth: 300,
    }
  },
  methods: {
    buildTree(nodes) {
      const envChildren = []
      const drwChildren = []
      const ragChildren = []
      const otherChildren = []

      nodes.forEach((node) => {
        const item = { id: node, label: node, key: node, type: 'node' }
        if (node.startsWith('ENV')) {
          envChildren.push(item)
        } else if (node.startsWith('DRW')) {
          drwChildren.push(item)
        } else if (node.startsWith('RAG')) {
          ragChildren.push(item)
        } else {
          otherChildren.push(item)
        }
      })

      const tree = []
      if (envChildren.length) {
        tree.push({ id: 'module_env', label: '环境分析', type: 'module', children: envChildren })
      }
      if (drwChildren.length) {
        tree.push({ id: 'module_drw', label: '要图标绘', type: 'module', children: drwChildren })
      }
      if (ragChildren.length) {
        tree.push({ id: 'module_rag', label: '情报查询', type: 'module', children: ragChildren })
      }
      if (otherChildren.length) {
        tree.push({ id: 'module_other', label: '其它', type: 'module', children: otherChildren })
      }
      return tree
    },

    async loadTreeData() {
      this.loading = true
      try {
        const res = await promptNodes()
        const nodes = Array.isArray(res.nodes) ? res.nodes : []
        this.moduleTree = this.buildTree(nodes)

        if (!nodes.length) {
          this.selectedNodeKey = ''
          this.nowPrompt = ''
          return
        }

        if (!this.selectedNodeKey || !nodes.includes(this.selectedNodeKey)) {
          this.selectedNodeKey = nodes[0]
          await this.loadNodeNowPrompt(this.selectedNodeKey)
          this.$nextTick(() => this.$refs.nodeTree?.setCurrentKey(this.selectedNodeKey))
        }
      } catch (e) {
        this.$message.error('加载节点失败：' + (e?.message || e))
      } finally {
        this.loading = false
      }
    },

    async onTreeNodeClick(data) {
      if (data.type !== 'node') return
      this.selectedNodeKey = data.key
      await this.loadNodeNowPrompt(this.selectedNodeKey)
    },

    async loadNodeNowPrompt(nodeKey) {
      this.loading = true
      try {
        const nowRes = await promptGet(nodeKey, 'now')
        this.nowPrompt = nowRes.prompt || ''
      } catch (e) {
        this.$message.error('读取提示词失败：' + (e?.message || e))
      } finally {
        this.loading = false
      }
    },

    async saveNowPrompt() {
      if (!this.selectedNodeKey) return
      this.loading = true
      try {
        await promptSaveNow(this.selectedNodeKey, this.nowPrompt)
        this.$message.success('已保存，后续请求将使用新提示词')
      } catch (e) {
        this.$message.error('保存失败：' + (e?.message || e))
      } finally {
        this.loading = false
      }
    },

    async resetNowFromDefault() {
      if (!this.selectedNodeKey) return
      this.loading = true
      try {
        const defRes = await promptGet(this.selectedNodeKey, 'def')
        this.nowPrompt = defRes.prompt || ''
        this.$message.success('默认配置已加载到编辑区，请点击“保存并热更新”后生效')
      } catch (e) {
        this.$message.error('恢复失败：' + (e?.message || e))
      } finally {
        this.loading = false
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
      const nextWidth = this.dragStartWidth + deltaX
      this.applyAsideWidth(nextWidth)
    },

    stopResize() {
      this.dragging = false
      window.removeEventListener('mousemove', this.onResizing)
      window.removeEventListener('mouseup', this.stopResize)
    },

    applyAsideWidth(width) {
      const containerWidth = this.$refs.containerRef?.clientWidth || 800
      const maxAside = containerWidth - this.dividerWidth - this.minMainWidth
      const clamped = Math.max(this.minAsideWidth, Math.min(width, maxAside))
      this.asideWidth = clamped
    },
  },
  mounted() {
    this.loadTreeData()
  },
  beforeUnmount() {
    this.stopResize()
  },
}
</script>

<style scoped>
.smart-config-root {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  overflow: hidden;
}

.left-pane {
  height: 100%;
  background: rgb(238, 241, 246);
  padding: 10px;
  box-sizing: border-box;
  overflow: auto;
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
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.aside-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.aside-header h3 {
  margin: 0;
}

.node-tree {
  background: transparent;
}

.module-label {
  font-weight: 600;
}

.node-label {
  font-weight: 400;
}

.smart-config-header {
  line-height: 44px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  padding: 0 12px;
}

.smart-config-main {
  flex: 1;
  overflow: auto;
  padding: 12px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
}

.panel-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
</style>
