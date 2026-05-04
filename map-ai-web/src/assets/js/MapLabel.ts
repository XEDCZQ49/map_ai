
import './jquery.min.js'
import '../Cesium/Cesium.js'
import * as Utils from "../js/utils.js";

function getArrowHeadPoints(points, tailLeft, tailRight) {
    try {
        let len = Utils.getBaseLength(points);
        let headHeight = len * 0.8;
        const headPnt = points[points.length - 1];
        len = Utils.MathDistance(headPnt, points[points.length - 2]);
        const tailWidth = Utils.MathDistance(tailLeft, tailRight);
        if (headHeight > tailWidth * 0.8) {
            headHeight = tailWidth * 0.8;
        }
        const headWidth = headHeight * 0.3;
        const neckWidth = headHeight * 0.15;
        headHeight = headHeight > len ? len : headHeight;
        const neckHeight = headHeight * 0.85;
        const headEndPnt = Utils.getThirdPoint(points[points.length - 2], headPnt, 0, headHeight, true);
        const neckEndPnt = Utils.getThirdPoint(points[points.length - 2], headPnt, 0, neckHeight, true);
        const headLeft = Utils.getThirdPoint(headPnt, headEndPnt, Math.PI / 2, headWidth, false);
        const headRight = Utils.getThirdPoint(headPnt, headEndPnt, Math.PI / 2, headWidth, true);
        const neckLeft = Utils.getThirdPoint(headPnt, neckEndPnt, Math.PI / 2, neckWidth, false);
        const neckRight = Utils.getThirdPoint(headPnt, neckEndPnt, Math.PI / 2, neckWidth, true);
        return [neckLeft, headLeft, headPnt, headRight, neckRight];
    } catch (e) {
        console.log(e);
    }
}

function getArrowBodyPoints(points, neckLeft, neckRight, tailWidthFactor) {
    const allLen = Utils.wholeDistance(points);
    const len = Utils.getBaseLength(points);
    const tailWidth = len * tailWidthFactor;
    const neckWidth = Utils.MathDistance(neckLeft, neckRight);
    const widthDif = (tailWidth - neckWidth) / 2;
    let [tempLen, leftBodyPnts, rightBodyPnts] = [0, [], []];
    for (let i = 1; i < points.length - 1; i++) {
        const angle = Utils.getAngleOfThreePoints(points[i - 1], points[i], points[i + 1]) / 2;
        tempLen += Utils.MathDistance(points[i - 1], points[i]);
        const w = (tailWidth / 2 - (tempLen / allLen) * widthDif) / Math.sin(angle);
        const left = Utils.getThirdPoint(points[i - 1], points[i], Math.PI - angle, w, true);
        const right = Utils.getThirdPoint(points[i - 1], points[i], angle, w, false);
        leftBodyPnts.push(left);
        rightBodyPnts.push(right);
    }
    return leftBodyPnts.concat(rightBodyPnts);
}

