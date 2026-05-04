<template>
  <div ref="cesiumContainer" id="cesiumContainer"></div>

  <div class="plan-panel" v-if="currentPlanId">
    <div class="plan-row">
      <label for="plan-select">战场选择：</label>
      <el-select
        id="plan-select"
        :model-value="currentPlanId"
        placeholder="选择战场"
        popper-class="plan-select-popper"
        @change="handlePlanChange"
      >
        <el-option :value="NEW_PLAN_OPTION_VALUE" label="+ 创建新地图" />
        <el-option
          v-for="plan in planOptions"
          :key="plan.plan_id"
          :value="plan.plan_id"
          :label="plan.plan_name || plan.plan_id"
        />
      </el-select>
    </div>
    <div class="plan-row">状态：{{ planStatusText }}</div>
    <div class="plan-actions">
      <button type="button" class="save-btn" @click="saveCurrentPlan">保存战场</button>
      <button type="button" class="delete-plan-btn" @click.stop="deleteCurrentPlan">删除战场</button>
    </div>
  </div>

  <div class="button-container" v-if="selectedEntity !== null">
    <button @click="removeEntity">删除选中实体</button>
  </div>

  <div class="test-button-container" v-if="showTestUi">
    <div class="test-title">测试模式（统一入口）</div>
    <button @click="startTest('place_tank')">部署坦克（1点）</button>
    <button @click="startTest('place_aircraft')">部署飞机（1点）</button>
    <button @click="startTest('place_car')">部署车辆（1点）</button>
    <button @click="startTest('place_by')">部署兵营（1点）</button>

    <button @click="startTest('circle')">画圆（1点）</button>
    <button @click="startTest('line')">画线（2点）</button>
    <button @click="startTest('labeled_line')">标注线（2点）</button>
    <button @click="startTest('polygon')">画多边形（3点）</button>
    <button @click="startTest('arrow_2')">箭头-直箭头（2点）</button>
    <button @click="startTest('arrow_3')">箭头-进攻（3点）</button>
    <button @click="startTest('arrow_4')">箭头-进攻（4点）</button>
    <button @click="startTest('double_arrow_3')">双尖头（3点）</button>
    <button @click="startTest('double_arrow_4')">双尖头（4点）</button>

    <button @click="startTest('qun')">作战群（3点）</button>
    <button @click="startTest('defend')">防御阵地（3点）</button>
    <button @click="startTest('open_curve')">开放曲线（3点）</button>
    <button @click="startTest('close_curve')">闭合曲线（3点）</button>
    <button @click="startTest('double_end_curves')">双端曲线（3点）</button>

    <button class="cancel-btn" @click="cancelTest">取消当前测试</button>
  </div>

  <div class="tip-panel" v-if="showTestUi && activeTest">
    <div>当前模式：{{ activeTest.label }}</div>
    <div>需要点数：{{ activeTest.requiredPoints }}</div>
    <div>已采点数：{{ testPoints.length }}</div>
    <div>提示：请在地图上左键点击采点</div>
  </div>
</template>

<script>
import '../assets/js/Sandcastle-header.js'
import '../assets/js/jquery.min.js'
import '../assets/Cesium/Cesium.js'
import EntityManager from '../map/manager/EntityManager.js'
import DrawManager from '../map/manager/DrawManager.js'
import { createPlan, deletePlan, listPlans, loadPlan, savePlan } from '../api/ApiLLM.js'

const PLAN_SESSION_KEY = 'map-working-plans'
const NEW_PLAN_OPTION_VALUE = '__create_new_plan__'

const TEST_DEFS = {
  place_tank: { label: '部署坦克', requiredPoints: 1 },
  place_aircraft: { label: '部署飞机', requiredPoints: 1 },
  place_car: { label: '部署车辆', requiredPoints: 1 },
  place_by: { label: '部署兵营', requiredPoints: 1 },
  circle: { label: '画圆', requiredPoints: 1 },
  line: { label: '画线', requiredPoints: 2 },
  labeled_line: { label: '标注线', requiredPoints: 2 },
  polygon: { label: '画多边形', requiredPoints: 3 },
  arrow_2: { label: '箭头-直箭头', requiredPoints: 2 },
  arrow_3: { label: '箭头-进攻', requiredPoints: 3 },
  arrow_4: { label: '箭头-进攻', requiredPoints: 4 },
  double_arrow_3: { label: '双尖头', requiredPoints: 3 },
  double_arrow_4: { label: '双尖头', requiredPoints: 4 },
  qun: { label: '作战群', requiredPoints: 3 },
  defend: { label: '防御阵地', requiredPoints: 3 },
  open_curve: { label: '开放曲线', requiredPoints: 3 },
  close_curve: { label: '闭合曲线', requiredPoints: 3 },
  double_end_curves: { label: '双端曲线', requiredPoints: 3 }
}

