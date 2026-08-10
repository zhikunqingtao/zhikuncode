// ============================================================
// 王者峡谷地图：地面纹理 / 河道 / 基地 / 防御塔 / 水晶 / 泉水
// 野区树木石头 / 草丛 / 龙坑
// 导出 buildMap(scene)：返回 { group, colliders, towers, update }
// ============================================================
import * as THREE from 'three';
import { mergeGeometries } from '../../lib/BufferGeometryUtils.js';
import { MAP, TEAM, TEAM_COLOR } from '../config.js';
import { mulberry32, TAU } from '../utils.js';

const PX = MAP.GROUND_TEX / MAP.SIZE;   // 像素/单位
// 世界坐标 → 地面画布坐标（PlaneGeometry rotateX(-90°) 后 v 与 canvas y 的关系：cy=(z+HALF)*PX）
function pt(x, z) { return [(x + MAP.HALF) * PX, (z + MAP.HALF) * PX]; }

// ------------------------------------------------------------
// 地面 Canvas 程序化纹理
// ------------------------------------------------------------
function makeGroundTexture() {
  const S = MAP.GROUND_TEX;
  const cv = document.createElement('canvas');
  cv.width = cv.height = S;
  const g = cv.getContext('2d');
  const rng = mulberry32(20240801);

  // 1) 草地底色：对角渐变
  const grad = g.createLinearGradient(0, 0, S, S);
  grad.addColorStop(0, '#4d8140');
  grad.addColorStop(0.5, '#578a45');
  grad.addColorStop(1, '#4a7c3c');
  g.fillStyle = grad;
  g.fillRect(0, 0, S, S);

  // 2) 蓝/红方区域色调（左下偏蓝、右上偏红）
  const tint = (bx, bz, color) => {
    const [cx, cy] = pt(bx, bz);
    const rg = g.createRadialGradient(cx, cy, 0, cx, cy, 95 * PX);
    rg.addColorStop(0, color);
    rg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = rg;
    g.fillRect(0, 0, S, S);
  };
  tint(MAP.BLUE_BASE.x, MAP.BLUE_BASE.z, 'rgba(64,110,200,0.16)');
  tint(MAP.RED_BASE.x, MAP.RED_BASE.z, 'rgba(200,80,60,0.16)');

  // 3) 野区深色块（四个象限）
  const blob = (x, z, r, color, a) => {
    const [cx, cy] = pt(x, z);
    const rg = g.createRadialGradient(cx, cy, 0, cx, cy, r * PX);
    rg.addColorStop(0, color.replace('A', String(a)));
    rg.addColorStop(1, color.replace('A', '0'));
    g.fillStyle = rg;
    g.fillRect(cx - r * PX, cy - r * PX, r * 2 * PX, r * 2 * PX);
  };
  blob(-50, 32, 34, 'rgba(28,62,30,A)', 0.5);
  blob(32, -50, 34, 'rgba(28,62,30,A)', 0.5);
  blob(50, -32, 34, 'rgba(30,58,32,A)', 0.5);
  blob(-32, 50, 34, 'rgba(30,58,32,A)', 0.5);

  // 4) 草地噪点 + 中尺度斑块 + 零星小花
  for (let i = 0; i < 260; i++) {   // 大块色斑，打破平涂感
    const x = rng() * S, y = rng() * S, r = 30 + rng() * 90;
    const rg = g.createRadialGradient(x, y, 0, x, y, r);
    const dark = rng() < 0.5;
    rg.addColorStop(0, dark ? 'rgba(26,58,26,0.14)' : 'rgba(150,200,110,0.09)');
    rg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = rg;
    g.fillRect(x - r, y - r, r * 2, r * 2);
  }
  for (let i = 0; i < 12000; i++) {
    const x = rng() * S, y = rng() * S;
    const v = rng();
    g.fillStyle = v < 0.5 ? 'rgba(18,48,18,0.08)' : 'rgba(190,230,150,0.07)';
    g.fillRect(x, y, 1.5 + rng() * 3.5, 1.5 + rng() * 3.5);
  }
  // p5：细颗粒噪点层（近景草地层次）
  for (let i = 0; i < 16000; i++) {
    const x = rng() * S, y = rng() * S;
    g.fillStyle = rng() < 0.5 ? 'rgba(10,34,10,0.07)' : 'rgba(215,245,175,0.055)';
    g.fillRect(x, y, 1 + rng() * 1.6, 1 + rng() * 1.6);
  }
  // p5：中尺度草浪条纹（斜向柔带，模拟风吹草地明暗）
  for (let i = 0; i < 60; i++) {
    const x = rng() * S, y = rng() * S, w = 60 + rng() * 160, h = 8 + rng() * 20;
    g.save();
    g.translate(x, y);
    g.rotate(Math.PI / 4 + (rng() - 0.5) * 0.4);
    const lg = g.createLinearGradient(-w / 2, 0, w / 2, 0);
    const c = rng() < 0.5 ? 'rgba(20,50,20,A)' : 'rgba(170,215,120,A)';
    lg.addColorStop(0, c.replace('A', '0'));
    lg.addColorStop(0.5, c.replace('A', '0.08'));
    lg.addColorStop(1, c.replace('A', '0'));
    g.fillStyle = lg;
    g.fillRect(-w / 2, -h / 2, w, h);
    g.restore();
  }
  for (let i = 0; i < 420; i++) {
    const x = rng() * S, y = rng() * S;
    g.fillStyle = rng() < 0.5 ? 'rgba(240,220,120,0.20)' : 'rgba(235,240,255,0.18)';
    g.beginPath(); g.arc(x, y, 1 + rng() * 1.6, 0, TAU); g.fill();
  }

  // 5) 河道河床（对角线 z=-x，深色，水面之下透出）
  const strokePath = (pts, w, color) => {
    g.strokeStyle = color;
    g.lineWidth = w * PX;
    g.lineCap = 'round';
    g.lineJoin = 'round';
    g.beginPath();
    pts.forEach(([x, z], i) => {
      const [cx, cy] = pt(x, z);
      i === 0 ? g.moveTo(cx, cy) : g.lineTo(cx, cy);
    });
    g.stroke();
  };
  const riverLine = [[-95, 95], [95, -95]];
  strokePath(riverLine, MAP.RIVER_WIDTH + 5, '#22404f');
  strokePath(riverLine, MAP.RIVER_WIDTH + 1, '#2b5468');

  // 6) 三条兵线土路（草地过渡带 + 深色描边 + 沙土内芯 + 中线磨损）
  for (const key of Object.keys(MAP.LANES)) {
    strokePath(MAP.LANES[key], 8.6, 'rgba(86,104,58,0.42)');   // p5：草→土边缘柔化过渡
    strokePath(MAP.LANES[key], 6.0, 'rgba(88,66,40,0.85)');
    strokePath(MAP.LANES[key], 4.6, '#9a7c52');
    strokePath(MAP.LANES[key], 2.6, '#ab8c5e');
    strokePath(MAP.LANES[key], 1.1, 'rgba(140,116,78,0.5)');   // p5：路面中线磨损亮色
  }

  // 7) 龙坑地面图案（石环 + 符文内圈）
  const pit = (p, rim) => {
    const [cx, cy] = pt(p.x, p.z);
    g.fillStyle = '#5c5a58';
    g.beginPath(); g.arc(cx, cy, MAP.PIT_R * PX, 0, TAU); g.fill();
    g.strokeStyle = rim; g.lineWidth = 0.9 * PX;
    g.beginPath(); g.arc(cx, cy, (MAP.PIT_R - 0.8) * PX, 0, TAU); g.stroke();
    g.strokeStyle = 'rgba(220,200,150,0.5)'; g.lineWidth = 0.35 * PX;
    g.beginPath(); g.arc(cx, cy, (MAP.PIT_R - 2.6) * PX, 0, TAU); g.stroke();
    for (let i = 0; i < 8; i++) {  // 放射刻纹
      const a = i / 8 * TAU;
      g.beginPath();
      g.moveTo(cx + Math.cos(a) * 2.2 * PX, cy + Math.sin(a) * 2.2 * PX);
      g.lineTo(cx + Math.cos(a) * (MAP.PIT_R - 3.2) * PX, cy + Math.sin(a) * (MAP.PIT_R - 3.2) * PX);
      g.stroke();
    }
  };
  pit(MAP.TYRANT_PIT, 'rgba(180,120,220,0.65)');
  pit(MAP.OVERLORD_PIT, 'rgba(240,170,80,0.65)');

  // 8) 野怪营地空地
  const camps = [MAP.JUNGLE_BLUE.redBuff, MAP.JUNGLE_BLUE.blueBuff, ...MAP.JUNGLE_BLUE.small];
  for (const c of camps.concat(camps.map(c => ({ x: -c.x, z: -c.z })))) {
    const [cx, cy] = pt(c.x, c.z);
    g.fillStyle = 'rgba(120,95,60,0.55)';
    g.beginPath(); g.arc(cx, cy, MAP.CAMP_R * PX, 0, TAU); g.fill();
  }

  // 9) 基地石台（同心环 + 放射石缝）
  const base = (b, light, dark, accent) => {
    const [cx, cy] = pt(b.x, b.z);
    const rg = g.createRadialGradient(cx, cy, 0, cx, cy, MAP.BASE_PLATFORM_R * PX);
    rg.addColorStop(0, light);
    rg.addColorStop(0.8, dark);
    rg.addColorStop(1, 'rgba(40,46,56,0.9)');
    g.fillStyle = rg;
    g.beginPath(); g.arc(cx, cy, MAP.BASE_PLATFORM_R * PX, 0, TAU); g.fill();
    g.strokeStyle = accent; g.lineWidth = 0.5 * PX;
    for (const r of [MAP.BASE_PLATFORM_R - 1.2, MAP.BASE_PLATFORM_R - 6, 8]) {
      g.beginPath(); g.arc(cx, cy, r * PX, 0, TAU); g.stroke();
    }
    g.strokeStyle = 'rgba(30,34,42,0.5)'; g.lineWidth = 0.28 * PX;
    for (let i = 0; i < 16; i++) {
      const a = i / 16 * TAU;
      g.beginPath();
      g.moveTo(cx + Math.cos(a) * 8 * PX, cy + Math.sin(a) * 8 * PX);
      g.lineTo(cx + Math.cos(a) * (MAP.BASE_PLATFORM_R - 1.2) * PX, cy + Math.sin(a) * (MAP.BASE_PLATFORM_R - 1.2) * PX);
      g.stroke();
    }
  };
  base(MAP.BLUE_BASE, '#8d9ab2', '#66738c', 'rgba(120,170,255,0.5)');
  base(MAP.RED_BASE, '#b29a8d', '#8c6f66', 'rgba(255,150,120,0.5)');

  // 10) 泉水区
  const fountain = (f, color) => {
    const [cx, cy] = pt(f.x, f.z);
    const rg = g.createRadialGradient(cx, cy, 0, cx, cy, MAP.FOUNTAIN_R * PX);
    rg.addColorStop(0, color);
    rg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = rg;
    g.beginPath(); g.arc(cx, cy, MAP.FOUNTAIN_R * PX, 0, TAU); g.fill();
  };
  fountain(MAP.BLUE_FOUNTAIN, 'rgba(140,200,255,0.75)');
  fountain(MAP.RED_FOUNTAIN, 'rgba(255,160,140,0.75)');

  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  return tex;
}

