import { toCartesianFromLngLat } from '../utils/geo.js'
import * as Utils from '../../assets/js/utils.js'

// DrawManager 负责“图形/战术符号”绘制：
// 1) 提供基础图形（线、圆、多边形）
// 2) 提供战术图形（作战群、防御阵地、箭头符号）
// 3) 内部封装 point_test.py 迁移算法与几何工具方法
export default class DrawManager {
  constructor(viewer) {
    this.viewer = viewer
    // Catmull-Rom 张力系数，越大曲线越“紧”
    this.catmullTension = 0.7
    this.nextGroupId = 1
    this.objectMap = new Map()
  }

  // 通用曲线绘制（>=2 点）
  drawCurve(points, options = {}) {
    if (!this.viewer || !Array.isArray(points) || points.length < 2) return null

    const { color = 'red', width = 5, clampToGround = true } = options

    const entity = this.viewer.entities.add({
      polyline: {
        positions: toCartesianFromLngLat(points),
        width,
        clampToGround,
        material: Cesium.Color.fromCssColorString(color),
        arcType: Cesium.ArcType.GEODESIC
      }
    })
    return this._decorateEntity(entity, {
      groupId: options.groupId,
      selectionPoints: options.selectionPoints || points
    })
  }

  // 圆绘制（1 点圆心）
  drawCircle(center, options = {}) {
    if (!this.viewer || !Array.isArray(center) || center.length < 2) return null

    const { radius = 500, color = 'rgba(255, 0, 0, 0.35)' } = options

    const entity = this.viewer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(center[0], center[1]),
      ellipse: {
        semiMajorAxis: radius,
        semiMinorAxis: radius,
        material: Cesium.Color.fromCssColorString(color),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString(color).withAlpha(0.95),
        heightReference: Cesium.HeightReference.CLAMP_TO_GROUND
      }
    })
    return this._decorateEntity(entity, {
      groupId: options.groupId,
      selectionPoints: [center]
    })
  }

  // 多边形绘制（>=3 点）
  drawPolygon(points, options = {}) {
    if (!this.viewer || !Array.isArray(points) || points.length < 3) return null

    const { color = 'rgba(255, 0, 0, 0.35)', outlineColor = 'red' } = options

    const entity = this.viewer.entities.add({
      polygon: {
        hierarchy: Cesium.Cartesian3.fromDegreesArray(points.flat()),
        material: Cesium.Color.fromCssColorString(color),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString(outlineColor)
      }
    })
    return this._decorateEntity(entity, {
      groupId: options.groupId,
      selectionPoints: points
    })
  }

  // 作战群（3 点输入 -> 8 点闭合轮廓 -> 平滑闭合曲线）
  drawQun(points, options = {}) {
    const qunPoints = this._getQunPoints(points)
    if (!qunPoints) return null
    const objectId = options.id || this._createObjectId()
    return this.drawClosedCurve(qunPoints, {
      ...options,
      id: objectId,
      selectionPoints: qunPoints
    })
  }

  // 防御阵地（3 点输入 -> 5 组开放曲线）
  drawDefend(points, options = {}) {
    const groups = this._getDefendGroups(points)
    if (!groups) return []

    const entities = []
    const objectId = options.id || this._createObjectId()

    // 主脊线：保持开放曲线
    const main = this.drawOpenCurve(groups[0], {
      ...options,
      id: objectId,
      selectionPoints: groups[0],
      width: 4
    })
    if (main) entities.push(main)

    // 四条小曲线：前两条(曲度大)开放曲线；后两条(曲度小)双端曲线
    for (let i = 1; i <= 2; i++) {
      const branch = this.drawOpenCurve(groups[i], {
        ...options,
        id: objectId,
        selectionPoints: groups[i],
        width: 3
      })
      if (branch) entities.push(branch)
    }
    for (let i = 3; i <= 4; i++) {
      const branch = this.drawDoubleEndCurves(groups[i], {
        ...options,
        id: objectId,
        selectionPoints: groups[i],
        width: 3
      })
      if (Array.isArray(branch)) entities.push(...branch.filter(Boolean))
    }

    return entities
  }

  // 开放曲线（固定 3 点，二次贝塞尔）
  drawOpenCurve(points, options = {}) {
    if (!Array.isArray(points) || points.length !== 3) return null
    const curvePoints = this._sampleQuadraticCurve(points, options)
    return this._drawPolyline(curvePoints, {
      ...options,
      selectionPoints: options.selectionPoints || curvePoints
    })
  }

  // 闭合曲线（>=3 点，Catmull-Rom）
  drawClosedCurve(points, options = {}) {
    if (!Array.isArray(points) || points.length < 3) return null
    const curvePoints = this._getClosedCurvePoints(points, options.segments ?? 30)
    return this._drawPolyline(curvePoints, {
      ...options,
      selectionPoints: options.selectionPoints || curvePoints
    })
  }

  // 双端曲线（>=3 点主曲线 + 两端短线）
  drawDoubleEndCurves(points, options = {}) {
    if (!Array.isArray(points) || points.length < 3) return []
    const result = []
    const curvePoints = this._sampleQuadraticPath(points, options)

    if (curvePoints.length > 1) {
      result.push(this._drawPolyline(curvePoints, {
        ...options,
        selectionPoints: options.selectionPoints || curvePoints
      }))
    }

    const centroid = this._centroid(points)
    const firstTick = this._buildEndpointTick(points[0], points[1], centroid)
    const lastTick = this._buildEndpointTick(points[points.length - 1], points[points.length - 2], centroid)
    if (firstTick) result.push(this._drawPolyline(firstTick, { ...options, selectionPoints: firstTick }))
    if (lastTick) result.push(this._drawPolyline(lastTick, { ...options, selectionPoints: lastTick }))

    // 对齐 point_test.py 的视觉：在曲线头尾附近各补一条短线
    if (curvePoints.length >= 5) {
      const edgeLen = (this._distance(points[0], points[1]) + this._distance(points[points.length - 1], points[points.length - 2])) / 12
      const nearStartTick = this._buildCurveTick(curvePoints, 3, centroid, edgeLen)
      const nearEndTick = this._buildCurveTick(curvePoints, curvePoints.length - 4, centroid, edgeLen)
      if (nearStartTick) result.push(this._drawPolyline(nearStartTick, { ...options, selectionPoints: nearStartTick }))
      if (nearEndTick) result.push(this._drawPolyline(nearEndTick, { ...options, selectionPoints: nearEndTick }))
    }

    return result
  }

  // 箭头（2 点画直箭头，3/4 点画进攻箭头）
  drawArrow(points, options = {}) {
    const arrowPoints = this._getArrowPoints(points)
    return arrowPoints.length > 1 ? this._drawPolyline(arrowPoints, {
      ...options,
      id: options.id || this._createObjectId(),
      selectionPoints: arrowPoints
    }) : null
  }

  // 双尖头
  drawDoubleArrow(points, options = {}) {
    const arrowPoints = this._getDoubleArrowPoints(points)
    return arrowPoints.length > 1 ? this._drawPolyline(arrowPoints, {
      ...options,
      id: options.id || this._createObjectId(),
      selectionPoints: arrowPoints
    }) : null
  }

  runTestAction(key, points, options = {}) {
    switch (key) {
      case 'circle':
        return this.drawCircle(points[0], { radius: 500, color: 'rgba(0, 102, 255, 0.45)' })
      case 'line':
        return this.drawCurve(points, { color: 'yellow', width: 4 })
      case 'polygon':
        return this.drawPolygon(points, { color: 'rgba(46, 204, 113, 0.35)', outlineColor: '#2ecc71' })
      case 'labeled_line':
        return this.drawLabeledLine(points, ['out', 'in'], '#fa8c16')
      case 'arrow_2':
      case 'arrow_3':
      case 'arrow_4':
        return this.drawArrow(points, { color: 'red', width: 3 })
      case 'double_arrow':
      case 'double_arrow_3':
      case 'double_arrow_4':
        return this.drawDoubleArrow(points, { color: 'purple', width: 3 })
      case 'qun':
        return this.drawQun(points, { color: '#00ffff', width: 3 })
      case 'defend':
        return this.drawDefend(points, { color: '#ffa940', width: 3 })
      case 'open_curve':
        return this.drawOpenCurve(points, { color: '#52c41a', width: 3 })
      case 'close_curve':
        return this.drawClosedCurve(points, { color: '#722ed1', width: 3 })
      case 'double_end_curves':
        return this.drawDoubleEndCurves(points, { color: '#13c2c2', width: 3 })
      default:
        return null
    }
  }

  drawLabeledLine(points, include, color = 'red', options = {}) {
    if (!Array.isArray(points) || points.length < 2) throw new Error('draw_line 点位不足')
    const objectId = options.id || this._createObjectId()
    const result = []
    result.push(this.drawCurve(points, { color, width: 4, id: objectId, selectionPoints: points }))
    const labels = Array.isArray(include) ? include : []
    points.forEach((point, index) => {
      const mark = labels[index] === 'in' ? 'in' : 'out'
      const entity = this.viewer.entities.add({
        position: Cesium.Cartesian3.fromDegrees(point[0], point[1]),
        label: {
          text: mark,
          font: '14px sans-serif',
          fillColor: Cesium.Color.fromCssColorString(color),
          outlineColor: Cesium.Color.BLACK,
          outlineWidth: 2,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          pixelOffset: new Cesium.Cartesian2(0, -8)
        }
      })
      result.push(this._decorateEntity(entity, { id: objectId, color, keyPoints: [point], selectionPoints: [point] }))
    })
    return result.filter(Boolean)
  }

  executeCommand(fn, args = {}, options = {}) {
    const { color = 'red', id = args?.id || args?.graphic_id || args?.object_id } = options
    const deployCommands = new Set([
      'draw_group',
      'draw_defense',
      'draw_boundary',
      'draw_attack',
      'draw_attack_route',
      'draw_encirclement_attack'
    ])
    if (deployCommands.has(fn) && !String(id || '').trim()) {
      throw new Error(`${fn} id 必填`)
    }
    switch (fn) {
      case 'draw_group':
        return this.drawQun(args.lonlat, { color, width: 3, id })
      case 'draw_defense':
        return this.drawDefend(args.lonlat, { color, width: 3, id })
      case 'draw_boundary':
        return this.drawLabeledLine(args.lonlat, args.include, color, { id })
      case 'draw_attack':
        return this.drawArrow(args.lonlat, { color, width: 3, id })
      case 'draw_attack_route':
        return this.drawArrow(args.lonlat, { color, width: 3, id })
      case 'draw_encirclement_attack':
        return this.drawDoubleArrow(args.lonlat, { color, width: 3, id })
      case 'delete_graphic':
      case 'delete_draw':
      case 'delete_object':
        return this.removeById(args.id || args.graphic_id || args.object_id)
      default:
        return null
    }
  }

  // Catmull-Rom 闭合采样：
  // - 2 点时退化为线性插值
  // - >=3 点时做环形控制点扩展
  _getClosedCurvePoints(points, numSegments = 30) {
    if (points.length < 2) return points

    const curvePoints = []
    const tension = this.catmullTension

    if (points.length === 2) {
      for (let i = 0; i < numSegments; i++) {
        const t = i / (numSegments - 1)
        const x = points[0][0] * (1 - t) + points[1][0] * t
        const y = points[0][1] * (1 - t) + points[1][1] * t
        curvePoints.push([x, y])
      }
      return curvePoints
    }

    const controlPoints = points.slice(-2).concat(points).concat(points.slice(0, 2))

    for (let i = 1; i < controlPoints.length - 2; i++) {
      const p0 = controlPoints[i - 1]
      const p1 = controlPoints[i]
      const p2 = controlPoints[i + 1]
      const p3 = controlPoints[i + 2]

      for (let j = 0; j < numSegments; j++) {
        const t = j / (numSegments - 1)
        const t2 = t * t
        const t3 = t2 * t

        const m1 = -tension * t3 + 2 * tension * t2 - tension * t
        const m2 = (2 - tension) * t3 + (tension - 3) * t2 + 1
        const m3 = (tension - 2) * t3 + (3 - 2 * tension) * t2 + tension * t
        const m4 = tension * t3 - tension * t2

        const x = m1 * p0[0] + m2 * p1[0] + m3 * p2[0] + m4 * p3[0]
        const y = m1 * p0[1] + m2 * p1[1] + m3 * p2[1] + m4 * p3[1]
        curvePoints.push([x, y])
      }
    }

    return curvePoints
  }

  _getQunPoints(points) {
    const ordered = this._getTriangleFrame(points, 'longest')
    if (!ordered) return null

    const { A, B, lUnit, lLen, hUnit, hLen, C } = ordered
    return [
      this._add(this._sub(A, this._scale(lUnit, lLen / 4)), this._scale(hUnit, hLen / 2)),
      this._sub(A, this._scale(hUnit, hLen / 3)),
      this._add(this._add(A, this._scale(lUnit, lLen / 3)), this._scale(hUnit, hLen / 6)),
      this._add(this._sub(B, this._scale(lUnit, lLen / 3)), this._scale(hUnit, hLen / 6)),
      this._sub(B, this._scale(hUnit, hLen / 3)),
      this._add(this._add(B, this._scale(lUnit, lLen / 4)), this._scale(hUnit, hLen / 2)),
      this._add(this._add(C, this._scale(hUnit, hLen / 4)), this._scale(lUnit, lLen / 4)),
      this._sub(this._add(C, this._scale(hUnit, hLen / 4)), this._scale(lUnit, lLen / 4))
    ]
  }

  _getArrowPoints(points) {
    if (!Array.isArray(points)) return []
    if (points.length === 2) return this._getFineArrowPoints(points)
    if (points.length === 3 || points.length === 4) return this._getAttackArrowPoints(points)
    return []
  }

  _getFineArrowPoints(points) {
    if (!Array.isArray(points) || points.length !== 2) return []

    const [p1, p2] = points
    const len = Utils.getBaseLength([p1, p2])
    const tailWidth = len * 0.1
    const neckWidth = len * 0.2
    const headWidth = len * 0.25
    const tailLeft = Utils.getThirdPoint(p2, p1, Math.PI / 2, tailWidth, true)
    const tailRight = Utils.getThirdPoint(p2, p1, Math.PI / 2, tailWidth, false)
    const headLeft = Utils.getThirdPoint(p1, p2, Math.PI / 8.5, headWidth, false)
    const headRight = Utils.getThirdPoint(p1, p2, Math.PI / 8.5, headWidth, true)
    const neckLeft = Utils.getThirdPoint(p1, p2, Math.PI / 13, neckWidth, false)
    const neckRight = Utils.getThirdPoint(p1, p2, Math.PI / 13, neckWidth, true)
    return [tailLeft, neckLeft, headLeft, p2, headRight, neckRight, tailRight]
  }

  _getAttackArrowPoints(points) {
    if (!Array.isArray(points) || points.length < 3 || points.length > 4) return []

    const allLen = Utils.getBaseLength(points)
    const tailWidth = allLen * 0.1
    let tailLeft = Utils.getThirdPoint(points[1], points[0], Math.PI / 2, tailWidth, true)
    let tailRight = Utils.getThirdPoint(points[1], points[0], Math.PI / 2, tailWidth, false)
    if (Utils.isClockWise(tailLeft, tailRight, points[1])) {
      [tailLeft, tailRight] = [tailRight, tailLeft]
    }

    const bonePoints = points
    const headPoints = this._getArrowHeadPoints(bonePoints, tailLeft, tailRight)
    if (!headPoints.length) return []

    const [neckLeft, neckRight] = [headPoints[0], headPoints[4]]
    const factor = tailWidth / allLen
    const bodyPoints = this._getArrowBodyPoints(bonePoints, neckLeft, neckRight, factor)
    const count = bodyPoints.length
    let leftPoints = [tailLeft].concat(bodyPoints.slice(0, count / 2))
    let rightPoints = [tailRight].concat(bodyPoints.slice(count / 2, count))
    leftPoints.push(neckLeft)
    rightPoints.push(neckRight)
    leftPoints = Utils.getQBSplinePoints(leftPoints)
    rightPoints = Utils.getQBSplinePoints(rightPoints)
    return leftPoints.concat(headPoints, rightPoints.reverse())
  }

  _getDoubleArrowPoints(points) {
    if (!Array.isArray(points) || points.length < 3 || points.length > 4) return []

    const [p1, p2, p3] = [points[0], points[1], points[2]]
    const count = points.length
    const connPoint = Utils.Mid(p1, p2)
    // 4 点时先重排目标点，尽量让 1/2 分别指向更近的目标，减少交叉。
    const [targetA, targetB] = count === 4
      ? this._orderDoubleArrowTargets(p1, p2, p3, points[3])
      : [p3, p3]
    // 3 点时两个箭头不再共用同一尖端，而是在终点附近左右分开一点。
    const [tipA, tipB] = count === 3
      ? this._splitDoubleArrowTips(connPoint, p3, this._distance(p1, p2) / 10)
      : [targetA, targetB]

    let leftArrowPoints
    let rightArrowPoints
    const isClockWise = Utils.isClockWise(p1, p2, p3)
    if (isClockWise) {
      leftArrowPoints = this._getSingleArrowPoints(p1, connPoint, tipA, false)
      rightArrowPoints = this._getSingleArrowPoints(connPoint, p2, tipB, true)
    } else {
      leftArrowPoints = this._getSingleArrowPoints(p2, connPoint, tipA, false)
      rightArrowPoints = this._getSingleArrowPoints(connPoint, p1, tipB, true)
    }
    if (!leftArrowPoints.length || !rightArrowPoints.length) return []

    const m = leftArrowPoints.length
    const t = (m - 5) / 2
    const llBodyPoints = leftArrowPoints.slice(0, t)
    const leftHeadPoints = leftArrowPoints.slice(t, t + 5)
    let lrBodyPoints = leftArrowPoints.slice(t + 5, m)

    let rlBodyPoints = rightArrowPoints.slice(0, t)
    const rightHeadPoints = rightArrowPoints.slice(t, t + 5)
    const rrBodyPoints = rightArrowPoints.slice(t + 5, m)

    rlBodyPoints = Utils.getBezierPoints(rlBodyPoints)
    const bodyPoints = Utils.getBezierPoints(rrBodyPoints.concat(llBodyPoints.slice(1)))
    lrBodyPoints = Utils.getBezierPoints(lrBodyPoints)
    return rlBodyPoints.concat(rightHeadPoints, bodyPoints, leftHeadPoints, lrBodyPoints)
  }

  _orderDoubleArrowTargets(p1, p2, p3, p4) {
    const direct = this._distance(p1, p3) + this._distance(p2, p4)
    const crossed = this._distance(p1, p4) + this._distance(p2, p3)
    return direct <= crossed ? [p3, p4] : [p4, p3]
  }

  _splitDoubleArrowTips(connPoint, tipPoint, gap) {
    const dir = this._sub(tipPoint, connPoint)
    const len = this._norm(dir)
    if (!len || !gap) return [tipPoint, tipPoint]

    const perpUnit = [-dir[1] / len, dir[0] / len]
    const halfGap = gap / 2
    return [
      this._add(tipPoint, this._scale(perpUnit, halfGap)),
      this._sub(tipPoint, this._scale(perpUnit, halfGap))
    ]
  }

  _getDefendGroups(points) {
    const ordered = this._getTriangleFrame(points, 'shortest')
    if (!ordered) return null

    const { A, B, C, lUnit, lLen, hUnit, hLen } = ordered
    return [
      [
        this._sub(B, this._scale(lUnit, lLen / 5)),
        this._add(A, this._scale(hUnit, hLen / 5)),
        this._add(C, this._scale(lUnit, lLen / 5))
      ],
      [
        this._sub(B, this._scale(lUnit, lLen * 0.2)),
        this._add(this._add(B, this._scale(lUnit, lLen * 0.1)), this._scale(hUnit, hLen * 0.15)),
        this._add(B, this._scale(lUnit, lLen * 0.4))
      ],
      [
        this._sub(C, this._scale(lUnit, lLen * 0.4)),
        this._add(this._sub(C, this._scale(lUnit, lLen * 0.1)), this._scale(hUnit, hLen * 0.15)),
        this._add(C, this._scale(lUnit, lLen * 0.2))
      ],
      [
        this._sub(this._sub(B, this._scale(lUnit, lLen * 0.2)), this._scale(hUnit, hLen * 0.015)),
        this._sub(this._add(B, this._scale(lUnit, lLen * 0.1)), this._scale(hUnit, hLen * 0.05)),
        this._sub(this._add(B, this._scale(lUnit, lLen * 0.4)), this._scale(hUnit, hLen * 0.015))
      ],
      [
        this._sub(this._sub(C, this._scale(lUnit, lLen * 0.4)), this._scale(hUnit, hLen * 0.015)),
        this._sub(this._sub(C, this._scale(lUnit, lLen * 0.1)), this._scale(hUnit, hLen * 0.05)),
        this._sub(this._add(C, this._scale(lUnit, lLen * 0.2)), this._scale(hUnit, hLen * 0.015))
      ]
    ]
  }

  _getTriangleFrame(points, edgeMode) {
    if (!Array.isArray(points) || points.length !== 3) return null

    const [pA, pB, pC] = points
    const edges = [
      { len: this._distance(pA, pB), pair: [pA, pB], other: pC },
      { len: this._distance(pB, pC), pair: [pB, pC], other: pA },
      { len: this._distance(pC, pA), pair: [pC, pA], other: pB }
    ]
    const target = edges.reduce((best, edge) => {
      if (!best) return edge
      return edgeMode === 'shortest'
        ? (edge.len < best.len ? edge : best)
        : (edge.len > best.len ? edge : best)
    }, null)
    if (edgeMode === 'shortest') {
      const [B, C] = target.pair
      const A = target.other
      return { A, B, C, ...this._buildFrame(B, C, A) }
    }

    const [A, B] = target.pair
    const C = target.other
    return { A, B, C, ...this._buildFrame(A, B, C) }
  }

  // 把三角形归一成“基线方向 + 高方向”，后续群部署/防御阵地都复用这套局部坐标。
  _buildFrame(A, B, C) {
    const lVec = this._sub(B, A)
    const lLen = this._norm(lVec)
    const lUnit = lLen ? this._scale(lVec, 1 / lLen) : [1, 0]
    const AC = this._sub(C, A)
    const proj = this._scale(lUnit, this._dot(AC, lUnit))
    const hVec = this._sub(AC, proj)
    const hLen = this._norm(hVec)
    const hUnit = hLen ? this._scale(hVec, 1 / hLen) : [0, 1]
    return { lUnit, lLen, hUnit, hLen }
  }

  _sampleQuadraticPath(points, options = {}) {
    const curvePoints = []
    for (let i = 0; i < points.length - 2; i++) {
      curvePoints.push(...this._sampleQuadraticCurve(points.slice(i, i + 3), {
        ...options,
        segments: options.segments ?? 30
      }))
    }
    return curvePoints
  }

  _getArrowHeadPoints(points, tailLeft, tailRight) {
    if (!Array.isArray(points) || points.length < 2) return []

    let len = Utils.getBaseLength(points)
    let headHeight = len * 0.8
    const headPoint = points[points.length - 1]
    len = Utils.MathDistance(headPoint, points[points.length - 2])
    const tailWidth = Utils.MathDistance(tailLeft, tailRight)
    if (headHeight > tailWidth * 0.8) headHeight = tailWidth * 0.8

    const headWidth = headHeight * 0.3
    const neckWidth = headHeight * 0.15
    headHeight = headHeight > len ? len : headHeight
    const neckHeight = headHeight * 0.85
    const headEndPoint = Utils.getThirdPoint(points[points.length - 2], headPoint, 0, headHeight, true)
    const neckEndPoint = Utils.getThirdPoint(points[points.length - 2], headPoint, 0, neckHeight, true)
    const headLeft = Utils.getThirdPoint(headPoint, headEndPoint, Math.PI / 2, headWidth, false)
    const headRight = Utils.getThirdPoint(headPoint, headEndPoint, Math.PI / 2, headWidth, true)
    const neckLeft = Utils.getThirdPoint(headPoint, neckEndPoint, Math.PI / 2, neckWidth, false)
    const neckRight = Utils.getThirdPoint(headPoint, neckEndPoint, Math.PI / 2, neckWidth, true)
    return [neckLeft, headLeft, headPoint, headRight, neckRight]
  }

  _getArrowBodyPoints(points, neckLeft, neckRight, tailWidthFactor) {
    const allLen = Utils.wholeDistance(points)
    const len = Utils.getBaseLength(points)
    const tailWidth = len * tailWidthFactor
    const neckWidth = Utils.MathDistance(neckLeft, neckRight)
    const widthDiff = (tailWidth - neckWidth) / 2
    let tempLen = 0
    const leftBodyPoints = []
    const rightBodyPoints = []
    for (let i = 1; i < points.length - 1; i++) {
      const angle = Utils.getAngleOfThreePoints(points[i - 1], points[i], points[i + 1]) / 2
      tempLen += Utils.MathDistance(points[i - 1], points[i])
      const width = (tailWidth / 2 - (tempLen / allLen) * widthDiff) / Math.sin(angle)
      const left = Utils.getThirdPoint(points[i - 1], points[i], Math.PI - angle, width, true)
      const right = Utils.getThirdPoint(points[i - 1], points[i], angle, width, false)
      leftBodyPoints.push(left)
      rightBodyPoints.push(right)
    }
    return leftBodyPoints.concat(rightBodyPoints)
  }

  _getDoubleArrowHeadPoints(points) {
    const len = Utils.getBaseLength(points)
    const headHeight = len * 0.25
    const headPoint = points[points.length - 1]
    const headWidth = headHeight * 0.3
    const neckWidth = headHeight * 0.15
    const neckHeight = headHeight * 0.85
    const headEndPoint = Utils.getThirdPoint(points[points.length - 2], headPoint, 0, headHeight, true)
    const neckEndPoint = Utils.getThirdPoint(points[points.length - 2], headPoint, 0, neckHeight, true)
    const headLeft = Utils.getThirdPoint(headPoint, headEndPoint, Math.PI / 2, headWidth, false)
    const headRight = Utils.getThirdPoint(headPoint, headEndPoint, Math.PI / 2, headWidth, true)
    const neckLeft = Utils.getThirdPoint(headPoint, neckEndPoint, Math.PI / 2, neckWidth, false)
    const neckRight = Utils.getThirdPoint(headPoint, neckEndPoint, Math.PI / 2, neckWidth, true)
    return [neckLeft, headLeft, headPoint, headRight, neckRight]
  }

  _getDoubleArrowBodyPoints(points, neckLeft, neckRight, tailWidthFactor) {
    const allLen = Utils.wholeDistance(points)
    const len = Utils.getBaseLength(points)
    const tailWidth = len * tailWidthFactor
    const neckWidth = Utils.MathDistance(neckLeft, neckRight)
    const widthDiff = (tailWidth - neckWidth) / 2
    let tempLen = 0
    const leftBodyPoints = []
    const rightBodyPoints = []
    for (let i = 1; i < points.length - 1; i++) {
      const angle = Utils.getAngleOfThreePoints(points[i - 1], points[i], points[i + 1]) / 2
      tempLen += Utils.MathDistance(points[i - 1], points[i])
      const width = (tailWidth / 2 - (tempLen / allLen) * widthDiff) / Math.sin(angle)
      const left = Utils.getThirdPoint(points[i - 1], points[i], Math.PI - angle, width, true)
      const right = Utils.getThirdPoint(points[i - 1], points[i], angle, width, false)
      leftBodyPoints.push(left)
      rightBodyPoints.push(right)
    }
    return leftBodyPoints.concat(rightBodyPoints)
  }

  _getSingleArrowPoints(point1, point2, point3, clockWise) {
    const midPoint = Utils.Mid(point1, point2)
    const len = Utils.MathDistance(midPoint, point3)
    let midPoint1 = Utils.getThirdPoint(point3, midPoint, 0, len * 0.3, true)
    let midPoint2 = Utils.getThirdPoint(point3, midPoint, 0, len * 0.5, true)
    midPoint1 = Utils.getThirdPoint(midPoint, midPoint1, Math.PI / 2, len / 5, clockWise)
    midPoint2 = Utils.getThirdPoint(midPoint, midPoint2, Math.PI / 2, len / 4, clockWise)
    const points = [midPoint, midPoint1, midPoint2, point3]
    const arrowPoints = this._getDoubleArrowHeadPoints(points)
    if (!arrowPoints.length) return []

    const neckLeftPoint = arrowPoints[0]
    const neckRightPoint = arrowPoints[4]
    const tailWidthFactor = Utils.MathDistance(point1, point2) / Utils.getBaseLength(points) / 2
    const bodyPoints = this._getDoubleArrowBodyPoints(points, neckLeftPoint, neckRightPoint, tailWidthFactor)
    if (!bodyPoints.length) return []

    const count = bodyPoints.length
    let leftPoints = bodyPoints.slice(0, count / 2)
    let rightPoints = bodyPoints.slice(count / 2, count)
    leftPoints.push(neckLeftPoint)
    rightPoints.push(neckRightPoint)
    leftPoints = leftPoints.reverse()
    leftPoints.push(point2)
    rightPoints = rightPoints.reverse()
    rightPoints.push(point1)
    return leftPoints.reverse().concat(arrowPoints, rightPoints)
  }

  _sampleQuadraticCurve(points, options = {}) {
    if (!Array.isArray(points) || points.length !== 3) return []

    const [p0, p1, p2] = points
    const alpha = options.alpha ?? 2.0
    const segments = options.segments ?? 160
    const mx = 0.5 * (p0[0] + p2[0])
    const my = 0.5 * (p0[1] + p2[1])
    const cx = mx + alpha * (p1[0] - mx)
    const cy = my + alpha * (p1[1] - my)

    const curvePoints = []
    for (let i = 0; i <= segments; i++) {
      const t = i / segments
      const omt = 1 - t
      const x = omt * omt * p0[0] + 2 * omt * t * cx + t * t * p2[0]
      const y = omt * omt * p0[1] + 2 * omt * t * cy + t * t * p2[1]
      curvePoints.push([x, y])
    }
    return curvePoints
  }

  // 底层 polyline 渲染封装
  _drawPolyline(points, options = {}) {
    if (!this.viewer || !Array.isArray(points) || points.length < 2) return null

    const entity = this.viewer.entities.add({
      polyline: {
        positions: toCartesianFromLngLat(points),
        width: options.width ?? 3,
        clampToGround: options.clampToGround ?? true,
        material: Cesium.Color.fromCssColorString(options.color || 'red'),
        arcType: Cesium.ArcType.GEODESIC
      }
    })
    return this._decorateEntity(entity, {
      id: options.id || this._createObjectId(),
      color: options.color || 'red',
      keyPoints: options.keyPoints || options.selectionPoints || points,
      selectionPoints: options.selectionPoints || points
    })
  }

  _decorateEntity(entity, options = {}) {
    if (!entity) return null
    entity._objectId = options.id || this._createObjectId()
    entity._groupId = entity._objectId
    entity._color = options.color || 'red'
    entity._keyPoints = Array.isArray(options.keyPoints) ? options.keyPoints : []
    entity._selectionPoints = Array.isArray(options.selectionPoints) ? options.selectionPoints : []
    entity._entityScope = 'draw'
    this._registerEntity(entity)
    return entity
  }

  _createObjectId(prefix = 'draw') {
    const id = this.nextGroupId
    this.nextGroupId += 1
    return `${prefix}-${id}`
  }

  _registerEntity(entity) {
    const objectId = entity?._objectId
    if (!objectId) return
    const current = this.objectMap.get(objectId) || []
    current.push(entity)
    this.objectMap.set(objectId, current)
  }

  remove(entity) {
    if (!entity) return
    return this.removeById(entity._objectId)
  }

  removeById(objectId) {
    if (!objectId || !this.viewer) return false
    const entities = this.objectMap.get(objectId) || this.viewer.entities.values.filter((item) => item?._objectId === objectId)
    if (!entities.length) return false
    entities.forEach((entity) => this.viewer.entities.remove(entity))
    this.objectMap.delete(objectId)
    return true
  }

  // 求点集重心，用于判断端点短线外扩方向
  _centroid(points) {
    const sum = points.reduce((acc, p) => [acc[0] + p[0], acc[1] + p[1]], [0, 0])
    return [sum[0] / points.length, sum[1] / points.length]
  }

  // 根据端点方向构造“向外”的短线
  _buildEndpointTick(p0, p1, centroid) {
    const dir = this._sub(p1, p0)
    const l = this._norm(dir)
    if (!l) return null

    let perp = this._scale([-dir[1], dir[0]], 1 / l)
    const toCentroid = this._sub(centroid, p0)
    if (this._dot(perp, toCentroid) < 0) perp = this._scale(perp, -1)

    const segLen = l / 5
    const end = this._sub(p0, this._scale(perp, segLen))
    return [p0, end]
  }

  // 在曲线上某个采样点处，基于局部切线方向构造“向外”短线
  _buildCurveTick(curvePoints, index, centroid, segLen) {
    if (index <= 0 || index >= curvePoints.length - 1) return null
    const p = curvePoints[index]
    const prev = curvePoints[index - 1]
    const next = curvePoints[index + 1]
    const dir = this._sub(next, prev)
    const l = this._norm(dir)
    if (!l) return null

    let perp = this._scale([-dir[1], dir[0]], 1 / l)
    const toCentroid = this._sub(centroid, p)
    if (this._dot(perp, toCentroid) < 0) perp = this._scale(perp, -1)

    const end = this._sub(p, this._scale(perp, segLen))
    return [p, end]
  }

  // ===== 向量工具函数 =====
  _sub(a, b) { return [a[0] - b[0], a[1] - b[1]] }
  _add(a, b) { return [a[0] + b[0], a[1] + b[1]] }
  _scale(v, s) { return [v[0] * s, v[1] * s] }
  _dot(a, b) { return a[0] * b[0] + a[1] * b[1] }
  _norm(v) { return Math.hypot(v[0], v[1]) }
  _distance(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1]) }
}