export default {
  setup() {
    return { NEW_PLAN_OPTION_VALUE }
  },
  data() {
    return {
      viewer: null,
      entityManager: null,
      drawManager: null,
      selectedEntity: null,
      clickHandler: null,
      planOptions: [],

      // 所有测试相关状态集中在这里，后续可统一注释/关闭
      showTestUi: false,
      activeTestKey: null,
      testPoints: [],
      tempPointEntities: []
    }
  },

  computed: {
    activeTest() {
      return this.activeTestKey ? TEST_DEFS[this.activeTestKey] : null
    },
    currentPlanId() {
      return this.$store?.state?.currentPlanId || ''
    },
    planCommands() {
      return Array.isArray(this.$store?.state?.planCommands) ? this.$store.state.planCommands : []
    },
    planSaved() {
      return !!this.$store?.state?.planSaved
    },
    planStatusText() {
      return this.planSaved ? '已保存' : '未保存'
    },
    sharedObject() {
      return this.$store?.state?.sharedObject
    }
  },

  watch: {
    // 监听后端 Graph 返回，自动执行地图命令。
    sharedObject: {
      handler(val) {
        if (!val) return
        const commands = Array.isArray(val?.commands) ? val.commands : Array.isArray(val) ? val : []
        if (!commands.length) return
        console.log('[MapDisplay] 收到共享命令:', commands)
        this.executeGraphCommands(commands, val?.requestId)
      },
      deep: true
    },
    currentPlanId() {
      this.persistWorkingPlan()
    },
    planCommands: {
      handler() {
        this.persistWorkingPlan()
      },
      deep: true
    },
    planSaved() {
      this.persistWorkingPlan()
    }
  },

  mounted() {
    this.initMap()
    this.setupClickEventHandler()
    this.initWorkingPlan()
  },

  beforeUnmount() {
    if (this.clickHandler) this.clickHandler.destroy()
    if (this.viewer) this.viewer.destroy()
  },

  methods: {
    async initWorkingPlan() {
      if (this.currentPlanId) return
      const snapshot = this.restoreWorkingPlan()
      if (snapshot.planId) {
        await this.$store.dispatch('setCurrentPlanId', snapshot.planId)
        await this.$store.dispatch('resetPlanCommands', {
          commands: snapshot.current.commands,
          saved: snapshot.current.saved
        })
        this.refreshPlanOptions(snapshot)
        this.replayPlanCommands(snapshot.current.commands)
        await this.fetchPlanOptions()
        return
      }
      try {
        const result = await createPlan()
        const planId = String(result?.plan_id || '').trim()
        if (planId) {
          await this.$store.dispatch('setCurrentPlanId', planId)
          await this.$store.dispatch('resetPlanCommands', { commands: [], saved: false })
          this.refreshPlanOptions({
            planId,
            plans: {
              [planId]: { commands: [], saved: false }
            },
            current: { commands: [], saved: false }
          })
        }
      } catch (e) {
        console.error('初始化 plan 失败:', e)
      }
      await this.fetchPlanOptions()
    },

    async saveCurrentPlan() {
      if (!this.currentPlanId) return
      try {
        const result = await savePlan(this.currentPlanId, this.planCommands, this.currentPlanId)
        if (result?.code === 0 || result?.message === 'success') {
          await this.$store.dispatch('setPlanSaved', true)
          await this.fetchPlanOptions()
        }
      } catch (e) {
        console.error('保存地图失败:', e)
      }
    },

    persistWorkingPlan() {
      if (!this.currentPlanId) return
      try {
        const snapshot = this.restoreWorkingPlan()
        snapshot.planId = this.currentPlanId
        snapshot.plans = snapshot.plans || {}
        snapshot.plans[this.currentPlanId] = {
          commands: this.planCommands,
          saved: this.planSaved
        }
        snapshot.current = snapshot.plans[this.currentPlanId]
        sessionStorage.setItem(PLAN_SESSION_KEY, JSON.stringify(snapshot))
        this.refreshPlanOptions(snapshot)
      } catch (e) {
        console.error('缓存当前方案失败:', e)
      }
    },

    restoreWorkingPlan() {
      try {
        const raw = sessionStorage.getItem(PLAN_SESSION_KEY)
        if (!raw) return { planId: '', plans: {}, current: { commands: [], saved: false } }
        const parsed = JSON.parse(raw)
        const plans = parsed?.plans && typeof parsed.plans === 'object' ? parsed.plans : {}
        const planId = String(parsed?.planId || '').trim()
        const current = plans[planId] || { commands: [], saved: false }
        return {
          planId,
          plans,
          current: {
            commands: Array.isArray(current?.commands) ? current.commands : [],
            saved: !!current?.saved
          }
        }
      } catch (e) {
        console.error('恢复当前方案失败:', e)
        return { planId: '', plans: {}, current: { commands: [], saved: false } }
      }
    },

    refreshPlanOptions(snapshot = this.restoreWorkingPlan(), remotePlans = this.planOptions) {
      const options = new Map()
      const plans = snapshot?.plans && typeof snapshot.plans === 'object' ? snapshot.plans : {}

      ;(Array.isArray(remotePlans) ? remotePlans : []).forEach((plan) => {
        const planId = String(plan?.plan_id || '').trim()
        if (!planId) return
        options.set(planId, {
          plan_id: planId,
          plan_name: plan.plan_name || planId,
          saved: !!plan.saved,
          status: plan.status || (plan.saved ? 'saved' : 'active')
        })
      })

      Object.keys(plans).forEach((planId) => {
        const current = options.get(planId)
        options.set(planId, {
          plan_id: planId,
          plan_name: current?.plan_name || planId,
          saved: current?.saved ?? !!plans[planId]?.saved,
          status: current?.status || (plans[planId]?.saved ? 'saved' : 'active')
        })
      })

      this.planOptions = Array.from(options.values()).sort((a, b) => this.planOrder(a.plan_id) - this.planOrder(b.plan_id))
    },

    planOrder(planId) {
      const match = String(planId || '').match(/^plan-(\d+)$/)
      return match ? Number(match[1]) : Number.MAX_SAFE_INTEGER
    },

    async fetchPlanOptions() {
      try {
        const result = await listPlans()
        this.refreshPlanOptions(this.restoreWorkingPlan(), Array.isArray(result?.plans) ? result.plans : [])
      } catch (e) {
        console.error('获取方案列表失败:', e)
      }
    },

    async handlePlanChange(planId) {
      const targetPlanId = String(planId || '').trim()
      if (!targetPlanId) return
      if (targetPlanId === NEW_PLAN_OPTION_VALUE) {
        await this.createAndSwitchNewPlan()
        return
      }
      if (targetPlanId === this.currentPlanId) return

      const snapshot = this.restoreWorkingPlan()
      const cachedPlan = snapshot?.plans?.[targetPlanId]
      if (cachedPlan) {
        await this.switchToPlan(targetPlanId, cachedPlan.commands, cachedPlan.saved)
        return
      }

      try {
        const result = await loadPlan(targetPlanId)
        await this.switchToPlan(
          targetPlanId,
          Array.isArray(result?.commands) ? result.commands : [],
          !!result?.saved
        )
      } catch (e) {
        console.error('加载方案失败:', e)
      }
    },

    async createAndSwitchNewPlan() {
      try {
        const result = await createPlan()
        const planId = String(result?.plan_id || '').trim()
        if (!planId) return
        await this.switchToPlan(planId, [], false)
        const snapshot = this.restoreWorkingPlan()
        snapshot.plans = snapshot.plans || {}
        snapshot.plans[planId] = { commands: [], saved: false }
        snapshot.planId = planId
        snapshot.current = snapshot.plans[planId]
        sessionStorage.setItem(PLAN_SESSION_KEY, JSON.stringify(snapshot))
        await this.fetchPlanOptions()
      } catch (e) {
        console.error('创建新地图失败:', e)
      }
    },

    async deleteCurrentPlan() {
      const planId = String(this.currentPlanId || '').trim()
      if (!planId) return
      let remotePlans = []
      try {
        const listResult = await listPlans()
        remotePlans = Array.isArray(listResult?.plans) ? listResult.plans : []
      } catch (e) {
        console.error('删除前获取战场列表失败:', e)
      }
      const existedInRemote = remotePlans.some((item) => String(item?.plan_id || '').trim() === planId)

      let deleted = false
      try {
        const result = await deletePlan(planId)
        deleted = result?.deleted === true
      } catch (e) {
        console.error('删除战场失败:', e)
      }
      // 对于仅本地缓存、后端已不存在的未保存战场，允许本地删除继续执行。
      const allowLocalDelete = !this.planSaved && !existedInRemote
      if (!deleted && !allowLocalDelete) {
        console.error('删除战场未生效，已取消本地切换:', planId)
        await this.fetchPlanOptions()
        return
      }

      const snapshot = this.restoreWorkingPlan()
      if (snapshot?.plans?.[planId]) {
        delete snapshot.plans[planId]
      }
      snapshot.planId = ''
      snapshot.current = { commands: [], saved: false }
      sessionStorage.setItem(PLAN_SESSION_KEY, JSON.stringify(snapshot))

      await this.fetchPlanOptions()
      const candidates = this.planOptions.filter((item) => String(item?.plan_id || '').trim() !== planId)
      if (candidates.length > 0) {
        const nextPlanId = candidates[0].plan_id
        const cachedPlan = snapshot?.plans?.[nextPlanId]
        if (cachedPlan) {
          await this.switchToPlan(nextPlanId, cachedPlan.commands, cachedPlan.saved)
        } else {
          try {
            const loaded = await loadPlan(nextPlanId)
            await this.switchToPlan(nextPlanId, Array.isArray(loaded?.commands) ? loaded.commands : [], !!loaded?.saved)
          } catch (e) {
            console.error('删除后切换战场失败:', e)
          }
        }
      } else {
        await this.createAndSwitchNewPlan()
      }
    },

    async switchToPlan(planId, commands = [], saved = false) {
      this.clearMapObjects()
      await this.$store.dispatch('setCurrentPlanId', planId)
      await this.$store.dispatch('resetPlanCommands', {
        commands: Array.isArray(commands) ? commands : [],
        saved: !!saved
      })
      this.replayPlanCommands(commands)
      this.refreshPlanOptions()
    },

    replayPlanCommands(commands = []) {
      if (!Array.isArray(commands) || !commands.length) return
      commands.forEach((cmd) => {
        try {
          this.executeOneCommand(cmd)
        } catch (e) {
          console.error('恢复历史方案命令失败:', cmd, e)
        }
      })
    },

    executeGraphCommands(commands, requestId = '') {
      const result = {
        requestId: requestId || '',
        total: commands.length,
        successCount: 0,
        failedCount: 0,
        errors: []
      }
      commands.forEach((cmd) => {
        try {
          this.executeOneCommand(cmd)
          this.$store.dispatch('appendPlanCommands', [cmd])
          result.successCount += 1
        } catch (e) {
          console.error('命令执行失败:', cmd, e)
          result.failedCount += 1
          result.errors.push(e?.message || 'unknown')
        }
      })
      if (requestId) {
        window.dispatchEvent(new CustomEvent('map-deploy-result', { detail: result }))
      }
    },

    executeOneCommand(cmd) {
      const fn = cmd?.function_name || cmd?.functionName
      const args = cmd?.arguments || {}
      const color = this.toColor(cmd?.color)
      const points = this.toPoints(args.lonlat)
      console.log('[MapDisplay] 执行命令:', { fn, args, color, raw: cmd })
      this.validateCommand(fn, args)

      const entityResult = this.entityManager.executeCommand(fn, { ...args, color })
      if (entityResult) return entityResult

      const drawArgs = points.length ? { ...args, lonlat: points } : args
      const drawResult = this.drawManager.executeCommand(fn, drawArgs, {
        color,
        id: args.id || args.graphic_id || args.object_id
      })
      if (drawResult) return drawResult

      console.warn('[MapDisplay] 未实现的命令:', fn, cmd)
    },

    validateCommand(fn, args = {}) {
      const deployCommands = new Set([
        'add_tank',
        'add_aircraft',
        'add_by',
        'add_car',
        'draw_group',
        'draw_defense',
        'draw_boundary',
        'draw_attack',
        'draw_attack_route',
        'draw_encirclement_attack'
      ])

      if (deployCommands.has(fn) && !String(args.id || '').trim()) {
        throw new Error(`${fn} 缺少必填 id`)
      }
    },

    toColor(color) {
      if (typeof color !== 'string') return 'red'
      const normalized = color.trim().toLowerCase()
      return normalized || 'red'
    },

    toPoint(lonlat) {
      if (!Array.isArray(lonlat)) return null
      if (lonlat.length >= 2) {
        // 仅当传入是 [lon, lat] 才按单点解析；[[...],[...]] 交给 toPoints 处理
        if (Array.isArray(lonlat[0]) || Array.isArray(lonlat[1])) return null
        const lon = Number(lonlat[0])
        const lat = Number(lonlat[1])
        if (Number.isFinite(lon) && Number.isFinite(lat) && lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90) {
          return [lon, lat]
        }
      }
      return null
    },

    toPoints(lonlat) {
      if (!Array.isArray(lonlat)) return []
      const single = this.toPoint(lonlat)
      if (single) return [single]
      return lonlat.map((point) => this.toPoint(point)).filter((point) => Array.isArray(point))
    },

    initMap() {
      const gaodeMapKey = import.meta.env.GAODE_MAP_KEY
      const gaodeMapSak = import.meta.env.GAODE_MAP_SAK

      if (gaodeMapSak) {
        window._AMapSecurityConfig = { securityJsCode: gaodeMapSak }
      }

      if (!gaodeMapKey) {
        console.warn('GAODE_MAP_KEY 未配置，在线瓦片可能不可用。')
      }

      const amapLayer = new Cesium.UrlTemplateImageryProvider({
        url: `https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}&key=${gaodeMapKey || ''}`,
        subdomains: ['1', '2', '3', '4'],
        tilingScheme: new Cesium.WebMercatorTilingScheme(),
        minimumLevel: 1,
        maximumLevel: 18,
        credit: new Cesium.Credit('AMap')
      })

      this.viewer = new Cesium.Viewer(this.$refs.cesiumContainer, {
        baseLayerPicker: false,
        imageryProvider: amapLayer
      })

      this.entityManager = new EntityManager(this.viewer)
      this.drawManager = new DrawManager(this.viewer)
      window.CESIUM_VIEWER = this.viewer

      this.viewer.scene.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(116.46, 39.92, 6378137),
        duration: 0.1
      })
      this.viewer.scene.globe.depthTestAgainstTerrain = false
    },

    clearMapObjects() {
      this.cancelTest()
      this.selectedEntity = null
      this.viewer.entities.removeAll()
      this.entityManager = new EntityManager(this.viewer)
      this.drawManager = new DrawManager(this.viewer)
    },

    setupClickEventHandler() {
      this.clickHandler = new Cesium.ScreenSpaceEventHandler(this.viewer.scene.canvas)
      this.clickHandler.setInputAction((movement) => {
        if (this.activeTestKey) {
          this.handleTestMapClick(movement.position)
          return
        }

        this.selectedEntity = this.pickSelectableEntity(movement.position)
      }, Cesium.ScreenSpaceEventType.LEFT_CLICK)
    },

    pickSelectableEntity(position) {
      const pickedObject = this.viewer.scene.pick(position)
      if (Cesium.defined(pickedObject) && pickedObject.id instanceof Cesium.Entity) {
        return pickedObject.id
      }
      return this.pickNearbyEntity(position, 18)
    },

    pickNearbyEntity(position, maxPixelDistance = 18) {
      if (!this.viewer) return null

      const candidates = this.viewer.entities.values
      let bestEntity = null
      let bestDistance = maxPixelDistance + 1

      candidates.forEach((entity) => {
        const distance = this.getEntityScreenDistance(entity, position)
        if (distance < bestDistance) {
          bestDistance = distance
          bestEntity = entity
        }
      })

      return bestEntity
    },

    getEntityScreenDistance(entity, position) {
      const points = Array.isArray(entity?._selectionPoints) && entity._selectionPoints.length
        ? entity._selectionPoints
        : this.getFallbackSelectionPoints(entity)
      if (!points.length) return Number.POSITIVE_INFINITY

      let best = Number.POSITIVE_INFINITY
      const step = points.length > 80 ? Math.ceil(points.length / 80) : 1

      for (let i = 0; i < points.length; i += step) {
        const point = points[i]
        const screen = Cesium.SceneTransforms.wgs84ToWindowCoordinates(
          this.viewer.scene,
          Cesium.Cartesian3.fromDegrees(point[0], point[1], point[2] || 0)
        )
        if (!screen) continue
        const distance = Math.hypot(screen.x - position.x, screen.y - position.y)
        if (distance < best) best = distance
      }

      return best
    },

    getFallbackSelectionPoints(entity) {
      if (!entity) return []
      const cartesian = entity.position?.getValue?.(this.viewer.clock.currentTime)
      if (!cartesian) return []
      const cartographic = Cesium.Cartographic.fromCartesian(cartesian)
      if (!cartographic) return []
      return [[
        Cesium.Math.toDegrees(cartographic.longitude),
        Cesium.Math.toDegrees(cartographic.latitude),
        cartographic.height
      ]]
    },

    getLngLatFromClick(position) {
      const cartesian = this.viewer.camera.pickEllipsoid(position, this.viewer.scene.globe.ellipsoid)
      if (!cartesian) return null
      const cartographic = Cesium.Cartographic.fromCartesian(cartesian)
      const lng = Cesium.Math.toDegrees(cartographic.longitude)
      const lat = Cesium.Math.toDegrees(cartographic.latitude)
      return [lng, lat]
    },

    addTempPoint(point) {
      const entity = this.viewer.entities.add({
        position: Cesium.Cartesian3.fromDegrees(point[0], point[1]),
        point: {
          pixelSize: 8,
          color: Cesium.Color.YELLOW,
          outlineColor: Cesium.Color.BLACK,
          outlineWidth: 1,
          heightReference: Cesium.HeightReference.CLAMP_TO_GROUND
        }
      })
      this.tempPointEntities.push(entity)
    },

    clearTempPoints() {
      this.tempPointEntities.forEach((e) => this.viewer.entities.remove(e))
      this.tempPointEntities = []
    },

    startTest(testKey) {
      this.activeTestKey = testKey
      this.testPoints = []
      this.clearTempPoints()
    },

    cancelTest() {
      this.activeTestKey = null
      this.testPoints = []
      this.clearTempPoints()
    },

    handleTestMapClick(position) {
      const lnglat = this.getLngLatFromClick(position)
      if (!lnglat || !this.activeTest) return

      this.testPoints.push(lnglat)
      this.addTempPoint(lnglat)

      if (this.testPoints.length >= this.activeTest.requiredPoints) {
        this.runTestAction(this.activeTestKey, [...this.testPoints])
        this.cancelTest()
      }
    },

    // =============================
    // 测试逻辑统一集中（方便后期统一注释）
    // =============================
    runTestAction(key, points) {
      const entityResult = this.entityManager.runTestAction(key, points)
      if (entityResult) return entityResult
      return this.drawManager.runTestAction(key, points)
    },

    removeEntity() {
      if (this.selectedEntity) {
        const objectId = this.selectedEntity._objectId
        if (this.selectedEntity._entityScope === 'draw') {
          this.drawManager.remove(this.selectedEntity)
        } else {
          this.entityManager.remove(this.selectedEntity)
        }
        if (objectId) {
          this.$store.dispatch('appendPlanCommands', [{
            message: 'success',
            function_name: 'delete_object',
            color: 'red',
            arguments: { id: objectId, object_id: objectId },
            missing: []
          }])
        }
        this.selectedEntity = null
      }
    }
  }
}
</script>