// 河面波纹纹理（小画布循环平铺）
function makeRiverTexture() {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 128;
  const g = cv.getContext('2d');
  g.fillStyle = 'rgba(255,255,255,0)';
  g.clearRect(0, 0, 128, 128);
  const rng = mulberry32(77);
  for (let i = 0; i < 26; i++) {
    g.strokeStyle = `rgba(220,240,255,${0.05 + rng() * 0.10})`;
    g.lineWidth = 1 + rng() * 2;
    const y = rng() * 128;
    g.beginPath();
    for (let x = 0; x <= 128; x += 8) {
      const yy = y + Math.sin(x * 0.12 + i) * 3;
      x === 0 ? g.moveTo(x, yy) : g.lineTo(x, yy);
    }
    g.stroke();
  }
  const tex = new THREE.CanvasTexture(cv);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(14, 1.4);
  return tex;
}

// p5：河面波光纹理（细碎亮点+短亮纹，反向缓流+明灭）
function makeSparkleTexture() {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 128;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, 128, 128);
  const rng = mulberry32(2024);
  for (let i = 0; i < 60; i++) {
    const x = rng() * 128, y = rng() * 128;
    const w = 2 + rng() * 7;
    g.fillStyle = `rgba(235,250,255,${0.12 + rng() * 0.3})`;
    g.beginPath();
    g.ellipse(x, y, w, 0.8 + rng() * 1.2, (rng() - 0.5) * 0.6, 0, TAU);
    g.fill();
  }
  const tex = new THREE.CanvasTexture(cv);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(16, 1.6);
  return tex;
}

