// "use strict";
// Object.defineProperty(exports, "__esModule", { value: true });
// exports.bindAll = exports.preventDefault = exports.merge = exports.isObject = exports.splitWords = exports.trim = exports.stamp = exports.getuuid = exports.getQuadricBSplineFactor = exports.getQBSplinePoints = exports.getBinomialFactor = exports.getFactorial = exports.getBezierPoints = exports.getCurvePoints = exports.getRightMostControlPoint = exports.getLeftMostControlPoint = exports.getNormal = exports.getBisectorNormals = exports.getArcPoints = exports.getThirdPoint = exports.getCubicValue = exports.getPointOnLine = exports.isClockWise = exports.getAngleOfThreePoints = exports.getAzimuth = exports.getIntersectPoint = exports.getCircleCenterOfThreePoints = exports.Mid = exports.getBaseLength = exports.wholeDistance = exports.MathDistance = void 0;
var FITTING_COUNT = 100;
var ZERO_TOLERANCE = 0.0001;
/**
 * 计算两个坐标之间的距离
 * @param pnt1
 * @param pnt2
 * @returns {number}
 * @constructor
 */
export const MathDistance =  (pnt1, pnt2)=> { return Math.sqrt(Math.pow((pnt1[0] - pnt2[0]), 2) + Math.pow((pnt1[1] - pnt2[1]), 2)); };
// exports.MathDistance = MathDistance;
/**
 * 计算点集合的总距离
 * @param points
 * @returns {number}
 */
// 计算点集的总长度
export const wholeDistance = (points) => {
  let distance = 0;
  if (Array.isArray(points) && points.length > 1) { // 确保 points 是有效的数组且至少有两个点
    for (let i = 0; i < points.length - 1; i++) {
      distance += MathDistance(points[i], points[i + 1]); // 使用本文件中的 MathDistance 函数
    }
  }
  return distance;
};

// exports.wholeDistance = wholeDistance;
/**
 * 获取基础长度
 * @param points
 * @returns {number}
 */
export const getBaseLength = (points) => {
  return Math.pow(wholeDistance(points), 0.99); // 使用本文件中的 wholeDistance 函数
};
// exports.getBaseLength = getBaseLength;
/**
 * 求取两个坐标的中间值
 * @param point1
 * @param point2
 * @returns {[*,*]}
 * @constructor
 */
export const  Mid =  (point1, point2) =>{ return [(point1[0] + point2[0]) / 2, (point1[1] + point2[1]) / 2]; };
// exports.Mid = Mid;
/**
 * 通过三个点确定一个圆的中心点
 * @param point1
 * @param point2
 * @param point3
 */
export const getCircleCenterOfThreePoints =  (point1, point2, point3)=> {
    var pntA = [(point1[0] + point2[0]) / 2, (point1[1] + point2[1]) / 2];
    var pntB = [pntA[0] - point1[1] + point2[1], pntA[1] + point1[0] - point2[0]];
    var pntC = [(point1[0] + point3[0]) / 2, (point1[1] + point3[1]) / 2];
    var pntD = [pntC[0] - point1[1] + point3[1], pntC[1] + point1[0] - point3[0]];
    // eslint-disable-next-line @typescript-eslint/no-use-before-define
    return getIntersectPoint(pntA, pntB, pntC, pntD);
};
// exports.getCircleCenterOfThreePoints = getCircleCenterOfThreePoints;
/**
 * 获取交集的点
 * @param pntA
 * @param pntB
 * @param pntC
 * @param pntD
 * @returns {[*,*]}
 */