<style scoped lang="less">
html,
body,
#cesiumContainer {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
}

.button-container {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  gap: 10px;
  background-color: rgba(255, 255, 255, 0.88);
  padding: 10px;
  z-index: 100;
}

.plan-panel {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  gap: 10px;
  align-items: center;
  background-color: rgba(255, 255, 255, 0.9);
  padding: 10px 12px;
  z-index: 100;
}

.plan-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.plan-row label {
  white-space: nowrap;
  min-width: 64px;
}

.plan-panel :deep(.el-select) {
  min-width: 100px;
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.plan-actions button {
  padding: 4px 10px;
}

.plan-panel .save-btn {
  background-color: #2aa24a;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.plan-panel .save-btn:hover {
  background-color: #20883d;
}

.plan-panel .delete-plan-btn {
  background-color: #d84a3a;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.plan-panel .delete-plan-btn:hover {
  background-color: #bc3e30;
}

.button-container button {
  padding: 4px 10px;
  background-color: #e74c3c;
  color: #fff;
  border: none;
  border-radius: 5px;
  cursor: pointer;
}

.test-button-container {
  position: absolute;
  top: 50px;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background-color: rgba(0, 0, 0, 0.55);
  padding: 10px;
  border-radius: 0 0 8px 0;
  z-index: 99;
  max-height: calc(100vh - 80px);
  overflow: auto;
}

.test-title {
  color: #fff;
  font-size: 13px;
  margin-bottom: 4px;
}

.test-button-container button {
  padding: 6px 10px;
  background-color: #3498db;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  text-align: left;
}

.test-button-container button:hover {
  background-color: #2980b9;
}

.test-button-container .cancel-btn {
  background-color: #e67e22;
}

.tip-panel {
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 120;
  background: rgba(0, 0, 0, 0.7);
  color: #fff;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
}
</style>
