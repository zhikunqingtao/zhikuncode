// ============================================================
// 特效系统（p5 视觉打磨升级）
//   粒子爆发（共享 Points 对象池，白热芯+冲击波环）
//   弹道：发光核心+光晕+带状拖尾（ribbon）+沿途火花
//   防御塔激光 / 地面范围圈 / AOE 红色预警圈（脉冲）/ 方向箭头
//   受击闪红 / 死亡倒地淡出 / 防御塔倒塌
//   升级金色光柱 / 回城蓝色引导光柱 / 暴君主宰击杀全队金光
//   状态轮询（bindState）：眩晕击飞头顶旋转星星 / 红蓝BUFF环绕光环 / 泉水回复绿光
// 飘字走 DOM（hud.js 对象池），由 state.events 'floatText' 驱动
// ============================================================
import * as THREE from 'three';

const MAX_PARTICLES = 1024;
const TRAIL_N = 10;          // 弹道拖尾历史点数
const TRACER_POOL = 48;
const SHOCK_POOL = 12;       // 冲击波环池
const PILLAR_POOL = 10;      // 光柱池
const SHELL_POOL = 12;       // 单位发光壳池（受击闪红/技能增益）

// 柔和圆点纹理（粒子/光晕用）
function makeDotTexture() {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 64;
  const g = cv.getContext('2d');
  const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  grad.addColorStop(0, 'rgba(255,255,255,1)');
  grad.addColorStop(0.4, 'rgba(255,255,255,0.6)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 64, 64);
  return new THREE.CanvasTexture(cv);
}

// 四角星纹理（眩晕星星）
function makeStarTexture() {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 64;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, 64, 64);
  const grad = g.createRadialGradient(32, 32, 0, 32, 32, 30);
  grad.addColorStop(0, 'rgba(255,255,255,1)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.beginPath();
  // 四角星
  g.moveTo(32, 2); g.quadraticCurveTo(36, 28, 62, 32);
  g.quadraticCurveTo(36, 36, 32, 62); g.quadraticCurveTo(28, 36, 2, 32);
  g.quadraticCurveTo(28, 28, 32, 2);
  g.fill();
  return new THREE.CanvasTexture(cv);
}

// 箭头纹理
function makeArrowTexture() {
  const cv = document.createElement('canvas');
  cv.width = 64; cv.height = 128;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, 64, 128);
  g.fillStyle = 'rgba(255,255,255,0.9)';
  g.beginPath();
  g.moveTo(32, 4); g.lineTo(58, 44); g.lineTo(40, 44);
  g.lineTo(40, 124); g.lineTo(24, 124); g.lineTo(24, 44); g.lineTo(6, 44);
  g.closePath(); g.fill();
  return new THREE.CanvasTexture(cv);
}