// p5：龙坑地面符文纹理（发光圆环+放射刻纹+符文菱块）
function makePitRuneTexture(cssColor) {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 256;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, 256, 256);
  const c = 128;
  g.strokeStyle = cssColor;
  g.fillStyle = cssColor;
  g.lineWidth = 5;
  g.beginPath(); g.arc(c, c, 118, 0, TAU); g.stroke();
  g.lineWidth = 2;
  g.beginPath(); g.arc(c, c, 86, 0, TAU); g.stroke();
  g.beginPath(); g.arc(c, c, 30, 0, TAU); g.stroke();
  for (let i = 0; i < 8; i++) {   // 放射刻纹 + 符文菱块
    const a = i / 8 * TAU;
    g.lineWidth = 3;
    g.beginPath();
    g.moveTo(c + Math.cos(a) * 34, c + Math.sin(a) * 34);
    g.lineTo(c + Math.cos(a) * 82, c + Math.sin(a) * 82);
    g.stroke();
    const rx = c + Math.cos(a + TAU / 16) * 102, ry = c + Math.sin(a + TAU / 16) * 102;
    g.save();
    g.translate(rx, ry);
    g.rotate(a);
    g.beginPath();                 // 菱形符文
    g.moveTo(0, -9); g.lineTo(6, 0); g.lineTo(0, 9); g.lineTo(-6, 0);
    g.closePath(); g.fill();
    g.restore();
  }
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

// p5：基地平台发光纹路纹理（同心环+放射线+刻度）
function makeBaseRuneTexture(cssColor) {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 256;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, 256, 256);
  const c = 128;
  g.strokeStyle = cssColor;
  g.fillStyle = cssColor;
  for (const [r, w] of [[120, 5], [92, 2.5], [48, 2]]) {
    g.lineWidth = w;
    g.beginPath(); g.arc(c, c, r, 0, TAU); g.stroke();
  }
  g.lineWidth = 2.5;
  for (let i = 0; i < 16; i++) {   // 放射刻度
    const a = i / 16 * TAU;
    g.beginPath();
    g.moveTo(c + Math.cos(a) * 50, c + Math.sin(a) * 50);
    g.lineTo(c + Math.cos(a) * (i % 2 ? 76 : 90), c + Math.sin(a) * (i % 2 ? 76 : 90));
    g.stroke();
  }
  for (let i = 0; i < 4; i++) {    // 四方徽记
    const a = i / 4 * TAU + TAU / 8;
    g.save();
    g.translate(c + Math.cos(a) * 106, c + Math.sin(a) * 106);
    g.rotate(a);
    g.fillRect(-7, -7, 14, 14);
    g.restore();
  }
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

// 修复(p4)：围墙出入口对齐"兵线末段与围墙圆的实际交点"方向
// （原算法仅指向来向路径点：红方下路缺口偏离兵线穿越点约 23°，下路兵整波撞墙进不了基地）
// 求线段 (ax,az)→(cx,cz) 与圆心 (bx,bz) 半径 WALL_R 的首次交点的极角
function _laneEntryAngle(bx, bz, ax, az, cx, cz) {
  const dx = cx - ax, dz = cz - az;
  const fx = ax - bx, fz = az - bz;
  const A = dx * dx + dz * dz;
  const B = 2 * (fx * dx + fz * dz);
  const C = fx * fx + fz * fz - MAP.WALL_R * MAP.WALL_R;
  const disc = B * B - 4 * A * C;
  if (A < 1e-6 || disc <= 0) return Math.atan2(az - bz, ax - bx);   // 兜底：指向来向点
  const sq = Math.sqrt(disc);
  let t = (-B - sq) / (2 * A);
  if (t < 0 || t > 1) t = (-B + sq) / (2 * A);
  if (t < 0 || t > 1) t = 0;
  return Math.atan2(az + dz * t - bz, ax + dx * t - bx);
}

