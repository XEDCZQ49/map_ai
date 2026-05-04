<script setup>
import { ref, computed, onBeforeUnmount, onMounted, watch } from 'vue'
import MapDisplay from './components/MapDisplay.vue'
import Experience from './components/Experience.vue'

const panelRef = ref(null)
const baseWidth = 800
const baseHeight = 600
const panelScale = ref(0.95)

const minScale = 0.55
const maxScale = 1.6
const edgeThreshold = 8

const hoverEdge = ref('')
const resizing = ref(false)
const dragging = ref(false)

const panelLeft = ref(0)
const panelTop = ref(12)

const resizeCtx = {
  startX: 0,
  startY: 0,
  startScale: 0.95,
  startW: baseWidth * 0.95,
  startH: baseHeight * 0.95,
  startLeft: 0,
  startTop: 0,
  edge: ''
}

const dragCtx = {
  startX: 0,
  startY: 0,
  startLeft: 0,
  startTop: 0
}

const panelWidthPx = computed(() => baseWidth * panelScale.value)
const panelHeightPx = computed(() => baseHeight * panelScale.value)

const panelStyle = computed(() => ({
  left: `${panelLeft.value}px`,
  top: `${panelTop.value}px`,
  width: `${baseWidth * panelScale.value}px`,
  height: `${baseHeight * panelScale.value}px`
}))

const panelInnerStyle = computed(() => ({
  width: `${baseWidth}px`,
  height: `${baseHeight}px`,
  transform: `scale(${panelScale.value})`,
  transformOrigin: 'left top'
}))

function detectEdge(e) {
  const el = panelRef.value
  if (!el) return ''
  const rect = el.getBoundingClientRect()

  const nearLeft = e.clientX - rect.left <= edgeThreshold
  const nearRight = rect.right - e.clientX <= edgeThreshold
  const nearTop = e.clientY - rect.top <= edgeThreshold
  const nearBottom = rect.bottom - e.clientY <= edgeThreshold

  let edge = ''
  if (nearTop) edge += 'n'
  if (nearBottom) edge += 's'
  if (nearLeft) edge += 'w'
  if (nearRight) edge += 'e'
  return edge
}

function edgeCursor(edge) {
  switch (edge) {
    case 'n':
    case 's':
      return 'ns-resize'
    case 'e':
    case 'w':
      return 'ew-resize'
    case 'ne':
    case 'sw':
      return 'nesw-resize'
    case 'nw':
    case 'se':
      return 'nwse-resize'
    default:
      return 'default'
  }
}

function onPanelMouseMove(e) {
  if (resizing.value || dragging.value) return
  hoverEdge.value = detectEdge(e)
  if (panelRef.value) panelRef.value.style.cursor = edgeCursor(hoverEdge.value)
}

function onPanelMouseLeave() {
  if (resizing.value || dragging.value) return
  hoverEdge.value = ''
  if (panelRef.value) panelRef.value.style.cursor = 'default'
}

function onPanelMouseDown(e) {
  if (!panelRef.value) return
  const edge = detectEdge(e)
  if (!edge) return

  resizing.value = true
  resizeCtx.startX = e.clientX
  resizeCtx.startY = e.clientY
  resizeCtx.startScale = panelScale.value
  resizeCtx.startW = baseWidth * panelScale.value
  resizeCtx.startH = baseHeight * panelScale.value
  resizeCtx.startLeft = panelLeft.value
  resizeCtx.startTop = panelTop.value
  resizeCtx.edge = edge

  window.addEventListener('mousemove', onWindowMouseMove)
  window.addEventListener('mouseup', onWindowMouseUp)
  e.preventDefault()
}

function isInteractiveTarget(target) {
  if (!target || !(target instanceof Element)) return false
  return !!target.closest(
    'button,input,textarea,select,a,[role="button"],.el-tabs__item,.el-tabs__header,.el-input,.el-tree,.el-tree-node,.pane-divider,.btn_send_dialog,.btn_record_dialog,.btn_end_dialog'
  )
}

function onPanelContentMouseDown(e) {
  if (resizing.value || !panelRef.value) return
  if (detectEdge(e)) return
  if (isInteractiveTarget(e.target)) return

  dragging.value = true
  dragCtx.startX = e.clientX
  dragCtx.startY = e.clientY
  dragCtx.startLeft = panelLeft.value
  dragCtx.startTop = panelTop.value
  panelRef.value.style.cursor = 'grabbing'

  window.addEventListener('mousemove', onWindowMouseMove)
  window.addEventListener('mouseup', onWindowMouseUp)
  e.preventDefault()
}