export const getIntersectPoint =  (pntA, pntB, pntC, pntD) =>{
    if (pntA[1] === pntB[1]) {
        var f_1 = (pntD[0] - pntC[0]) / (pntD[1] - pntC[1]);
        var x_1 = f_1 * (pntA[1] - pntC[1]) + pntC[0];
        var y_1 = pntA[1];
        return [x_1, y_1];
    }
    if (pntC[1] === pntD[1]) {
        var e_1 = (pntB[0] - pntA[0]) / (pntB[1] - pntA[1]);
        var x_2 = e_1 * (pntC[1] - pntA[1]) + pntA[0];
        var y_2 = pntC[1];
        return [x_2, y_2];
    }
    var e = (pntB[0] - pntA[0]) / (pntB[1] - pntA[1]);
    var f = (pntD[0] - pntC[0]) / (pntD[1] - pntC[1]);
    var y = (e * pntA[1] - pntA[0] - f * pntC[1] + pntC[0]) / (e - f);
    var x = e * y - e * pntA[1] + pntA[0];
    return [x, y];
};
// exports.getIntersectPoint = getIntersectPoint;
/**
 * 获取方位角（地平经度）
 * @param startPoint
 * @param endPoint
 * @returns {*}
 */
export const getAzimuth =  (startPoint, endPoint) =>{
    var azimuth;
    var angle = Math.asin(Math.abs(endPoint[1] - startPoint[1]) / MathDistance(startPoint, endPoint));
    if (endPoint[1] >= startPoint[1] && endPoint[0] >= startPoint[0]) {
        azimuth = angle + Math.PI;
    }
    else if (endPoint[1] >= startPoint[1] && endPoint[0] < startPoint[0]) {
        azimuth = Math.PI * 2 - angle;
    }
    else if (endPoint[1] < startPoint[1] && endPoint[0] < startPoint[0]) {
        azimuth = angle;
    }
    else if (endPoint[1] < startPoint[1] && endPoint[0] >= startPoint[0]) {
        azimuth = Math.PI - angle;
    }
    return azimuth;
};
// exports.getAzimuth = getAzimuth;
/**
 * 通过三个点获取方位角
 * @param pntA
 * @param pntB
 * @param pntC
 * @returns {number}
 */
export const getAngleOfThreePoints =  (pntA, pntB, pntC)=>{
    var angle = getAzimuth(pntB, pntA) - getAzimuth(pntB, pntC);
    return angle < 0 ? angle + Math.PI * 2 : angle;
};
// exports.getAngleOfThreePoints = getAngleOfThreePoints;
/**
 * 判断是否是顺时针
 * @param pnt1
 * @param pnt2
 * @param pnt3
 * @returns {boolean}
 */
export const isClockWise =  (pnt1, pnt2, pnt3) =>{
    return (pnt3[1] - pnt1[1]) * (pnt2[0] - pnt1[0]) > (pnt2[1] - pnt1[1]) * (pnt3[0] - pnt1[0]);
};
// exports.isClockWise = isClockWise;
/**
 * 获取线上的点
 * @param t
 * @param startPnt
 * @param endPnt
 * @returns {[*,*]}
 */
export const getPointOnLine =  (t, startPnt, endPnt) =>{
    var x = startPnt[0] + t * (endPnt[0] - startPnt[0]);
    var y = startPnt[1] + t * (endPnt[1] - startPnt[1]);
    return [x, y];
};
// exports.getPointOnLine = getPointOnLine;
/**
 * 获取立方值
 * @param t
 * @param startPnt
 * @param cPnt1
 * @param cPnt2
 * @param endPnt
 * @returns {[*,*]}
 */
export const getCubicValue =  (t, startPnt, cPnt1, cPnt2, endPnt) =>{
    // eslint-disable-next-line no-param-reassign
    t = Math.max(Math.min(t, 1), 0);
    var _a = [1 - t, t * t], tp = _a[0], t2 = _a[1];
    var t3 = t2 * t;
    var tp2 = tp * tp;
    var tp3 = tp2 * tp;
    var x = tp3 * startPnt[0] + 3 * tp2 * t * cPnt1[0] + 3 * tp * t2 * cPnt2[0] + t3 * endPnt[0];
    var y = tp3 * startPnt[1] + 3 * tp2 * t * cPnt1[1] + 3 * tp * t2 * cPnt2[1] + t3 * endPnt[1];
    return [x, y];
};
// exports.getCubicValue = getCubicValue;
/**
 * 根据起止点和旋转方向求取第三个点
 * @param startPnt
 * @param endPnt
 * @param angle
 * @param distance
 * @param clockWise
 * @returns {[*,*]}
 */
