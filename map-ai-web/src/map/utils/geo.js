// 角度转弧度（地理计算基础函数）。
export function deg2rad(deg) {
  return deg * (Math.PI / 180)
}

// 计算两经纬度点球面距离（单位：米，Haversine 公式）。
export function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371000
  const dLat = deg2rad(lat2 - lat1)
  const dLon = deg2rad(lon2 - lon1)
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(deg2rad(lat1)) * Math.cos(deg2rad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
  return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)))
}

// 累计折线总长度（输入点格式：[[lng, lat], ...]）。
export function calculateTotalDistance(points = []) {
  if (!Array.isArray(points) || points.length < 2) return 0
  let totalDistance = 0
  for (let i = 0; i < points.length - 1; i++) {
    totalDistance += calculateDistance(points[i][1], points[i][0], points[i + 1][1], points[i + 1][0])
  }
  return totalDistance
}

// 经纬度点数组转 Cesium 笛卡尔坐标数组，供 polyline/polygon 直接使用。
export function toCartesianFromLngLat(points = [], height = 0) {
  return points.map((point) => Cesium.Cartesian3.fromDegrees(point[0], point[1], height))
}