export function createFineArrowGraphic(positions) {
    const [p1, p2] = positions;
    const len = Utils.getBaseLength([p1, p2]);
    const tailWidth = len * 0.1;
    const neckWidth = len * 0.2;
    const headWidth = len * 0.25;
    const tailLeft = Utils.getThirdPoint(p2, p1, Math.PI / 2, tailWidth, true);
    const tailRight = Utils.getThirdPoint(p2, p1, Math.PI / 2, tailWidth, false);
    const headLeft = Utils.getThirdPoint(p1, p2, Math.PI / 8.5, headWidth, false);
    const headRight = Utils.getThirdPoint(p1, p2, Math.PI / 8.5, headWidth, true);
    const neckLeft = Utils.getThirdPoint(p1, p2, Math.PI / 13, neckWidth, false);
    const neckRight = Utils.getThirdPoint(p1, p2, Math.PI / 13, neckWidth, true);
    const points = [...tailLeft, ...neckLeft, ...headLeft, ...p2, ...headRight, ...neckRight, ...tailRight, ...p1];
    const cartesianPoints = Cesium.Cartesian3.fromDegreesArray(points);
    return cartesianPoints;
}
// export function createSwallowtailAttackArrowGraphic(lnglatPoints) { //lnglatPoints:[[lon1,lat1],[lon2,lat2],[lon3,lat3],...]
//     // let [tailLeft, tailRight] = [lnglatPoints[0], lnglatPoints[1]];
//     // if (Utils.isClockWise(lnglatPoints[0], lnglatPoints[1], lnglatPoints[2])) {
//     //   tailLeft = lnglatPoints[1];
//     //   tailRight = lnglatPoints[0];
//     // }
//     // const midTail = Utils.Mid(tailLeft, tailRight);
//     // const bonePnts = [midTail].concat(lnglatPoints.slice(2));
//     // const headPnts = getArrowHeadPoints(bonePnts, tailLeft, tailRight);
//     // const [neckLeft, neckRight] = [headPnts[0], headPnts[4]];
//     // const tailWidth = Utils.MathDistance(tailLeft, tailRight);
//     // const allLen = Utils.getBaseLength(bonePnts);
//     // const len = allLen * 0.1 * 1;
//     // const swallowTailPnt = Utils.getThirdPoint(bonePnts[1], bonePnts[0], 0, len, true);
//     // const factor = tailWidth / allLen;
//     // const bodyPnts = getArrowBodyPoints(bonePnts, neckLeft, neckRight, factor);
//     // const count = bodyPnts.length;
//     // let leftPnts = [tailLeft].concat(bodyPnts.slice(0, count / 2));
//     // leftPnts.push(neckLeft);
//     // let rightPnts = [tailRight].concat(bodyPnts.slice(count / 2, count));
//     // rightPnts.push(neckRight);
//     // leftPnts = Utils.getQBSplinePoints(leftPnts);
//     // rightPnts = Utils.getQBSplinePoints(rightPnts);
//     // const points = leftPnts.concat(headPnts, rightPnts.reverse(), [swallowTailPnt, leftPnts[0]]);
//     // const temp = [].concat(...points);
//     // const cartesianPoints = Cesium.Cartesian3.fromDegreesArray(temp);
//     // return cartesianPoints;
//     // const allLen = Utils.getBaseLength(lnglatPoints);
//     // const tailWidth = allLen * 0.1;
//     // let tailLeft = Utils.getThirdPoint(lnglatPoints[1], lnglatPoints[0], Math.PI / 2, tailWidth, true);
//     // let tailRight = Utils.getThirdPoint(lnglatPoints[1], lnglatPoints[0], Math.PI / 2, tailWidth, false);
//     let [tailLeft, tailRight] = [lnglatPoints[0], lnglatPoints[1]];
//     if (Utils.isClockWise(tailLeft, tailRight, lnglatPoints[2])) {
//         const temp = tailLeft
//         tailLeft = tailRight;
//         tailRight = temp;
//     }
//     const midTail = Utils.Mid(tailLeft, tailRight);
//     const bonePnts = [midTail].concat(lnglatPoints.slice(2));
//     // const bonePnts = lnglatPoints;
//     const headPnts = getArrowHeadPoints(bonePnts, tailLeft, tailRight);
//     const [neckLeft, neckRight] = [headPnts[0], headPnts[4]];
//     const tailWidth = Utils.MathDistance(tailLeft, tailRight);
//     const allLen = Utils.getBaseLength(bonePnts);
//     const len = allLen * 0.1 * 1;
//     const swallowTailPnt = Utils.getThirdPoint(bonePnts[1], bonePnts[0], 0, len, true);
//     const factor = tailWidth / allLen;
//     const bodyPnts = getArrowBodyPoints(bonePnts, neckLeft, neckRight, factor);
//     const count = bodyPnts.length;
//     let leftPnts = [tailLeft].concat(bodyPnts.slice(0, count / 2));
//     leftPnts.push(neckLeft);
//     let rightPnts = [tailRight].concat(bodyPnts.slice(count / 2, count));
//     rightPnts.push(neckRight);
//     leftPnts = Utils.getQBSplinePoints(leftPnts);
//     rightPnts = Utils.getQBSplinePoints(rightPnts);
//     const points = leftPnts.concat(headPnts, rightPnts.reverse(), [swallowTailPnt, leftPnts[0]]);
//     const temp = [].concat(...points).slice(0, -4);
//     const cartesianPoints = Cesium.Cartesian3.fromDegreesArray(temp);
//     return cartesianPoints;
// }