export const getThirdPoint =  (startPnt, endPnt, angle, distance, clockWise)=> {
    var azimuth = getAzimuth(startPnt, endPnt);
    var alpha = clockWise ? azimuth + angle : azimuth - angle;
    var dx = distance * Math.cos(alpha);
    var dy = distance * Math.sin(alpha);
    return [endPnt[0] + dx, endPnt[1] + dy];
};
// exports.getThirdPoint = getThirdPoint;
/**
 * 插值弓形线段点
 * @param center
 * @param radius
 * @param startAngle
 * @param endAngle
 * @returns {null}
 */
export const getArcPoints =  (center, radius, startAngle, endAngle) =>{
    // eslint-disable-next-line
    var _a = [null, null, [], endAngle - startAngle], x = _a[0], y = _a[1], pnts = _a[2], angleDiff = _a[3];
    angleDiff = angleDiff < 0 ? angleDiff + Math.PI * 2 : angleDiff;
    for (var i = 0; i <= 100; i++) {
        var angle = startAngle + (angleDiff * i) / 100;
        x = center[0] + radius * Math.cos(angle);
        y = center[1] + radius * Math.sin(angle);
        pnts.push([x, y]);
    }
    return pnts;
};
// exports.getArcPoints = getArcPoints;
/**
 * getBisectorNormals
 * @param t
 * @param pnt1
 * @param pnt2
 * @param pnt3
 * @returns {[*,*]}
 */
export const getBisectorNormals =  (t, pnt1, pnt2, pnt3) =>{
    // eslint-disable-next-line
    var normal = getNormal(pnt1, pnt2, pnt3);
    var _a = [null, null, null, null, null], bisectorNormalRight = _a[0], bisectorNormalLeft = _a[1], dt = _a[2], x = _a[3], y = _a[4];
    var dist = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
    var uX = normal[0] / dist;
    var uY = normal[1] / dist;
    var d1 = MathDistance(pnt1, pnt2);
    var d2 = MathDistance(pnt2, pnt3);
    if (dist > ZERO_TOLERANCE) {
        if (isClockWise(pnt1, pnt2, pnt3)) {
            dt = t * d1;
            x = pnt2[0] - dt * uY;
            y = pnt2[1] + dt * uX;
            bisectorNormalRight = [x, y];
            dt = t * d2;
            x = pnt2[0] + dt * uY;
            y = pnt2[1] - dt * uX;
            bisectorNormalLeft = [x, y];
        }
        else {
            dt = t * d1;
            x = pnt2[0] + dt * uY;
            y = pnt2[1] - dt * uX;
            bisectorNormalRight = [x, y];
            dt = t * d2;
            x = pnt2[0] - dt * uY;
            y = pnt2[1] + dt * uX;
            bisectorNormalLeft = [x, y];
        }
    }
    else {
        x = pnt2[0] + t * (pnt1[0] - pnt2[0]);
        y = pnt2[1] + t * (pnt1[1] - pnt2[1]);
        bisectorNormalRight = [x, y];
        x = pnt2[0] + t * (pnt3[0] - pnt2[0]);
        y = pnt2[1] + t * (pnt3[1] - pnt2[1]);
        bisectorNormalLeft = [x, y];
    }
    return [bisectorNormalRight, bisectorNormalLeft];
};
// exports.getBisectorNormals = getBisectorNormals;
/**
 * 获取默认三点的内切圆
 * @param pnt1
 * @param pnt2
 * @param pnt3
 * @returns {[*,*]}
 */