export class VFX {
  /** @param {THREE.Scene} scene */
  constructor(scene) {
    this.scene = scene;
    this._state = null;        // bindState 后用于状态轮询（只读）
    this._time = 0;
    this._dotTex = makeDotTexture();
    this._starTex = makeStarTexture();

    // ---------- 粒子池（单个 Points） ----------
    const geo = new THREE.BufferGeometry();
    this._pPos = new Float32Array(MAX_PARTICLES * 3);
    this._pCol = new Float32Array(MAX_PARTICLES * 3);
    geo.setAttribute('position', new THREE.BufferAttribute(this._pPos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(this._pCol, 3));
    this._pVel = new Float32Array(MAX_PARTICLES * 3);
    this._pLife = new Float32Array(MAX_PARTICLES);      // 剩余
    this._pMaxLife = new Float32Array(MAX_PARTICLES);
    this._pBaseCol = new Float32Array(MAX_PARTICLES * 3);
    this._pGrav = new Float32Array(MAX_PARTICLES);      // 重力系数（可上浮）
    this._pFree = [];
    for (let i = MAX_PARTICLES - 1; i >= 0; i--) {
      this._pFree.push(i);
      this._pPos[i * 3 + 1] = -100;
    }
    const mat = new THREE.PointsMaterial({
      size: 0.55, map: this._dotTex, transparent: true,
      vertexColors: true, blending: THREE.AdditiveBlending,
      depthWrite: false, sizeAttenuation: true,
    });
    this._points = new THREE.Points(geo, mat);
    this._points.frustumCulled = false;
    scene.add(this._points);

    // ---------- 弹道池（核心+光晕+带状拖尾） ----------
    this._tracers = [];
    this._tracerFree = [];
    const sphereGeo = new THREE.SphereGeometry(1, 10, 8);
    this._sphereGeo = sphereGeo;
    // 拖尾 ribbon 索引（三角形条带，全部弹道共享）
    const idx = [];
    for (let i = 0; i < TRAIL_N - 1; i++) {
      const a = i * 2, b = i * 2 + 1, c = i * 2 + 2, d = i * 2 + 3;
      idx.push(a, b, c, b, d, c);
    }
    for (let i = 0; i < TRACER_POOL; i++) {
      const group = new THREE.Group();
      const core = new THREE.Mesh(sphereGeo, new THREE.MeshBasicMaterial({
        color: 0xffffff, transparent: true, opacity: 0.95,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
      group.add(core);
      // 光晕（面向相机的精灵）
      const halo = new THREE.Sprite(new THREE.SpriteMaterial({
        map: this._dotTex, color: 0xffffff, transparent: true, opacity: 0.55,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
      group.add(halo);
      // 带状拖尾（世界坐标写入，mesh 固定原点）
      const tGeo = new THREE.BufferGeometry();
      const tPos = new Float32Array(TRAIL_N * 2 * 3);
      const tCol = new Float32Array(TRAIL_N * 2 * 3);
      tGeo.setAttribute('position', new THREE.BufferAttribute(tPos, 3));
      tGeo.setAttribute('color', new THREE.BufferAttribute(tCol, 3));
      tGeo.setIndex(idx);
      const trail = new THREE.Mesh(tGeo, new THREE.MeshBasicMaterial({
        vertexColors: true, transparent: true, opacity: 0.9,
        blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
      }));
      trail.frustumCulled = false;
      this.scene.add(trail);
      group.visible = false;
      trail.visible = false;
      scene.add(group);
      const tr = {
        group, core, halo, trail, tPos, tCol,
        hist: [], color: new THREE.Color(), size: 0.4, released: true, sparkT: 0,
      };
      this._tracers.push(tr);
      this._tracerFree.push(tr);
    }

    // ---------- 冲击波环池（命中爆裂/技能落点） ----------
    this._shocks = [];
    this._shockFree = [];
    const shockGeo = new THREE.RingGeometry(0.82, 1.0, 36);
    for (let i = 0; i < SHOCK_POOL; i++) {
      const m = new THREE.Mesh(shockGeo, new THREE.MeshBasicMaterial({
        color: 0xffffff, transparent: true, opacity: 0,
        blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
      }));
      m.rotation.x = -Math.PI / 2;
      m.visible = false;
      scene.add(m);
      const s = { mesh: m, t: 0, max: 0.35, r: 2 };
      this._shocks.push(s);
      this._shockFree.push(s);
    }

    // ---------- 光柱池（升级/回城完成/屠龙金光） ----------
    this._pillars = [];
    this._pillarFree = [];
    const pillarGeo = new THREE.CylinderGeometry(1, 1, 1, 12, 1, true);
    for (let i = 0; i < PILLAR_POOL; i++) {
      const m = new THREE.Mesh(pillarGeo, new THREE.MeshBasicMaterial({
        color: 0xffe060, transparent: true, opacity: 0,
        blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
      }));
      m.visible = false;
      scene.add(m);
      const p = { mesh: m, t: 0, max: 0.8, r: 1, h: 6 };
      this._pillars.push(p);
      this._pillarFree.push(p);
    }

    // ---------- 激光/指示圈/箭头/消散 活动列表 ----------
    this._beams = [];
    this._beamGeo = new THREE.CylinderGeometry(1, 1, 1, 6, 1, true);
    this._circles = [];
    this._arrows = [];
    this._arrowTex = makeArrowTexture();
    this._dissolves = [];   // {model, t, dur, entries}
    this._tmpV = new THREE.Vector3();

    // ---------- 单位发光壳池（受击闪红/技能增益光效的附加层） ----------
    // p5b 修复：旧实现 flashHit 直接改单位材质 emissive——战斗中每次受击刷新计时，
    // 全身永久饱和橙红、模型细节全失（亚瑟橙红团）。改为附加发光壳 mesh，
    // 不碰任何单位材质，0.15s 到期自动隐藏，绝无残留/污染。
    this._shells = [];
    this._shellFree = [];
    const shellGeo = new THREE.SphereGeometry(1, 12, 9);
    for (let i = 0; i < SHELL_POOL; i++) {
      const m = new THREE.Mesh(shellGeo, new THREE.MeshBasicMaterial({
        color: 0xff3020, transparent: true, opacity: 0,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
      m.visible = false;
      scene.add(m);
      const s = { mesh: m, t: 0, max: 0.15, model: null, r: 1, o: 0.5 };
      this._shells.push(s);
      this._shellFree.push(s);
    }

    // ---------- 状态特效（bindState 后轮询驱动） ----------
    this._stunStars = new Map();   // unitId → {grp, sprites, unit, ang}
    this._auras = new Map();       // unitId → {red, blue, unit}
    this._recalls = new Map();     // unitId → {grp, unit}
    this._sparkT = 0;              // 泉水绿光节流

    // ---------- 持久瞄准指示器（技能键按住拖动时显示，松开隐藏） ----------
    this._aim = this._buildAimIndicator();
  }

  /** 绑定 GameState（只读轮询：眩晕星星/红蓝BUFF光环/回城光柱/泉水绿光） */
  bindState(state) { this._state = state; }

  _buildAimIndicator() {
    const mk = (inner, outer, opacity) => new THREE.Mesh(
      new THREE.RingGeometry(inner, outer, 40),
      new THREE.MeshBasicMaterial({ color: 0x7db8ff, transparent: true, opacity, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    const grp = new THREE.Group();
    // 范围环（单位半径，scale 到 r）
    const ring = mk(0.93, 1.0, 0.9);
    ring.rotation.x = -Math.PI / 2; ring.position.y = 0.1;
    grp.add(ring);
    const fill = new THREE.Mesh(
      new THREE.CircleGeometry(1, 40),
      new THREE.MeshBasicMaterial({ color: 0x7db8ff, transparent: true, opacity: 0.12, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    fill.rotation.x = -Math.PI / 2; fill.position.y = 0.09;
    grp.add(fill);
    // 方向箭头（单位长度，scale 到 len）
    const arrow = new THREE.Mesh(
      new THREE.PlaneGeometry(1.8, 1),
      new THREE.MeshBasicMaterial({ map: this._arrowTex, color: 0x9fd0ff, transparent: true, opacity: 0.85, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    arrow.rotation.x = -Math.PI / 2; arrow.position.y = 0.11;
    grp.add(arrow);
    // 落点圈（范围技能的目标点）
    const drop = mk(0.8, 1.0, 0.95);
    drop.rotation.x = -Math.PI / 2; drop.position.y = 0.12;
    grp.add(drop);
    grp.visible = false;
    this.scene.add(grp);
    return { grp, ring, fill, arrow, drop };
  }

  /**
   * 显示/更新瞄准指示器
   * @param o { x, z,              施法者位置
   *            r,                 自身范围圈半径（around/target），0=不显示
   *            dir:{x,z}, len,    方向箭头（line/dash/area），null=不显示
   *            dropX, dropZ, dropR }  落点圈（area），dropR=0 不显示
   */
  aimShow(o) {
    const a = this._aim;
    a.grp.visible = true;
    a.grp.position.set(o.x, 0, o.z);
    // 自身范围圈
    if (o.r > 0) {
      a.ring.visible = a.fill.visible = true;
      a.ring.scale.setScalar(o.r);
      a.fill.scale.setScalar(o.r);
    } else {
      a.ring.visible = a.fill.visible = false;
    }
    // 方向箭头
    if (o.dir && o.len > 0) {
      a.arrow.visible = true;
      a.arrow.rotation.z = -Math.atan2(o.dir.x, o.dir.z);
      a.arrow.scale.set(1, o.len, 1);
      a.arrow.position.set(o.dir.x * o.len / 2, 0.11, o.dir.z * o.len / 2);
    } else {
      a.arrow.visible = false;
    }
    // 落点圈（世界坐标 → 相对 grp）
    if (o.dropR > 0) {
      a.drop.visible = true;
      a.drop.scale.setScalar(o.dropR);
      a.drop.position.set(o.dropX - o.x, 0.12, o.dropZ - o.z);
    } else {
      a.drop.visible = false;
    }
  }

  /** 隐藏瞄准指示器 */
  aimHide() {
    if (this._aim) this._aim.grp.visible = false;
  }

  /** 单粒子发射（内部） */
  _emit(x, y, z, vx, vy, vz, life, col, grav = 9) {
    if (!this._pFree.length) return;
    const i = this._pFree.pop();
    this._pPos[i * 3] = x; this._pPos[i * 3 + 1] = y; this._pPos[i * 3 + 2] = z;
    this._pVel[i * 3] = vx; this._pVel[i * 3 + 1] = vy; this._pVel[i * 3 + 2] = vz;
    this._pLife[i] = this._pMaxLife[i] = life;
    this._pBaseCol[i * 3] = col.r; this._pBaseCol[i * 3 + 1] = col.g; this._pBaseCol[i * 3 + 2] = col.b;
    this._pGrav[i] = grav;
  }

  /** 粒子爆发（白热芯+彩色外壳+可选冲击波环） @param o {color, count, speed, life, up, shock} */
  burst(x, y, z, o = {}) {
    const count = o.count || 12;
    const speed = o.speed || 5;
    const life = o.life || 0.5;
    const up = o.up !== undefined ? o.up : speed * 0.55;
    const col = new THREE.Color(o.color !== undefined ? o.color : 0xffffff);
    const white = new THREE.Color(0xffffff);
    for (let n = 0; n < count; n++) {
      const a = Math.random() * Math.PI * 2;
      const r = Math.random();
      // 前 1/4 为白热芯（更快更亮更短命）
      const core = n < count * 0.25;
      this._emit(
        x, y, z,
        Math.cos(a) * speed * r * (core ? 1.5 : 1),
        up * (0.4 + Math.random() * 0.8) * (core ? 1.3 : 1),
        Math.sin(a) * speed * r * (core ? 1.5 : 1),
        life * (0.6 + Math.random() * 0.7) * (core ? 0.55 : 1),
        core ? white : col);
    }
    // 大爆发附带地面冲击波环
    if (o.shock !== false && count >= 14) this.shockRing(x, z, Math.max(1.6, speed * 0.32), o.color);
  }

  /** 地面冲击波环 */
  shockRing(x, z, r = 2, color = 0xffffff, y = 0.12) {
    const s = this._shockFree.pop();
    if (!s) return;
    s.t = s.max = 0.38;
    s.r = r;
    s.mesh.material.color.set(color !== undefined ? color : 0xffffff);
    s.mesh.position.set(x, y, z);
    s.mesh.visible = true;
  }

  /** 光柱（升级金柱等） */
  pillar(x, z, color = 0xffe060, r = 0.9, h = 6, dur = 0.8) {
    const p = this._pillarFree.pop();
    if (!p) return;
    p.t = p.max = dur;
    p.r = r; p.h = h;
    p.mesh.material.color.set(color);
    p.mesh.position.set(x, h / 2, z);
    p.mesh.visible = true;
  }

  /** 弹道发光球+光晕+带状拖尾 @returns {setPos(x,y,z), release()} */
  tracer(color, size = 0.4) {
    const tr = this._tracerFree.pop();
    if (!tr) return { setPos: () => {}, release: () => {} };
    tr.released = false;
    tr.color.set(color);
    tr.size = size;
    tr.core.material.color.set(color);
    tr.core.scale.setScalar(size);
    tr.halo.material.color.set(color);
    tr.halo.scale.setScalar(size * 4.2);
    tr.hist.length = 0;
    tr.group.visible = true;
    tr.trail.visible = true;
    return {
      setPos: (x, y, z) => {
        tr.group.position.set(x, y, z);
        tr.hist.unshift({ x, y, z });
        if (tr.hist.length > TRAIL_N) tr.hist.pop();
        // 带状拖尾：历史点沿行进方向的水平法线展开成双顶点条带
        const w0 = tr.size * 0.85;
        for (let i = 0; i < TRAIL_N; i++) {
          const h = tr.hist[Math.min(i, tr.hist.length - 1)] || { x, y, z };
          const nxt = tr.hist[Math.min(i + 1, tr.hist.length - 1)] || h;
          let px = -(nxt.z - h.z), pz = (nxt.x - h.x);
          const pl = Math.hypot(px, pz);
          if (pl > 1e-5) { px /= pl; pz /= pl; } else { px = 1; pz = 0; }
          const w = w0 * (1 - i / TRAIL_N);
          const o = i * 6;
          tr.tPos[o] = h.x + px * w; tr.tPos[o + 1] = h.y; tr.tPos[o + 2] = h.z + pz * w;
          tr.tPos[o + 3] = h.x - px * w; tr.tPos[o + 4] = h.y; tr.tPos[o + 5] = h.z - pz * w;
          const f = 1 - i / TRAIL_N;
          tr.tCol[o] = tr.color.r * f; tr.tCol[o + 1] = tr.color.g * f; tr.tCol[o + 2] = tr.color.b * f;
          tr.tCol[o + 3] = tr.color.r * f; tr.tCol[o + 4] = tr.color.g * f; tr.tCol[o + 5] = tr.color.b * f;
        }
        tr.trail.geometry.attributes.position.needsUpdate = true;
        tr.trail.geometry.attributes.color.needsUpdate = true;
        // 沿途火花（节流）
        tr.sparkT -= 1;
        if (tr.sparkT <= 0) {
          tr.sparkT = 3;   // 每 3 次 setPos 一颗
          this._emit(x, y, z,
            (Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2,
            0.28, tr.color, 0);
        }
      },
      release: () => {
        if (tr.released) return;
        tr.released = true;
        tr.group.visible = false;
        tr.trail.visible = false;
        this._tracerFree.push(tr);
      },
    };
  }

  /** 激光束（防御塔） */
  beam(from, to, color, width = 0.2) {
    const mat = new THREE.MeshBasicMaterial({
      color, transparent: true, opacity: 0.85,
      blending: THREE.AdditiveBlending, depthWrite: false,
    });
    const m = new THREE.Mesh(this._beamGeo, mat);
    const a = new THREE.Vector3(from.x, from.y, from.z);
    const b = new THREE.Vector3(to.x, to.y, to.z);
    const len = a.distanceTo(b);
    m.position.copy(a).lerp(b, 0.5);
    m.scale.set(width, len, width);
    m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), b.clone().sub(a).normalize());
    this.scene.add(m);
    this._beams.push({ mesh: m, t: 0.18, max: 0.18 });
  }

  /** 地面范围圈指示器 */
  groundCircle(x, z, r, color, duration = 0.6) {
    const grp = new THREE.Group();
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(r - 0.22, r, 36),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.85, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.08;
    grp.add(ring);
    const fill = new THREE.Mesh(
      new THREE.CircleGeometry(r, 36),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.14, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    fill.rotation.x = -Math.PI / 2;
    fill.position.y = 0.07;
    grp.add(fill);
    grp.position.set(x, 0, z);
    this.scene.add(grp);
    this._circles.push({ mesh: grp, t: duration, max: duration, mats: [ring.material, fill.material], warn: false });
  }

  /** AOE 预警圈（敌方红色半透明，脉冲闪烁） */
  warnCircle(x, z, r, duration = 0.8, color = 0xff4030) {
    const grp = new THREE.Group();
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(r - 0.3, r, 40),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.9, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.09;
    grp.add(ring);
    const fill = new THREE.Mesh(
      new THREE.CircleGeometry(r, 40),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.2, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    fill.rotation.x = -Math.PI / 2;
    fill.position.y = 0.08;
    grp.add(fill);
    grp.position.set(x, 0, z);
    this.scene.add(grp);
    this._circles.push({ mesh: grp, t: duration, max: duration, mats: [ring.material, fill.material], warn: true });
  }

  /** 方向箭头指示器 */
  directionArrow(x, z, dx, dz, len, color, duration = 0.5) {
    const m = new THREE.Mesh(
      new THREE.PlaneGeometry(1.6, len),
      new THREE.MeshBasicMaterial({ map: this._arrowTex, color, transparent: true, opacity: 0.8, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    m.rotation.x = -Math.PI / 2;
    m.rotation.z = -Math.atan2(dx, dz);
    m.position.set(x + dx * len / 2, 0.09, z + dz * len / 2);
    this.scene.add(m);
    this._arrows.push({ mesh: m, t: duration, max: duration });
  }

  /**
   * 单位附加发光壳（p5b）：受击闪红/技能增益光效的叠加层。
   * 不修改单位任何材质（杜绝共享材质污染/恢复遗漏），到期自动隐藏。
   * @param model 单位模型 @param color 颜色 @param dur 时长(s)
   * @param radius 壳半径（世界单位） @param opacity 峰值透明度
   */
  glowShell(model, color = 0xff3020, dur = 0.15, radius = 1, opacity = 0.5) {
    if (!model) return;
    // 同一模型已有壳：只刷新计时，不叠加（连续受击不会越叠越亮）
    const exist = this._shells.find(s => s.model === model && s.t > 0);
    if (exist) { exist.t = exist.max; return; }
    const s = this._shellFree.pop();
    if (!s) return;
    s.model = model;
    s.t = s.max = dur;
    s.r = Math.max(0.6, radius);
    s.o = opacity;
    s.mesh.material.color.set(color);
    s.mesh.visible = true;
  }

  /** 受击闪红：红色发光壳 0.15s（附加层，到期必消失；壳跟随模型） */
  flashHit(model, radius = 1) {
    this.glowShell(model, 0xff3020, 0.15, radius * 1.35 + 0.4, 0.5);
  }

  /** 死亡消散（向后倒地+淡出+轻微下沉；英雄重生时由 unitSpawned 钩子恢复 rotation.x） */
  dissolve(model, dur = 0.9) {
    if (!model) return;
    if (model.userData._origScale === undefined) model.userData._origScale = model.scale.x;
    const entries = [];
    model.traverse(o => {
      if (o.isMesh && o.material) {
        if (!o.material.userData._orig) {
          o.material.userData._orig = { transparent: o.material.transparent, opacity: o.material.opacity };
        }
        entries.push({ mat: o.material, orig: o.material.userData._orig });
        o.material.transparent = true;
      }
    });
    this._dissolves.push({ model, t: dur, dur, entries, fall: true });
  }

  /** 防御塔倒塌（倾斜+下沉+烟尘） */
  collapse(model, dur = 1.4) {
    if (!model) return;
    this._dissolves.push({ model, t: dur, dur, entries: [], tilt: true });
    this.burst(model.position.x, 1, model.position.z, { color: 0x998866, count: 30, speed: 6, life: 1.0, up: 6 });
  }

  /** 升级光效（金色光柱+粒子+地面环） */
  levelUp(x, z) {
    this.pillar(x, z, 0xffe060, 0.9, 6, 0.85);
    this.groundCircle(x, z, 2.0, 0xffe060, 0.9);
    this.burst(x, 0.3, z, { color: 0xffe060, count: 26, speed: 3, life: 0.9, up: 7, shock: false });
  }

  /** 暴君/主宰击杀：全队英雄金光（金柱+金环+金雨） */
  teamGold(team) {
    if (!this._state) return;
    for (const u of this._state.units) {
      if (u.kind !== 'hero' || u.team !== team || !u.alive) continue;
      this.pillar(u.pos.x, u.pos.z, 0xffd850, 1.1, 7, 1.1);
      this.groundCircle(u.pos.x, u.pos.z, 1.8, 0xffd850, 1.0);
      this.burst(u.pos.x, 1, u.pos.z, { color: 0xffd850, count: 22, speed: 3.5, life: 0.9, up: 8, shock: false });
    }
  }

  // ---------------- 状态特效（每帧轮询，只读 state） ----------------
  _pollStatus(dt) {
    const st = this._state;
    if (!st) return;
    for (const u of st.units) {
      if (u.kind !== 'hero') continue;
      const active = u.alive;
      // ---- 眩晕/击飞：头顶旋转星星 ----
      const stunned = active && (u.hasBuff('stun') || u.hasBuff('knockup'));
      let ss = this._stunStars.get(u.id);
      if (stunned && !ss) {
        const grp = new THREE.Group();
        const sprites = [];
        for (let i = 0; i < 3; i++) {
          const sp = new THREE.Sprite(new THREE.SpriteMaterial({
            map: this._starTex, color: 0xffe060, transparent: true,
            blending: THREE.AdditiveBlending, depthWrite: false,
          }));
          sp.scale.setScalar(0.5);
          grp.add(sp);
          sprites.push(sp);
        }
        this.scene.add(grp);
        ss = { grp, sprites, unit: u, ang: Math.random() * TAU_ };
        this._stunStars.set(u.id, ss);
      }
      if (ss) {
        if (!stunned) {
          this.scene.remove(ss.grp);
          this._stunStars.delete(u.id);
        } else {
          ss.ang += dt * 5.2;
          const y = u.pos.y + (u.barHeight || 3.1) * 0.78;
          for (let i = 0; i < 3; i++) {
            const a = ss.ang + i * (Math.PI * 2 / 3);
            ss.sprites[i].position.set(
              u.pos.x + Math.cos(a) * 0.62,
              y + Math.sin(ss.ang * 1.7 + i) * 0.1,
              u.pos.z + Math.sin(a) * 0.62);
          }
        }
      }
      // ---- 红/蓝 BUFF 环绕光环 ----
      let aura = this._auras.get(u.id);
      const wantRed = active && u.hasBuff('redBuff');
      const wantBlue = active && u.hasBuff('blueBuff');
      if ((wantRed || wantBlue) && !aura) {
        aura = { unit: u, red: this._makeAura(0xff5030), blue: this._makeAura(0x40a0ff) };
        this.scene.add(aura.red.grp, aura.blue.grp);
        this._auras.set(u.id, aura);
      }
      if (aura) {
        if (!active) {
          this.scene.remove(aura.red.grp, aura.blue.grp);
          this._auras.delete(u.id);
        } else {
          this._updateAura(aura.red, wantRed, u, dt);
          this._updateAura(aura.blue, wantBlue, u, dt);
        }
      }
      // ---- 回城引导：蓝色光柱 ----
      const recalling = active && u.channel && u.channel.type === 'recall';
      let rc = this._recalls.get(u.id);
      if (recalling && !rc) {
        const grp = new THREE.Group();
        const beam = new THREE.Mesh(
          new THREE.CylinderGeometry(1.0, 1.3, 7, 12, 1, true),
          new THREE.MeshBasicMaterial({
            color: 0x66ccff, transparent: true, opacity: 0.3,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
          }));
        beam.position.y = 3.5;
        grp.add(beam);
        const rring = new THREE.Mesh(
          new THREE.RingGeometry(1.1, 1.5, 32),
          new THREE.MeshBasicMaterial({ color: 0x88d8ff, transparent: true, opacity: 0.8, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
        rring.rotation.x = -Math.PI / 2;
        rring.position.y = 0.1;
        grp.add(rring);
        this.scene.add(grp);
        rc = { grp, unit: u, beam, rring };
        this._recalls.set(u.id, rc);
      }
      if (rc) {
        if (!recalling) {
          this.scene.remove(rc.grp);
          this._recalls.delete(u.id);
        } else {
          rc.grp.position.set(u.pos.x, 0, u.pos.z);
          rc.rring.rotation.z = this._time * 2.2;
          rc.beam.material.opacity = 0.24 + Math.sin(this._time * 6) * 0.08;
          // 上升光点
          if (Math.random() < dt * 14) {
            this._emit(u.pos.x + (Math.random() - 0.5) * 1.6, 0.3, u.pos.z + (Math.random() - 0.5) * 1.6,
              0, 3.2, 0, 0.7, RECALL_COL, -2);
          }
        }
      }
    }
    // ---- 泉水回复绿光（节流） ----
    this._sparkT -= dt;
    if (this._sparkT <= 0) {
      this._sparkT = 0.18;
      for (const f of st.fountains) {
        for (const u of st.units) {
          if (u.kind !== 'hero' || !u.alive || u.team !== f.team) continue;
          if (u.hp >= u.maxHp * 0.995) continue;
          const dx = u.pos.x - f.x, dz = u.pos.z - f.z;
          if (dx * dx + dz * dz > 36) continue;   // FOUNTAIN.R²
          this._emit(u.pos.x + (Math.random() - 0.5) * 1.4, 0.4, u.pos.z + (Math.random() - 0.5) * 1.4,
            0, 2.2, 0, 0.6, HEAL_COL, -2.5);
        }
      }
    }
  }

  _makeAura(color) {
    const grp = new THREE.Group();
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(0.95, 1.25, 32),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.55, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.14;
    grp.add(ring);
    // 环绕光点
    const sprites = [];
    for (let i = 0; i < 2; i++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({
        map: this._dotTex, color, transparent: true,
        blending: THREE.AdditiveBlending, depthWrite: false,
      }));
      sp.scale.setScalar(0.55);
      grp.add(sp);
      sprites.push(sp);
    }
    return { grp, ring, sprites, ang: Math.random() * TAU_ };
  }

  _updateAura(aura, want, u, dt) {
    aura.grp.visible = want;
    if (!want) return;
    aura.ang += dt * 2.6;
    aura.grp.position.set(u.pos.x, 0, u.pos.z);
    aura.ring.rotation.z = aura.ang * 0.5;
    for (let i = 0; i < aura.sprites.length; i++) {
      const a = aura.ang + i * Math.PI;
      aura.sprites[i].position.set(Math.cos(a) * 1.1, 0.45 + Math.sin(this._time * 3 + i * 2) * 0.18, Math.sin(a) * 1.1);
    }
  }

  /** 渲染帧更新 */
  update(dt) {
    this._time += dt;
    // 粒子
    for (let i = 0; i < MAX_PARTICLES; i++) {
      if (this._pLife[i] <= 0) continue;
      this._pLife[i] -= dt;
      if (this._pLife[i] <= 0) {
        this._pPos[i * 3 + 1] = -100;
        this._pCol[i * 3] = this._pCol[i * 3 + 1] = this._pCol[i * 3 + 2] = 0;
        this._pFree.push(i);
        continue;
      }
      this._pVel[i * 3 + 1] -= this._pGrav[i] * dt;   // 重力（负值=上浮）
      this._pPos[i * 3] += this._pVel[i * 3] * dt;
      this._pPos[i * 3 + 1] += this._pVel[i * 3 + 1] * dt;
      this._pPos[i * 3 + 2] += this._pVel[i * 3 + 2] * dt;
      if (this._pPos[i * 3 + 1] < 0.05) this._pPos[i * 3 + 1] = 0.05;
      const f = this._pLife[i] / this._pMaxLife[i];
      this._pCol[i * 3] = this._pBaseCol[i * 3] * f;
      this._pCol[i * 3 + 1] = this._pBaseCol[i * 3 + 1] * f;
      this._pCol[i * 3 + 2] = this._pBaseCol[i * 3 + 2] * f;
    }
    this._points.geometry.attributes.position.needsUpdate = true;
    this._points.geometry.attributes.color.needsUpdate = true;

    // 激光
    for (let i = this._beams.length - 1; i >= 0; i--) {
      const b = this._beams[i];
      b.t -= dt;
      b.mesh.material.opacity = 0.85 * (b.t / b.max);
      if (b.t <= 0) {
        this.scene.remove(b.mesh);
        b.mesh.material.dispose();
        this._beams.splice(i, 1);
      }
    }
    // 地面圈（预警圈脉冲）
    for (let i = this._circles.length - 1; i >= 0; i--) {
      const c = this._circles[i];
      c.t -= dt;
      const f = Math.max(0, c.t / c.max);
      const pulse = c.warn ? (0.72 + 0.28 * Math.sin(this._time * 11)) : 1;
      c.mats[0].opacity = 0.85 * f * pulse;
      c.mats[1].opacity = (c.warn ? 0.22 : 0.14) * f * pulse;
      if (c.warn) {
        const s = 1 + Math.sin(this._time * 11) * 0.03;
        c.mesh.scale.set(s, 1, s);
      }
      if (c.t <= 0) {
        this.scene.remove(c.mesh);
        c.mats.forEach(m => m.dispose());
        this._circles.splice(i, 1);
      }
    }
    // 箭头
    for (let i = this._arrows.length - 1; i >= 0; i--) {
      const a = this._arrows[i];
      a.t -= dt;
      a.mesh.material.opacity = 0.8 * Math.max(0, a.t / a.max);
      if (a.t <= 0) {
        this.scene.remove(a.mesh);
        a.mesh.material.dispose();
        this._arrows.splice(i, 1);
      }
    }
    // 冲击波环
    for (const s of this._shocks) {
      if (s.t <= 0) continue;
      s.t -= dt;
      const f = Math.max(0, s.t / s.max);
      const r = s.r * (1.25 - f * 0.95);
      s.mesh.scale.set(r, r, 1);
      s.mesh.material.opacity = 0.75 * f;
      if (s.t <= 0) {
        s.mesh.visible = false;
        this._shockFree.push(s);
      }
    }
    // 光柱
    for (const p of this._pillars) {
      if (p.t <= 0) continue;
      p.t -= dt;
      const f = Math.max(0, p.t / p.max);
      const grow = Math.min(1, (1 - f) * 4);          // 快速升起
      p.mesh.scale.set(p.r * (1 + (1 - f) * 0.35), p.h * grow, p.r * (1 + (1 - f) * 0.35));
      p.mesh.material.opacity = 0.65 * f;
      p.mesh.rotation.y += dt * 2.4;
      if (p.t <= 0) {
        p.mesh.visible = false;
        this._pillarFree.push(p);
      }
    }
    // 发光壳（跟随单位，淡出；到期/模型离场自动回收——闪红必在 0.15s 内消失）
    for (const s of this._shells) {
      if (s.t <= 0) continue;
      s.t -= dt;
      const gone = !s.model || !s.model.parent;
      const f = gone ? 0 : Math.max(0, s.t / s.max);
      if (!gone) {
        s.mesh.position.set(s.model.position.x, s.model.position.y + s.r * 0.62, s.model.position.z);
        const g = 1 + (1 - f) * 0.15;
        s.mesh.scale.set(s.r * g, s.r * 1.05 * g, s.r * g);
        s.mesh.material.opacity = s.o * f;
      }
      if (s.t <= 0 || gone) {
        s.t = 0;
        s.mesh.visible = false;
        s.model = null;
        this._shellFree.push(s);
      }
    }
    // 消散/倒塌
    for (let i = this._dissolves.length - 1; i >= 0; i--) {
      const d = this._dissolves[i];
      d.t -= dt;
      const f = Math.max(0, d.t / d.dur);
      for (const e of d.entries) e.mat.opacity = e.orig.opacity * f;
      if (d.tilt) {
        d.model.rotation.z = (1 - f) * 1.1;
        d.model.position.y = -(1 - f) * 3;
      } else {
        // 向后倒地 + 轻微下沉（重生时 rotation.x 由 main.js unitSpawned 钩子复位）
        d.model.rotation.x = -(1 - f) * 1.4;
        d.model.position.y = -(1 - f) * 0.6;
      }
      if (d.t <= 0) {
        d.model.visible = false;
        if (d.model.parent) d.model.parent.remove(d.model);
        this._dissolves.splice(i, 1);
      }
    }
    // 状态特效轮询
    this._pollStatus(dt);
  }
}

// 模块内共享常量
const TAU_ = Math.PI * 2;
const RECALL_COL = new THREE.Color(0x88d8ff);
const HEAL_COL = new THREE.Color(0x7dff9a);