function clampPanelPosition(left, top) {
  const maxX = Math.max(0, window.innerWidth - panelWidthPx.value)
  const maxY = Math.max(0, window.innerHeight - panelHeightPx.value)
  return {
    left: Math.max(0, Math.min(left, maxX)),
    top: Math.max(0, Math.min(top, maxY))
  }
}

function onWindowMouseMove(e) {
  if (!resizing.value && !dragging.value) return

  if (dragging.value) {
    const dx = e.clientX - dragCtx.startX
    const dy = e.clientY - dragCtx.startY
    const next = clampPanelPosition(dragCtx.startLeft + dx, dragCtx.startTop + dy)
    panelLeft.value = next.left
    panelTop.value = next.top
    return
  }

  const dx = e.clientX - resizeCtx.startX
  const dy = e.clientY - resizeCtx.startY
  const edge = resizeCtx.edge

  let scaleFromX = null
  let scaleFromY = null

  if (edge.includes('e')) scaleFromX = (resizeCtx.startW + dx) / baseWidth
  if (edge.includes('w')) scaleFromX = (resizeCtx.startW - dx) / baseWidth
  if (edge.includes('s')) scaleFromY = (resizeCtx.startH + dy) / baseHeight
  if (edge.includes('n')) scaleFromY = (resizeCtx.startH - dy) / baseHeight

  let nextScale = resizeCtx.startScale
  if (scaleFromX != null && scaleFromY != null) {
    const diffX = Math.abs(scaleFromX - resizeCtx.startScale)
    const diffY = Math.abs(scaleFromY - resizeCtx.startScale)
    nextScale = diffX >= diffY ? scaleFromX : scaleFromY
  } else if (scaleFromX != null) {
    nextScale = scaleFromX
  } else if (scaleFromY != null) {
    nextScale = scaleFromY
  }

  nextScale = Math.max(minScale, Math.min(maxScale, nextScale))

  const nextW = baseWidth * nextScale
  const nextH = baseHeight * nextScale

  let nextLeft = resizeCtx.startLeft
  let nextTop = resizeCtx.startTop

  // 固定对侧边/角：拖左边时固定右边，拖上边时固定下边。
  if (edge.includes('w')) {
    nextLeft = resizeCtx.startLeft + (resizeCtx.startW - nextW)
  }
  if (edge.includes('n')) {
    nextTop = resizeCtx.startTop + (resizeCtx.startH - nextH)
  }

  panelScale.value = nextScale
  const clamped = clampPanelPosition(nextLeft, nextTop)
  panelLeft.value = clamped.left
  panelTop.value = clamped.top
}

function onWindowMouseUp() {
  resizing.value = false
  dragging.value = false
  window.removeEventListener('mousemove', onWindowMouseMove)
  window.removeEventListener('mouseup', onWindowMouseUp)
  if (panelRef.value) panelRef.value.style.cursor = 'default'
}

function initializePanelPosition() {
  // 默认按当前窗口 40% 计算显示尺寸，并放到右下角。
  const widthScale = (window.innerWidth * 0.4) / baseWidth
  const heightScale = (window.innerHeight * 0.4) / baseHeight
  panelScale.value = Math.max(minScale, Math.min(maxScale, Math.min(widthScale, heightScale)))

  const defaultLeft = window.innerWidth - panelWidthPx.value - 12
  const defaultTop = window.innerHeight - panelHeightPx.value - 12
  panelLeft.value = Math.max(0, defaultLeft)
  panelTop.value = Math.max(0, defaultTop)
}

function onWindowResize() {
  const clamped = clampPanelPosition(panelLeft.value, panelTop.value)
  panelLeft.value = clamped.left
  panelTop.value = clamped.top
}

onMounted(() => {
  initializePanelPosition()
  window.addEventListener('resize', onWindowResize)
})

watch(panelScale, () => {
  const clamped = clampPanelPosition(panelLeft.value, panelTop.value)
  panelLeft.value = clamped.left
  panelTop.value = clamped.top
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onWindowMouseMove)
  window.removeEventListener('mouseup', onWindowMouseUp)
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <div id="app">
    <MapDisplay />

    <div
      ref="panelRef"
      class="chat-panel"
      :style="panelStyle"
      @mousemove="onPanelMouseMove"
      @mouseleave="onPanelMouseLeave"
      @mousedown="onPanelMouseDown"
    >
      <div class="chat-panel-inner" :style="panelInnerStyle" @mousedown="onPanelContentMouseDown">
        <Experience />
      </div>
    </div>
  </div>
</template>

<style>
html,
body,
#app {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
}

.chat-panel {
  position: fixed;
  top: 12px;
  right: 12px;
  z-index: 300;
  border: 1px solid rgba(0, 0, 0, 0.18);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
  overflow: hidden;
  user-select: none;
}

.chat-panel-inner {
  position: absolute;
  top: 0;
  left: 0;
}
</style>