export const getNormal =  (pnt1, pnt2, pnt3) =>{
    var dX1 = pnt1[0] - pnt2[0];
    var dY1 = pnt1[1] - pnt2[1];
    var d1 = Math.sqrt(dX1 * dX1 + dY1 * dY1);
    dX1 /= d1;
    dY1 /= d1;
    var dX2 = pnt3[0] - pnt2[0];
    var dY2 = pnt3[1] - pnt2[1];
    var d2 = Math.sqrt(dX2 * dX2 + dY2 * dY2);
    dX2 /= d2;
    dY2 /= d2;
    var uX = dX1 + dX2;
    var uY = dY1 + dY2;
    return [uX, uY];
};
// exports.getNormal = getNormal;
/**
 * 获取左边控制点
 * @param controlPoints
 * @param t
 * @returns {[*,*]}
 */
export const  getLeftMostControlPoint =  (controlPoints, t) =>{
    // eslint-disable-next-line
    var _a = [controlPoints[0], controlPoints[1], controlPoints[2], null, null], pnt1 = _a[0], pnt2 = _a[1], pnt3 = _a[2], controlX = _a[3], controlY = _a[4];
    var pnts = getBisectorNormals(0, pnt1, pnt2, pnt3);
    var normalRight = pnts[0];
    var normal = getNormal(pnt1, pnt2, pnt3);
    var dist = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
    if (dist > ZERO_TOLERANCE) {
        var mid = Mid(pnt1, pnt2);
        var pX = pnt1[0] - mid[0];
        var pY = pnt1[1] - mid[1];
        var d1 = MathDistance(pnt1, pnt2);
        var n = 2.0 / d1;
        var nX = -n * pY;
        var nY = n * pX;
        var a11 = nX * nX - nY * nY;
        var a12 = 2 * nX * nY;
        var a22 = nY * nY - nX * nX;
        var dX = normalRight[0] - mid[0];
        var dY = normalRight[1] - mid[1];
        controlX = mid[0] + a11 * dX + a12 * dY;
        controlY = mid[1] + a12 * dX + a22 * dY;
    }
    else {
        controlX = pnt1[0] + t * (pnt2[0] - pnt1[0]);
        controlY = pnt1[1] + t * (pnt2[1] - pnt1[1]);
    }
    return [controlX, controlY];
};
// exports.getLeftMostControlPoint = getLeftMostControlPoint;
/**
 * 获取右边控制点
 * @param controlPoints
 * @param t
 * @returns {[*,*]}
 */
export const getRightMostControlPoint =  (controlPoints, t) =>{
    var count = controlPoints.length;
    var pnt1 = controlPoints[count - 3];
    var pnt2 = controlPoints[count - 2];
    var pnt3 = controlPoints[count - 1];
    var pnts = getBisectorNormals(0, pnt1, pnt2, pnt3);
    var normalLeft = pnts[1];
    var normal = getNormal(pnt1, pnt2, pnt3);
    var dist = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1]);
    var _a = [null, null], controlX = _a[0], controlY = _a[1];
    if (dist > ZERO_TOLERANCE) {
        var mid = Mid(pnt2, pnt3);
        var pX = pnt3[0] - mid[0];
        var pY = pnt3[1] - mid[1];
        var d1 = MathDistance(pnt2, pnt3);
        var n = 2.0 / d1;
        var nX = -n * pY;
        var nY = n * pX;
        var a11 = nX * nX - nY * nY;
        var a12 = 2 * nX * nY;
        var a22 = nY * nY - nX * nX;
        var dX = normalLeft[0] - mid[0];
        var dY = normalLeft[1] - mid[1];
        controlX = mid[0] + a11 * dX + a12 * dY;
        controlY = mid[1] + a12 * dX + a22 * dY;
    }
    else {
        controlX = pnt3[0] + t * (pnt2[0] - pnt3[0]);
        controlY = pnt3[1] + t * (pnt2[1] - pnt3[1]);
    }
    return [controlX, controlY];
};
// exports.getRightMostControlPoint = getRightMostControlPoint;
/**
 * 插值曲线点
 * @param t
 * @param controlPoints
 * @returns {null}
 */