// ------------------------------------------------------------
// 防御塔模型（层叠圆柱 + 顶部发光水晶）
// ------------------------------------------------------------
function buildTowerMesh(team) {
  const tc = TEAM_COLOR[team];
  const grp = new THREE.Group();
  const stone = new THREE.MeshLambertMaterial({ color: 0x9aa2ae });
  const trim = new THREE.MeshLambertMaterial({ color: tc.main });
  const add = (geo, mat, y) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.y = y;
    m.castShadow = true; m.receiveShadow = true;
    grp.add(m);
    return m;
  };
  add(new THREE.CylinderGeometry(2.9, 3.3, 1.2, 8), stone, 0.6);   // 基座
  add(new THREE.CylinderGeometry(2.2, 2.6, 0.7, 8), trim, 1.55);   // 队伍色环
  add(new THREE.CylinderGeometry(1.4, 1.9, 3.6, 8), stone, 3.7);   // 塔身
  add(new THREE.CylinderGeometry(2.1, 1.6, 1.0, 8), trim, 5.9);    // 塔顶托盘
  // 顶部发光水晶
  const crystal = new THREE.Mesh(
    new THREE.OctahedronGeometry(0.95),
    new THREE.MeshLambertMaterial({ color: tc.bright, emissive: tc.bright, emissiveIntensity: 0.9 }));
  crystal.position.y = 7.5;
  grp.add(crystal);
  grp.userData.crystal = crystal;
  return grp;
}