/**
 * 根据输入的坐标点创建圆形图形集合
 * @param {Array} lnglatPoints - 经纬度坐标数组 [[lon1, lat1], [lon2, lat2], ...]
 * @param {number} radius - 圆的半径（米）
 * @param {Object} [style] - 可选样式配置
 * @returns {Array} Cesium.Entity数组
 */

export function createSwallowtailAttackArrowGraphic(lnglatPoints) { //lnglatPoints:[[lon1,lat1],[lon2,lat2],[lon3,lat3],...]
    const allLen = Utils.getBaseLength(lnglatPoints);
    const tailWidth = allLen * 0.1;
    let tailLeft = Utils.getThirdPoint(lnglatPoints[1], lnglatPoints[0], Math.PI / 2, tailWidth, true);
    let tailRight = Utils.getThirdPoint(lnglatPoints[1], lnglatPoints[0], Math.PI / 2, tailWidth, false);
    // let [tailLeft, tailRight] = [lnglatPoints[0], lnglatPoints[1]];
    if (Utils.isClockWise(tailLeft, tailRight, lnglatPoints[1])) {
        const temp = tailLeft
        tailLeft = tailRight;
        tailRight = temp;
    }
    // const midTail = Utils.Mid(tailLeft, tailRight);
    // const bonePnts = [midTail].concat(lnglatPoints.slice(1));
    const bonePnts = lnglatPoints;
    const headPnts = getArrowHeadPoints(bonePnts, tailLeft, tailRight);
    const [neckLeft, neckRight] = [headPnts[0], headPnts[4]];
    // const tailWidth = Utils.MathDistance(tailLeft, tailRight);
    // const allLen = Utils.getBaseLength(bonePnts);
    const len = allLen * 0.1 * 1;
    const swallowTailPnt = Utils.getThirdPoint(bonePnts[1], bonePnts[0], 0, len, true);
    const factor = tailWidth / allLen;
    const bodyPnts = getArrowBodyPoints(bonePnts, neckLeft, neckRight, factor);
    const count = bodyPnts.length;
    let leftPnts = [tailLeft].concat(bodyPnts.slice(0, count / 2));
    leftPnts.push(neckLeft);
    let rightPnts = [tailRight].concat(bodyPnts.slice(count / 2, count));
    rightPnts.push(neckRight);
    leftPnts = Utils.getQBSplinePoints(leftPnts);
    rightPnts = Utils.getQBSplinePoints(rightPnts);
    const points = leftPnts.concat(headPnts, rightPnts.reverse());// , [swallowTailPnt, leftPnts[0]]
    const temp = [].concat(...points);
    const cartesianPoints = Cesium.Cartesian3.fromDegreesArray(temp);
    return cartesianPoints;
}
function getTempPoint4(linePnt1, linePnt2, point) {
    const midPnt = Utils.Mid(linePnt1, linePnt2);
    const len = Utils.MathDistance(midPnt, point);
    const angle = Utils.getAngleOfThreePoints(linePnt1, midPnt, point);
    let symPnt = [0, 0];
    let distance1;
    let distance2;
    let mid;
    if (angle < Math.PI / 2) {
        distance1 = len * Math.sin(angle);
        distance2 = len * Math.cos(angle);
        mid = Utils.getThirdPoint(linePnt1, midPnt, Math.PI / 2, distance1, false);
        symPnt = Utils.getThirdPoint(midPnt, mid, Math.PI / 2, distance2, true);
    } else if (angle >= Math.PI / 2 && angle < Math.PI) {
        distance1 = len * Math.sin(Math.PI - angle);
        distance2 = len * Math.cos(Math.PI - angle);
        mid = Utils.getThirdPoint(linePnt1, midPnt, Math.PI / 2, distance1, false);
        symPnt = Utils.getThirdPoint(midPnt, mid, Math.PI / 2, distance2, false);
    } else if (angle >= Math.PI && angle < Math.PI * 1.5) {
        distance1 = len * Math.sin(angle - Math.PI);
        distance2 = len * Math.cos(angle - Math.PI);
        mid = Utils.getThirdPoint(linePnt1, midPnt, Math.PI / 2, distance1, true);
        symPnt = Utils.getThirdPoint(midPnt, mid, Math.PI / 2, distance2, true);
    } else {
        distance1 = len * Math.sin(Math.PI * 2 - angle);
        distance2 = len * Math.cos(Math.PI * 2 - angle);
        mid = Utils.getThirdPoint(linePnt1, midPnt, Math.PI / 2, distance1, true);
        symPnt = Utils.getThirdPoint(midPnt, mid, Math.PI / 2, distance2, false);
    }
    return symPnt;
}

