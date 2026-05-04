// EntityManager 负责实体模型的“放置、命名、删除”生命周期管理。
export default class EntityManager {
  constructor(viewer) {
    this.viewer = viewer
    // name -> entity 的索引，便于后续按名称定位实体。
    this.entityMap = {}
    this.objectMap = new Map()
    // 简单自增 ID，用于生成可读名称和标签。
    this.nextId = 1
    this.nextGroupId = 1
  }

  // 在地图上放置一个模型实体。
  // entityName: tank/car/aircraft/by
  // lnglatPoints: [lng, lat]
  placeModel(entityName, lnglatPoints, options = {}) {
    if (!this.viewer || !Array.isArray(lnglatPoints) || lnglatPoints.length < 2) return null

    // 业务实体类型到模型文件的映射关系。
    const modelMapping = {
      tank: 'tank.glb',
      car: 'car.glb',
      aircraft: 'aircraft.glb',
      by: 'soldier.glb'
    }

    const [lng, lat] = lnglatPoints
    const isAircraft = entityName === 'aircraft'
    const color = options.color || 'red'
    // 飞机模型抬高显示，地面实体贴地。
    const height = isAircraft ? 20000 : 0
    const id = this.nextId
    const objectId = options.id || this._createObjectId('entity')

    const entity = this.viewer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(lng, lat, height),
      name: `${entityName}${id}`,
      model: {
        uri: `./model/${modelMapping[entityName] || 'tank.glb'}`,
        scale: isAircraft ? 0.5 : 0.3,
        minimumPixelSize: 128,
        maximumScale: 100000,
        heightReference: isAircraft ? Cesium.HeightReference.NONE : Cesium.HeightReference.CLAMP_TO_GROUND,
        scaleByDistance: new Cesium.NearFarScalar(1.5e2, 2.0, 1.5e7, 0.5)
      },
      label: {
        text: `${entityName}_${id}`,
        font: '14pt monospace',
        fillColor: Cesium.Color.fromCssColorString(color),
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(0, -15)
      }
    })

    entity._objectId = objectId
    entity._groupId = objectId
    entity._color = color
    entity._keyPoints = [[lng, lat]]
    entity._selectionPoints = [[lng, lat]]
    entity._entityScope = 'entity'
    this.objectMap.set(objectId, entity)
    this.entityMap[entity.name] = entity
    this.nextId += 1
    return entity
  }

  executeCommand(fn, args = {}) {
    switch (fn) {
      case 'add_tank':
        return this.placeEntity({ ...args, entity_name: 'tank' })
      case 'add_aircraft':
        return this.placeEntity({ ...args, entity_name: 'aircraft' })
      case 'add_by':
        return this.placeEntity({ ...args, entity_name: 'by' })
      case 'add_car':
        return this.placeEntity({ ...args, entity_name: 'car' })
      case 'delete_entity':
      case 'delete_object':
        return this.removeById(args.id || args.entity_id || args.object_id)
      default:
        return null
    }
  }

  runTestAction(key, points) {
    switch (key) {
      case 'place_tank':
        return this.placeModel('tank', points[0])
      case 'place_aircraft':
        return this.placeModel('aircraft', points[0])
      case 'place_car':
        return this.placeModel('car', points[0])
      case 'place_by':
        return this.placeModel('by', points[0])
      default:
        return null
    }
  }

  placeEntity(args = {}) {
    const point = this._toPoint(args.lonlat)
    if (!point) throw new Error('add_* lonlat 非法')
    const objectId = String(args.id || args.entity_id || args.object_id || '').trim()
    if (!objectId) throw new Error('add_* id 必填')
    const name = String(args.entity_name || '').trim()
    if (!name) throw new Error('add_* entity_name 必填')
    return this.placeModel(name, point, {
      id: objectId,
      color: args.color || 'red'
    })
  }

  // 删除已选中的地图实体并同步清理索引。
  remove(entity) {
    if (!entity || !this.viewer) return
    return this.removeById(entity._objectId)
  }

  removeById(objectId) {
    if (!objectId || !this.viewer) return false
    const entity = this.objectMap.get(objectId) || this.viewer.entities.values.find((item) => item?._objectId === objectId)
    if (!entity) return false
    this.viewer.entities.remove(entity)
    this.objectMap.delete(objectId)
    if (entity?.name) delete this.entityMap[entity.name]
    return true
  }

  _createObjectId(prefix = 'entity') {
    const id = this.nextGroupId
    this.nextGroupId += 1
    return `${prefix}-${id}`
  }

  _toPoint(lonlat) {
    if (!Array.isArray(lonlat)) return null
    if (lonlat.length >= 2) {
      const lon = Number(lonlat[0])
      const lat = Number(lonlat[1])
      if (Number.isFinite(lon) && Number.isFinite(lat) && lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90) {
        return [lon, lat]
      }
    }
    if (Array.isArray(lonlat[0])) return this._toPoint(lonlat[0])
    return null
  }
}