export const  getCurvePoints =  (t, controlPoints)=> {
    var _a;
    var leftControl = getLeftMostControlPoint(controlPoints, t);
    // eslint-disable-next-line
    var _b = [null, null, null, [leftControl], []], pnt1 = _b[0], pnt2 = _b[1], pnt3 = _b[2], normals = _b[3], points = _b[4];
    for (var i = 0; i < controlPoints.length - 2; i++) {
        _a = [controlPoints[i], controlPoints[i + 1], controlPoints[i + 2]], pnt1 = _a[0], pnt2 = _a[1], pnt3 = _a[2];
        var normalPoints = getBisectorNormals(t, pnt1, pnt2, pnt3);
        normals = normals.concat(normalPoints);
    }
    var rightControl = getRightMostControlPoint(controlPoints, t);
    if (rightControl) {
        normals.push(rightControl);
    }
    for (var i = 0; i < controlPoints.length - 1; i++) {
        pnt1 = controlPoints[i];
        pnt2 = controlPoints[i + 1];
        points.push(pnt1);
        for (var j = 0; j < FITTING_COUNT; j++) {
            var pnt = getCubicValue(j / FITTING_COUNT, pnt1, normals[i * 2], normals[i * 2 + 1], pnt2);
            points.push(pnt);
        }
        points.push(pnt2);
    }
    return points;
};
// exports.getCurvePoints = getCurvePoints;
/**
 * 贝塞尔曲线
 * @param points
 * @returns {*}
 */
 export const getBezierPoints = (points)=> {
    if (points.length <= 2) {
        return points;
    }
    var bezierPoints = [];
    var n = points.length - 1;
    for (var t = 0; t <= 1; t += 0.01) {
        var _a = [0, 0], x = _a[0], y = _a[1];
        for (var index = 0; index <= n; index++) {
            // eslint-disable-next-line
            var factor = getBinomialFactor(n, index);
            var a = Math.pow(t, index);
            var b = Math.pow((1 - t), (n - index));
            x += factor * a * b * points[index][0];
            y += factor * a * b * points[index][1];
        }
        bezierPoints.push([x, y]);
    }
    bezierPoints.push(points[n]);
    return bezierPoints;
};
// exports.getBezierPoints = getBezierPoints;
/**
 * 获取阶乘数据
 * @param n
 * @returns {number}
 */
export const  getFactorial =  (n)=> {
    var result = 1;
    switch (n) {
        case n <= 1:
            result = 1;
            break;
        case n === 2:
            result = 2;
            break;
        case n === 3:
            result = 6;
            break;
        case n === 24:
            result = 24;
            break;
        case n === 5:
            result = 120;
            break;
        default:
            for (var i = 1; i <= n; i++) {
                result *= i;
            }
            break;
    }
    return result;
};
// exports.getFactorial = getFactorial;
/**
 * 获取二项分布
 * @param n
 * @param index
 * @returns {number}
 */
export const  getBinomialFactor =  (n, index) =>{ return getFactorial(n) / (getFactorial(index) * getFactorial(n - index)); };
// exports.getBinomialFactor = getBinomialFactor;
/**
 * 插值线性点
 * @param points
 * @returns {*}
 */
export const  getQBSplinePoints =  (points)=> {
    if (points.length <= 2) {
        return points;
    }
    var _a = [2, []], n = _a[0], bSplinePoints = _a[1];
    var m = points.length - n - 1;
    bSplinePoints.push(points[0]);
    for (var i = 0; i <= m; i++) {
        for (var t = 0; t <= 1; t += 0.05) {
            var _b = [0, 0], x = _b[0], y = _b[1];
            for (var k = 0; k <= n; k++) {
                // eslint-disable-next-line
                var factor = getQuadricBSplineFactor(k, t);
                x += factor * points[i + k][0];
                y += factor * points[i + k][1];
            }
            bSplinePoints.push([x, y]);
        }
    }
    bSplinePoints.push(points[points.length - 1]);
    return bSplinePoints;
};
// exports.getQBSplinePoints = getQBSplinePoints;
/**
 * 得到二次线性因子
 * @param k
 * @param t
 * @returns {number}
 */