//
function getDoubleArrowHeadPoints(points) {
    const len = Utils.getBaseLength(points);
    const headHeight = len * 0.25;
    const headPnt = points[points.length - 1];
    const headWidth = headHeight * 0.3;
    const neckWidth = headHeight * 0.15;
    const neckHeight = headHeight * 0.85;
    const headEndPnt = Utils.getThirdPoint(points[points.length - 2], headPnt, 0, headHeight, true);
    const neckEndPnt = Utils.getThirdPoint(points[points.length - 2], headPnt, 0, neckHeight, true);
    const headLeft = Utils.getThirdPoint(headPnt, headEndPnt, Math.PI / 2, headWidth, false);
    const headRight = Utils.getThirdPoint(headPnt, headEndPnt, Math.PI / 2, headWidth, true);
    const neckLeft = Utils.getThirdPoint(headPnt, neckEndPnt, Math.PI / 2, neckWidth, false);
    const neckRight = Utils.getThirdPoint(headPnt, neckEndPnt, Math.PI / 2, neckWidth, true);
    return [neckLeft, headLeft, headPnt, headRight, neckRight];
}

function getDoubleArrowBodyPoints(points, neckLeft, neckRight, tailWidthFactor) {
    const allLen = Utils.wholeDistance(points);
    const len = Utils.getBaseLength(points);
    const tailWidth = len * tailWidthFactor;
    const neckWidth = Utils.MathDistance(neckLeft, neckRight);
    const widthDif = (tailWidth - neckWidth) / 2;
    let tempLen = 0;
    let leftBodyPnts = [];
    let rightBodyPnts = [];
    for (let i = 1; i < points.length - 1; i++) {
        const angle = Utils.getAngleOfThreePoints(points[i - 1], points[i], points[i + 1]) / 2;
        tempLen += Utils.MathDistance(points[i - 1], points[i]);
        const w = (tailWidth / 2 - (tempLen / allLen) * widthDif) / Math.sin(angle);
        const left = Utils.getThirdPoint(points[i - 1], points[i], Math.PI - angle, w, true);
        const right = Utils.getThirdPoint(points[i - 1], points[i], angle, w, false);
        leftBodyPnts.push(left);
        rightBodyPnts.push(right);
    }
    return leftBodyPnts.concat(rightBodyPnts);
}

function getArrowPoints(pnt1, pnt2, pnt3, clockWise) {
    const midPnt = Utils.Mid(pnt1, pnt2);
    const len = Utils.MathDistance(midPnt, pnt3);
    let midPnt1 = Utils.getThirdPoint(pnt3, midPnt, 0, len * 0.3, true);
    let midPnt2 = Utils.getThirdPoint(pnt3, midPnt, 0, len * 0.5, true);
    midPnt1 = Utils.getThirdPoint(midPnt, midPnt1, Math.PI / 2, len / 5, clockWise);

    midPnt2 = Utils.getThirdPoint(midPnt, midPnt2, Math.PI / 2, len / 4, clockWise);
    const points = [midPnt, midPnt1, midPnt2, pnt3];
    const arrowPnts = getDoubleArrowHeadPoints(points);
    if (arrowPnts && Array.isArray(arrowPnts) && arrowPnts.length > 0) {
        const neckLeftPoint = arrowPnts[0];
        const neckRightPoint = arrowPnts[4];
        const tailWidthFactor = Utils.MathDistance(pnt1, pnt2) / Utils.getBaseLength(points) / 2;
        const bodyPnts = getDoubleArrowBodyPoints(points, neckLeftPoint, neckRightPoint, tailWidthFactor);
        if (bodyPnts) {
            const n = bodyPnts.length;
            let lPoints = bodyPnts.slice(0, n / 2);
            let rPoints = bodyPnts.slice(n / 2, n);
            lPoints.push(neckLeftPoint);
            rPoints.push(neckRightPoint);
            lPoints = lPoints.reverse();
            lPoints.push(pnt2);
            rPoints = rPoints.reverse();
            rPoints.push(pnt1);
            return lPoints.reverse().concat(arrowPnts, rPoints);
        }
    } else {
        throw new Error('Interpolation Error');
    }
}