// ------------------------------------------------------------
// 主入口：生成整张地图
// ------------------------------------------------------------
export function buildMap(scene) {
  const group = new THREE.Group();
  const colliders = [];   // {x, z, r, type} 供 state.js 圆形推挤
  const towers = [];      // {x, z, team, lane, tier, mesh, radius}
  const crystals = [];    // {team, x, z, core, light} 水晶核心（阶段2 战斗单位）
  const dynamic = [];     // 需要每帧动画的对象 {update(dt,t)}

  // ---------- 地面 ----------
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(MAP.SIZE, MAP.SIZE),
    new THREE.MeshLambertMaterial({ map: makeGroundTexture() }));
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  group.add(ground);

  // 地面外圈暗边（地平线过渡，避免看到贴图边缘）
  const skirt = new THREE.Mesh(
    new THREE.PlaneGeometry(MAP.SIZE * 3, MAP.SIZE * 3),
    new THREE.MeshLambertMaterial({ color: 0x3a6434 }));
  skirt.rotation.x = -Math.PI / 2;
  skirt.position.y = -0.08;
  group.add(skirt);

  // ---------- 河面 ----------
  const riverTex = makeRiverTexture();
  const riverGeo = new THREE.PlaneGeometry(MAP.RIVER_LEN, MAP.RIVER_WIDTH);
  riverGeo.rotateX(-Math.PI / 2);
  riverGeo.rotateY(Math.PI / 4);   // 对齐对角线 z=-x
  const river = new THREE.Mesh(riverGeo, new THREE.MeshPhongMaterial({
    color: 0x3d80c0, transparent: true, opacity: 0.52,
    shininess: 90, specular: 0xbfe0ff, map: riverTex, depthWrite: false,
  }));
  river.position.y = 0.12;
  group.add(river);
  dynamic.push({ update: (dt) => { riverTex.offset.x += dt * 0.045; } });

  // p5：河面波光层（与水流反向缓流 + 明灭闪烁）
  const sparkTex = makeSparkleTexture();
  const sparkle = new THREE.Mesh(riverGeo, new THREE.MeshBasicMaterial({
    map: sparkTex, color: 0xcfeaff, transparent: true, opacity: 0.3,
    blending: THREE.AdditiveBlending, depthWrite: false,
  }));
  sparkle.position.y = 0.18;
  group.add(sparkle);
  dynamic.push({ update: (dt, t) => {
    sparkTex.offset.x -= dt * 0.028;
    sparkle.material.opacity = 0.22 + Math.sin(t * 1.6) * 0.1;
  } });

  // 河岸浅水亮边
  const bankGeo = new THREE.PlaneGeometry(MAP.RIVER_LEN, MAP.RIVER_WIDTH + 3);
  bankGeo.rotateX(-Math.PI / 2);
  bankGeo.rotateY(Math.PI / 4);
  const bank = new THREE.Mesh(bankGeo, new THREE.MeshBasicMaterial({
    color: 0x5aa8d8, transparent: true, opacity: 0.16, depthWrite: false,
  }));
  bank.position.y = 0.07;
  group.add(bank);

  // ---------- 双方基地 ----------
  for (const team of [TEAM.BLUE, TEAM.RED]) {
    const tc = TEAM_COLOR[team];
    const s = team === TEAM.BLUE ? 1 : -1;          // 中心对称系数
    const bx = MAP.BLUE_BASE.x * s, bz = MAP.BLUE_BASE.z * s;
    const fx = MAP.BLUE_FOUNTAIN.x * s, fz = MAP.BLUE_FOUNTAIN.z * s;

    // 石台（略抬高）
    const plat = new THREE.Mesh(
      new THREE.CylinderGeometry(MAP.BASE_PLATFORM_R, MAP.BASE_PLATFORM_R + 0.8, 0.5, 40),
      new THREE.MeshLambertMaterial({ color: team === TEAM.BLUE ? 0x66738a : 0x8a7368 }));
    plat.position.set(bx, 0.22, bz);
    plat.receiveShadow = true;
    group.add(plat);

    // p5：基地平台发光纹路（队伍色同心环+放射刻度，缓慢旋转+呼吸脉冲）
    const runeTex = makeBaseRuneTexture(team === TEAM.BLUE ? '#7db8ff' : '#ff9a80');
    const rune = new THREE.Mesh(
      new THREE.CircleGeometry(MAP.BASE_PLATFORM_R - 1.6, 48),
      new THREE.MeshBasicMaterial({
        map: runeTex, color: tc.bright, transparent: true, opacity: 0.4,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
    rune.rotation.x = -Math.PI / 2;
    rune.position.set(bx, 0.49, bz);
    group.add(rune);
    dynamic.push({ update: (dt, t) => {
      rune.rotation.z += dt * 0.1;
      rune.material.opacity = 0.3 + Math.sin(t * 1.8 + (team === TEAM.BLUE ? 0 : 2)) * 0.14;
    } });

    // 水晶台座 + 水晶核心（大八面体发光晶体）
    const ped = new THREE.Mesh(
      new THREE.CylinderGeometry(3.6, 4.4, 1.4, 8),
      new THREE.MeshLambertMaterial({ color: 0x8a92a2 }));
    ped.position.set(bx, 0.9, bz);
    ped.castShadow = true; ped.receiveShadow = true;
    group.add(ped);
    const core = new THREE.Mesh(
      new THREE.OctahedronGeometry(MAP.CRYSTAL_R),
      new THREE.MeshLambertMaterial({
        color: tc.bright, emissive: tc.bright, emissiveIntensity: 1.15,
        transparent: true, opacity: 0.94,
      }));
    core.position.set(bx, 5.6, bz);
    core.castShadow = true;
    group.add(core);
    dynamic.push({ update: (dt, t) => {
      core.rotation.y += dt * 0.6;
      core.position.y = 5.6 + Math.sin(t * 1.4) * 0.18;
      // p5：呼吸发光（emissive + 点光源同步脉动）
      const breathe = 1.15 + Math.sin(t * 2.0 + (team === TEAM.BLUE ? 0 : 1.5)) * 0.4;
      core.material.emissiveIntensity = breathe;
      coreLight.intensity = 70 + breathe * 28;
    } });
    const coreLight = new THREE.PointLight(tc.bright, 90, 40, 1.8);
    coreLight.position.set(bx, 7, bz);
    group.add(coreLight);
    colliders.push({ x: bx, z: bz, r: 4.2, type: 'crystal' });
    crystals.push({ team, x: bx, z: bz, core, light: coreLight });

    // 泉水发光区
    const spring = new THREE.Mesh(
      new THREE.CircleGeometry(MAP.FOUNTAIN_R, 32),
      new THREE.MeshBasicMaterial({
        color: tc.bright, transparent: true, opacity: 0.35,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
    spring.rotation.x = -Math.PI / 2;
    spring.position.set(fx, 0.55, fz);
    group.add(spring);
    const springRing = new THREE.Mesh(
      new THREE.RingGeometry(MAP.FOUNTAIN_R - 0.5, MAP.FOUNTAIN_R, 40),
      new THREE.MeshBasicMaterial({ color: tc.bright, transparent: true, opacity: 0.8, depthWrite: false }));
    springRing.rotation.x = -Math.PI / 2;
    springRing.position.set(fx, 0.56, fz);
    group.add(springRing);
    dynamic.push({ update: (dt, t) => {
      const k = 1 + Math.sin(t * 2.4) * 0.05;
      springRing.scale.set(k, k, 1);
      spring.material.opacity = 0.28 + Math.sin(t * 2.4) * 0.10;
    } });

    // 基地围墙（三段出入口对准三条兵线）
    const wallMat = new THREE.MeshLambertMaterial({ color: team === TEAM.BLUE ? 0x6d7a92 : 0x92756d });
    // 修复(p4)：出入口角度 = 兵线攻入本基地的最后一段与围墙圆的交点方向
    // 蓝方基地受攻方向为红方兵线（反向行走）：pts[1]→pts[0]；红方基地：pts[n-2]→pts[n-1]
    const gapAngles = ['mid', 'top', 'bot'].map(key => {
      const pts = MAP.LANES[key];
      const n = pts.length;
      const [ax, az] = team === TEAM.BLUE ? pts[1] : pts[n - 2];
      const [cx2, cz2] = team === TEAM.BLUE ? pts[0] : pts[n - 1];
      return _laneEntryAngle(bx, bz, ax, az, cx2, cz2);
    });
    const gapRad = MAP.WALL_GAP_DEG * Math.PI / 180;
    // p5b：围墙段+垛口+旗杆合并为单个静态 mesh（每基地 ~45 次 draw call → 1 次）
    const wallGeos = [];
    const wallM4 = new THREE.Matrix4();
    const pushWall = (geo, x, y, z, ry) => {
      wallM4.makeRotationY(ry).setPosition(x, y, z);
      geo.applyMatrix4(wallM4);
      wallGeos.push(geo);
    };
    for (let i = 0; i < MAP.WALL_SEGMENTS; i++) {
      const a = i / MAP.WALL_SEGMENTS * TAU;
      if (gapAngles.some(ga => {
        let d = Math.abs(((a - ga) % TAU + TAU) % TAU);
        if (d > Math.PI) d = TAU - d;
        return d < gapRad;
      })) continue;
      const wx = bx + Math.cos(a) * MAP.WALL_R;
      const wz = bz + Math.sin(a) * MAP.WALL_R;
      pushWall(new THREE.BoxGeometry(5.0, 2.8, 1.4), wx, 1.4, wz, -a + Math.PI / 2);   // 墙段
      pushWall(new THREE.BoxGeometry(5.2, 0.5, 1.7), wx, 3.0, wz, -a + Math.PI / 2);   // 墙顶小垛口
      colliders.push({ x: wx, z: wz, r: 1.9, type: 'wall' });
    }

    // 出入口旗帜（旗杆并入静态合并，旗面保留摆动动画）
    const flagMat = new THREE.MeshLambertMaterial({ color: tc.main, side: THREE.DoubleSide });
    for (const off of [-1, 1]) {
      const a = gapAngles[0] + off * (gapRad + 0.09);
      const px = bx + Math.cos(a) * MAP.WALL_R, pz = bz + Math.sin(a) * MAP.WALL_R;
      pushWall(new THREE.CylinderGeometry(0.1, 0.14, 4.6, 6), px, 2.3, pz, 0);
      const flag = new THREE.Mesh(new THREE.PlaneGeometry(1.5, 1.0), flagMat);
      flag.position.set(px, 4.0, pz);
      flag.rotation.y = -a;
      group.add(flag);
      dynamic.push({ update: (dt, t) => { flag.rotation.y = -a + Math.sin(t * 3 + off) * 0.18; } });
    }
    if (wallGeos.length) {
      const wallMesh = new THREE.Mesh(mergeGeometries(wallGeos), wallMat);
      wallMesh.castShadow = true; wallMesh.receiveShadow = true;
      group.add(wallMesh);
    }
  }

  // ---------- 防御塔 9+9 ----------
  // 修复(p3)：红方塔位由"中心对称取负"改为沿河道(z=-x)镜像 (x,z)→(-z,-x)
  // （取负会把上路塔映射到右路=物理下路兵线：lane 标签错乱、拐角塔脱离兵线 8.68
  //   > 小兵索敌 8 永久不可达；镜像后红方塔恰好落在对应兵线另一半上）
  for (const lane of Object.keys(MAP.TOWERS_BLUE)) {
    MAP.TOWERS_BLUE[lane].forEach(([x, z], tier) => {
      for (const team of [TEAM.BLUE, TEAM.RED]) {
        const tx = team === TEAM.BLUE ? x : -z;
        const tz = team === TEAM.BLUE ? z : -x;
        const mesh = buildTowerMesh(team);
        mesh.position.set(tx, 0, tz);
        group.add(mesh);
        towers.push({ x: tx, z: tz, team, lane, tier: tier + 1, mesh, radius: MAP.TOWER_R });
        colliders.push({ x: tx, z: tz, r: MAP.TOWER_R + 0.3, type: 'tower' });
        dynamic.push({ update: (dt, t) => {
          mesh.userData.crystal.rotation.y += dt * 1.2;
          // p5：塔顶水晶呼吸发光（各塔相位错开）
          mesh.userData.crystal.material.emissiveIntensity =
            0.75 + Math.sin(t * 2.2 + tx * 0.7 + tz * 0.5) * 0.35;
        } });
      }
    });
  }

  // ---------- 龙坑石环（合并几何减少 draw call）----------
  const pitStones = [];
  const stoneGeo = new THREE.DodecahedronGeometry(0.85, 0);
  const rngPit = mulberry32(555);
  for (const p of [MAP.TYRANT_PIT, MAP.OVERLORD_PIT]) {
    for (let i = 0; i < 12; i++) {
      const a = i / 12 * TAU + rngPit() * 0.2;
      const r = MAP.PIT_R + 0.6;
      const geo = stoneGeo.clone();
      const m = new THREE.Matrix4()
        .makeRotationY(rngPit() * TAU)
        .scale(new THREE.Vector3(0.7 + rngPit() * 0.6, 0.6 + rngPit() * 0.5, 0.7 + rngPit() * 0.6))
        .setPosition(p.x + Math.cos(a) * r, 0.35, p.z + Math.sin(a) * r);
      geo.applyMatrix4(m);
      pitStones.push(geo);
    }
  }
  const pitMesh = new THREE.Mesh(
    mergeGeometries(pitStones),
    new THREE.MeshLambertMaterial({ color: 0x7d7a74 }));
  pitMesh.castShadow = true; pitMesh.receiveShadow = true;
  group.add(pitMesh);

  // p5：龙坑地面符文（暴君紫 / 主宰金，缓慢旋转+呼吸发光）
  const pitRunes = [
    { p: MAP.TYRANT_PIT, css: '#c090ff', col: 0xb070ff },
    { p: MAP.OVERLORD_PIT, css: '#ffc060', col: 0xffa040 },
  ];
  for (const pr of pitRunes) {
    const rm = new THREE.Mesh(
      new THREE.CircleGeometry(MAP.PIT_R - 0.9, 40),
      new THREE.MeshBasicMaterial({
        map: makePitRuneTexture(pr.css), color: pr.col, transparent: true, opacity: 0.34,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
    rm.rotation.x = -Math.PI / 2;
    rm.position.set(pr.p.x, 0.06, pr.p.z);
    group.add(rm);
    dynamic.push({ update: (dt, t) => {
      rm.rotation.z += dt * 0.08;
      rm.material.opacity = 0.26 + Math.sin(t * 1.5 + pr.p.x) * 0.1;
    } });
  }

  // ---------- 野区树木 + 石头（InstancedMesh）----------
  const rng = mulberry32(909090);
  const lanes = Object.values(MAP.LANES);
  const nearLane = (x, z, d) => lanes.some(path => {
    for (let i = 0; i < path.length - 1; i++) {
      const [ax, az] = path[i], [bx, bz] = path[i + 1];
      const abx = bx - ax, abz = bz - az;
      const l2 = abx * abx + abz * abz;
      let t = ((x - ax) * abx + (z - az) * abz) / l2;
      t = Math.max(0, Math.min(1, t));
      const dx = x - (ax + abx * t), dz = z - (az + abz * t);
      if (dx * dx + dz * dz < d * d) return true;
    }
    return false;
  });
  // 野怪营地（双方对称）——树木避让，防止碰撞体卡住野怪/打野
  const jungleSpots = [MAP.JUNGLE_BLUE.redBuff, MAP.JUNGLE_BLUE.blueBuff, ...MAP.JUNGLE_BLUE.small]
    .flatMap(c => [{ x: c.x, z: c.z }, { x: -c.x, z: -c.z }]);
  const treeSpots = [];
  let guard = 0;
  while (treeSpots.length < MAP.TREE_COUNT && guard++ < 4000) {
    const x = (rng() * 2 - 1) * 84;
    const z = (rng() * 2 - 1) * 84;
    if (Math.abs(x + z) / Math.SQRT2 < MAP.RIVER_WIDTH / 2 + 4) continue;  // 河道
    if (nearLane(x, z, 6)) continue;                                       // 兵线
    if (jungleSpots.some(c => Math.hypot(x - c.x, z - c.z) < 7)) continue; // 野怪营地
    if (Math.hypot(x - MAP.BLUE_BASE.x, z - MAP.BLUE_BASE.z) < 27) continue;
    if (Math.hypot(x - MAP.RED_BASE.x, z - MAP.RED_BASE.z) < 27) continue;
    if (Math.hypot(x - MAP.TYRANT_PIT.x, z - MAP.TYRANT_PIT.z) < 11) continue;
    if (Math.hypot(x - MAP.OVERLORD_PIT.x, z - MAP.OVERLORD_PIT.z) < 11) continue;
    if (towers.some(t => Math.hypot(x - t.x, z - t.z) < 6)) continue;
    if (MAP.BRUSHES.some(b => Math.hypot(x - b.x, z - b.z) < 5)) continue;
    if (treeSpots.some(s => Math.hypot(x - s.x, z - s.z) < 3.4)) continue; // 树间距
    // p5：两种树形（0=针叶松 1=阔叶团冠）+ 更大高低差
    treeSpots.push({ x, z, s: 0.7 + rng() * 0.95, rot: rng() * TAU, kind: rng() < 0.55 ? 0 : 1 });
  }

  const trunkGeo = new THREE.CylinderGeometry(0.22, 0.36, 1.5, 5);
  const leafGeo1 = new THREE.ConeGeometry(1.55, 2.7, 6);        // 针叶松：双层锥
  const leafGeo2 = new THREE.ConeGeometry(1.05, 1.8, 6);
  const blobGeo1 = new THREE.IcosahedronGeometry(1.55, 0);      // 阔叶树：双团冠
  const blobGeo2 = new THREE.IcosahedronGeometry(1.0, 0);
  const trunkMat = new THREE.MeshLambertMaterial({ color: 0x6b4a2f });
  const leafMat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  const pines = treeSpots.filter(t => t.kind === 0);
  const broads = treeSpots.filter(t => t.kind === 1);
  const trunks = new THREE.InstancedMesh(trunkGeo, trunkMat, treeSpots.length);
  const leaves1 = new THREE.InstancedMesh(leafGeo1, leafMat, pines.length);
  const leaves2 = new THREE.InstancedMesh(leafGeo2, leafMat.clone(), pines.length);
  const blobs1 = new THREE.InstancedMesh(blobGeo1, leafMat.clone(), broads.length);
  const blobs2 = new THREE.InstancedMesh(blobGeo2, leafMat.clone(), broads.length);
  const dummy = new THREE.Object3D();
  const leafColor = new THREE.Color();
  treeSpots.forEach((t, i) => {
    dummy.position.set(t.x, 0.75 * t.s, t.z);
    dummy.rotation.set(0, t.rot, 0);
    dummy.scale.setScalar(t.s);
    dummy.updateMatrix();
    trunks.setMatrixAt(i, dummy.matrix);
    colliders.push({ x: t.x, z: t.z, r: 0.85 * t.s, type: 'tree' });
  });
  // 针叶松：高瘦双层锥，深绿
  pines.forEach((t, i) => {
    const set = (mesh, y) => {
      dummy.position.set(t.x, y * t.s, t.z);
      dummy.rotation.set(0, t.rot, 0);
      dummy.scale.setScalar(t.s);
      dummy.updateMatrix();
      mesh.setMatrixAt(i, dummy.matrix);
    };
    set(leaves1, 2.6);
    set(leaves2, 4.1);
    leafColor.setHSL(0.30 + rng() * 0.05, 0.5, 0.24 + rng() * 0.09);
    leaves1.setColorAt(i, leafColor);
    leaves2.setColorAt(i, leafColor.clone().offsetHSL(0, 0, 0.05));
  });
  // 阔叶树：矮壮双团冠，浅黄绿
  broads.forEach((t, i) => {
    const set = (mesh, y, squash, ox, oz) => {
      dummy.position.set(t.x + ox * t.s, y * t.s, t.z + oz * t.s);
      dummy.rotation.set(0, t.rot, 0);
      dummy.scale.set(t.s * 1.15, t.s * squash, t.s * 1.15);
      dummy.updateMatrix();
      mesh.setMatrixAt(i, dummy.matrix);
    };
    set(blobs1, 2.8, 0.82, 0, 0);
    set(blobs2, 3.7, 0.9, 0.55, 0.3);
    leafColor.setHSL(0.24 + rng() * 0.06, 0.48, 0.3 + rng() * 0.1);
    blobs1.setColorAt(i, leafColor);
    blobs2.setColorAt(i, leafColor.clone().offsetHSL(0.01, 0, 0.06));
  });
  trunks.castShadow = true;
  leaves1.castShadow = true;
  leaves2.castShadow = true;
  blobs1.castShadow = true;
  blobs2.castShadow = true;
  group.add(trunks, leaves1, leaves2, blobs1, blobs2);

  // 石头
  const rockGeo = new THREE.DodecahedronGeometry(0.9, 0);
  const rockMat = new THREE.MeshLambertMaterial({ color: 0x8b8d90 });
  const rocks = new THREE.InstancedMesh(rockGeo, rockMat, MAP.ROCK_COUNT);
  let placed = 0; guard = 0;
  while (placed < MAP.ROCK_COUNT && guard++ < 2000) {
    const x = (rng() * 2 - 1) * 84;
    const z = (rng() * 2 - 1) * 84;
    if (Math.abs(x + z) / Math.SQRT2 < MAP.RIVER_WIDTH / 2 + 2.5) continue;
    if (nearLane(x, z, 5)) continue;
    if (Math.hypot(x - MAP.BLUE_BASE.x, z - MAP.BLUE_BASE.z) < 25) continue;
    if (Math.hypot(x - MAP.RED_BASE.x, z - MAP.RED_BASE.z) < 25) continue;
    if (treeSpots.some(t => Math.hypot(x - t.x, z - t.z) < 2.6)) continue;
    const s = 0.5 + rng() * 0.9;
    dummy.position.set(x, 0.3 * s, z);
    dummy.rotation.set(rng() * TAU, rng() * TAU, 0);
    dummy.scale.setScalar(s);
    dummy.updateMatrix();
    rocks.setMatrixAt(placed, dummy.matrix);
    placed++;
  }
  rocks.count = placed;
  rocks.castShadow = true; rocks.receiveShadow = true;
  group.add(rocks);

  // ---------- 草丛 14 片（基座 + 交叉面片草叶，半透明）----------
  const brushBladeTex = (() => {
    const cv = document.createElement('canvas');
    cv.width = 64; cv.height = 64;
    const g2 = cv.getContext('2d');
    g2.clearRect(0, 0, 64, 64);
    const rb = mulberry32(31);
    for (let i = 0; i < 22; i++) {   // 手绘草叶簇
      const bx = 6 + rb() * 52;
      const h = 26 + rb() * 34;
      const lean = (rb() - 0.5) * 14;
      const grad = g2.createLinearGradient(0, 64, 0, 64 - h);
      grad.addColorStop(0, '#2c6428');
      grad.addColorStop(1, '#7fbf5a');
      g2.strokeStyle = grad;
      g2.lineWidth = 2.4 + rb() * 2;
      g2.beginPath();
      g2.moveTo(bx, 64);
      g2.quadraticCurveTo(bx + lean * 0.4, 64 - h * 0.6, bx + lean, 64 - h);
      g2.stroke();
    }
    const t = new THREE.CanvasTexture(cv);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  })();
  // p5：草丛改立体草叶簇——低矮草墩 + 底部 pivot 交叉草叶（11 片/簇，高矮随机，微风摆动）
  const brushBaseMat = new THREE.MeshLambertMaterial({
    color: 0x2f6b2a, transparent: true, opacity: 0.6, emissive: 0x142c10,
  });
  const brushBaseGeo = new THREE.CylinderGeometry(1, 1.18, 0.55, 8);
  const bladeMat = new THREE.MeshLambertMaterial({
    map: brushBladeTex, transparent: true, alphaTest: 0.15,
    side: THREE.DoubleSide, emissive: 0x1d3818,
  });
  const bladeGeo = new THREE.PlaneGeometry(1.35, 1);
  bladeGeo.translate(0, 0.5, 0);   // pivot 移到叶片底部：摆动时草根不动、草尖摇晃
  const rngBrush = mulberry32(41414);
  const bladeData = [];   // {x,z,ry,sx,sy,ph} 世界坐标（烘焙草丛朝向）
  for (const b of MAP.BRUSHES) {
    const bg = new THREE.Group();
    bg.position.set(b.x, 0, b.z);
    bg.rotation.y = b.rot;
    const baseM = new THREE.Mesh(brushBaseGeo, brushBaseMat);
    baseM.scale.set(MAP.BRUSH_W * 0.6, 1, MAP.BRUSH_L * 0.6);
    baseM.position.y = 0.24;
    bg.add(baseM);
    group.add(bg);
    // 交叉草叶簇参数（底部 pivot，高矮错落）——p5b：合并为单个 InstancedMesh
    const cosR = Math.cos(b.rot), sinR = Math.sin(b.rot);
    for (let i = 0; i < 11; i++) {
      const lx = (rngBrush() - 0.5) * (MAP.BRUSH_W - 0.5);
      const lz = (rngBrush() - 0.5) * (MAP.BRUSH_L - 0.5);
      bladeData.push({
        x: b.x + lx * cosR + lz * sinR,
        z: b.z - lx * sinR + lz * cosR,
        ry: b.rot + rngBrush() * Math.PI,
        sx: 0.75 + rngBrush() * 0.55,
        sy: (0.85 + rngBrush() * 0.85) * MAP.BRUSH_H,
        ph: rngBrush() * TAU + b.x * 0.3,
      });
    }
  }
  // p5b：154 片草叶 = 1 次 draw call（原 154 个独立 Mesh），微风摆动走实例矩阵
  const blades = new THREE.InstancedMesh(bladeGeo, bladeMat, bladeData.length);
  blades.frustumCulled = false;
  group.add(blades);
  const bladeDummy = new THREE.Object3D();
  dynamic.push({ update: (dt, t) => {
    for (let i = 0; i < bladeData.length; i++) {
      const d = bladeData[i];
      bladeDummy.position.set(d.x, 0.28, d.z);
      bladeDummy.rotation.set(0, d.ry, Math.sin(t * 1.9 + d.ph) * 0.085);   // 微风摆动
      bladeDummy.scale.set(d.sx, d.sy, 1);
      bladeDummy.updateMatrix();
      blades.setMatrixAt(i, bladeDummy.matrix);
    }
    blades.instanceMatrix.needsUpdate = true;
  } });

  scene.add(group);

  return {
    group,
    colliders,
    towers,
    crystals,
    lanes: MAP.LANES,
    /** 每帧动画（水波/水晶/泉水/旗帜） */
    update(dt, t) { for (const d of dynamic) d.update(dt, t); },
  };
}