export const  getQuadricBSplineFactor =  (k, t)=> {
    var res = 0;
    if (k === 0) {
        res = Math.pow((t - 1), 2) / 2;
    }
    else if (k === 1) {
        res = (-2 * Math.pow(t, 2) + 2 * t + 1) / 2;
    }
    else if (k === 2) {
        res = Math.pow(t, 2) / 2;
    }
    return res;
};
// exports.getQuadricBSplineFactor = getQuadricBSplineFactor;
/**
 * 获取id
 * @returns {*|string|!Array.<T>}
 */
export const  getuuid =  ()=> {
    var _a = [[], '0123456789abcdef'], s = _a[0], hexDigits = _a[1];
    for (var i = 0; i < 36; i++) {
        s[i] = hexDigits.substr(Math.floor(Math.random() * 0x10), 1);
    }
    s[14] = '4';
    // eslint-disable-next-line no-bitwise
    s[19] = hexDigits.substr((s[19] & 0x3) | 0x8, 1);
    // eslint-disable-next-line no-multi-assign
    s[8] = s[13] = s[18] = s[23] = '-';
    return s.join('');
};
// exports.getuuid = getuuid;
/**
 * 添加标识
 * @param obj
 * @returns {*}
 */
export const  stamp =  (obj)=> {
    var key = '_event_id_';
    obj[key] = obj[key] || getuuid();
    return obj[key];
};
// exports.stamp = stamp;
/**
 * 去除字符串前后空格
 * @param str
 * @returns {*}
 */
export const  trim =  (str)=> { return (str.trim ? str.trim() : str.replace(/^\s+|\s+$/g, '')); };
// exports.trim = trim;
/**
 * 将类名截取成数组
 * @param str
 * @returns {Array|*}
 */
export const  splitWords =  (str) =>{ return trim(str).split(/\s+/); };
// exports.splitWords = splitWords;
/**
 * 判断是否为对象
 * @param value
 * @returns {boolean}
 */
export const  isObject =  (value) =>{
    var type = typeof value;
    return value !== null && (type === 'object' || type === 'function');
};
// exports.isObject = isObject;
/**
 * merge
 * @param a
 * @param b
 * @returns {*}
 */
export const  merge =  (a, b)=> {
    // eslint-disable-next-line no-restricted-syntax
    for (var key in b) {
        if (isObject(b[key]) && isObject(a[key])) {
            merge(a[key], b[key]);
        }
        else {
            a[key] = b[key];
        }
    }
    return a;
};
// exports.merge = merge;
 export const  preventDefault =(e)=> {
    // eslint-disable-next-line no-param-reassign
    e = e || window.event;
    if (e.preventDefault) {
        e.preventDefault();
    }
    else {
        e.returnValue = false;
    }
}
// exports.preventDefault = preventDefault;
 export const bindAll =(fns, context)=> {
    fns.forEach(function (fn) {
        if (!context[fn]) {
            return;
        }
        context[fn] = context[fn].bind(context);
    });
}
export function isArray2D(arr) {
    // 首先检查输入是否为数组
    if (!Array.isArray(arr)) {
        return false;
    }

    // 遍历数组中的每个元素
    for (let i = 0; i < arr.length; i++) {
        // 检查每个元素是否为数组
        if (!Array.isArray(arr[i])) {
            return false;
        }
    }

    // 如果所有元素都是数组，则返回 true
    return true;
}

// exports.bindAll = bindAll;