export function createDoubleArrowGraphic(lnglatPoints) {
    const [pnt1, pnt2, pnt3] = [lnglatPoints[0], lnglatPoints[1], lnglatPoints[2]];
    const count = lnglatPoints.length;
    let tempPoint4;
    let connPoint;
    if (count === 3) {
        tempPoint4 = getTempPoint4(pnt1, pnt2, pnt3);
        connPoint = Utils.Mid(pnt1, pnt2);
    }else if (count === 4) {
        tempPoint4 = lnglatPoints[3];
        connPoint = Utils.Mid(pnt1, pnt2);
    } else {
        tempPoint4 = lnglatPoints[3];
        connPoint = lnglatPoints[4];
    }
    let leftArrowPnts;
    let rightArrowPnts;
    const isClockWise = Utils.isClockWise(pnt1, pnt2, pnt3);
    if (isClockWise) {
        leftArrowPnts = getArrowPoints(pnt1, connPoint, tempPoint4, false);
        rightArrowPnts = getArrowPoints(connPoint, pnt2, pnt3, true);
    } else {
        leftArrowPnts = getArrowPoints(pnt2, connPoint, pnt3, false);
        rightArrowPnts = getArrowPoints(connPoint, pnt1, tempPoint4, true);
    }
    const m = leftArrowPnts.length;
    const t = (m - 5) / 2;
    const llBodyPnts = leftArrowPnts.slice(0, t);
    const lArrowPnts = leftArrowPnts.slice(t, t + 5);
    let lrBodyPnts = leftArrowPnts.slice(t + 5, m);

    let rlBodyPnts = rightArrowPnts.slice(0, t);
    const rArrowPnts = rightArrowPnts.slice(t, t + 5);
    const rrBodyPnts = rightArrowPnts.slice(t + 5, m);

    rlBodyPnts = Utils.getBezierPoints(rlBodyPnts);
    const bodyPnts = Utils.getBezierPoints(rrBodyPnts.concat(llBodyPnts.slice(1)));
    lrBodyPnts = Utils.getBezierPoints(lrBodyPnts);
    const pnts = rlBodyPnts.concat(rArrowPnts, bodyPnts, lArrowPnts, lrBodyPnts);
    const temp = [].concat(...pnts);
    const cartesianPoints = Cesium.Cartesian3.fromDegreesArray(temp);
    return cartesianPoints;
}


export function drawPolygon(positions, viewer, color = 'red') {
    const polylineEntity = viewer.entities.add({
        polyline: {
            positions: positions, // 传入的坐标点
            width: 3, // 线条宽度
            material: Cesium.Color.fromCssColorString(color), // 线条颜色为红色
            clampToGround: true // 如果希望线条贴地，可以设置为 true
        }
    });
}



export function addTank(dataPoint, viewer, pid) {
    const curentity_position = Cesium.Cartesian3.fromDegrees(dataPoint[0], dataPoint[1]);
    const curentity = viewer.entities.add({
        id: pid,
        name: pid,
        position: Cesium.Cartesian3.fromDegrees(dataPoint[0], dataPoint[1], 0),
        model: {
            uri: `src/assets/model/tank.gltf`,
            minimumPixelSize: 50,
            scale: 1.0, // 初始缩放比例
            show: true,
            // color: Cesium.Color.isVisible, // 设置透明度为0.2，1.0为完全不透明，0.0为完全透明
            color: Cesium.Color.RED, // 设置模型颜色为半透明红色
        },
        description: "大坦克", // 设置实体描述
        label: {
            text: "red", // 显示第四个元素作为标签  会在页面上显示红或蓝其中一个字
            font: 'bold 10pt Arial', // 修改字体样式、大小、字体类型
            style: Cesium.LabelStyle.FILL_AND_OUTLINE,
            outlineWidth: 2,
            verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
            pixelOffset: new Cesium.Cartesian2(0, -9), // 标签位置调整
        },
        orientation: Cesium.Transforms.headingPitchRollQuaternion(
            curentity_position,
            new Cesium.HeadingPitchRoll(
                Cesium.Math.toRadians(0), // Heading
                Cesium.Math.toRadians(0),  // Pitch
                Cesium.Math.toRadians(0)   // Roll
            )
        )
    });
    return curentity
}
