// ============================================================
// 小地图（阶段4）：左上角 200px Canvas
//   一次性离线渲染底图（斜向河道/三路兵线/基地区块/龙坑标记）
//   每 0.25s 刷新：蓝/红单位圆点（英雄大点/小兵小点）、存活防御塔图标、
//   野怪黄点、玩家视野框（相机朝向矩形，世界 45° 旋转四边形）
//   点击/拖动小地图移动相机观察，松开回弹跟随玩家
// 坐标约定：世界 (x,z)∈[-90,90] → 地图 (mx,my)∈[0,S]，
//   mx=(x+90)/180*S，my=(90-z)/180*S（蓝方基地在左下，红方右上，经典布局）
// ============================================================
import * as THREE from 'three';
import { MAP, TEAM } from '../config.js';

const S = 200;               // 小地图边长(px)
const REFRESH = 0.25;        // 动态层刷新间隔(s)
const A = Math.SQRT1_2;

export class Minimap {
  /**
   * @param {HTMLElement} root UI 根节点
   * @param {object} mapData buildMap() 返回（兵线路径/塔位）
   */
  constructor(root, mapData) {
    this.root = root;
    this.mapData = mapData;
    this._engine = null;
    this._getPlayer = null;
    this._panning = false;
    this._panObj = null;      // 相机观察点（临时跟随目标）
    this._timer = 0;

    const cv = document.createElement('canvas');
    cv.id = 'minimap';
    cv.width = cv.height = S;
    cv.setAttribute('data-ui', '1');
    root.appendChild(cv);
    this.canvas = cv;
    this.ctx = cv.getContext('2d');

    // ---- 底图（离线渲染一次） ----
    this._base = document.createElement('canvas');
    this._base.width = this._base.height = S;
    this._renderBase(this._base.getContext('2d'));

    // ---- 点击/拖动移动相机 ----
    cv.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      this._panning = true;
      this._panTo(e);
    });
    window.addEventListener('pointermove', (e) => { if (this._panning) this._panTo(e); });
    const endPan = () => {
      if (!this._panning) return;
      this._panning = false;
      // 松开回弹：恢复跟随玩家
      if (this._engine && this._getPlayer) {
        const p = this._getPlayer();
        if (p && p.model) this._engine.setFollowTarget(p.model);
      }
    };
    window.addEventListener('pointerup', endPan);
    window.addEventListener('pointercancel', endPan);

    this.setVisible(false);
  }

  /** 绑定相机引擎与玩家获取回调（startGame 时调用） */
  bind(engine, getPlayer) {
    this._engine = engine;
    this._getPlayer = getPlayer;
  }

  setVisible(v) { this.canvas.style.display = v ? '' : 'none'; }

  /** 世界 → 地图坐标 */
  _m(x, z) { return [(x + MAP.HALF) / MAP.SIZE * S, (MAP.HALF - z) / MAP.SIZE * S]; }
  /** 地图 → 世界坐标 */
  _w(mx, my) { return { x: mx / S * MAP.SIZE - MAP.HALF, z: MAP.HALF - my / S * MAP.SIZE }; }

  _panTo(e) {
    if (!this._engine) return;
    const r = this.canvas.getBoundingClientRect();
    const mx = (e.clientX - r.left) / r.width * S;
    const my = (e.clientY - r.top) / r.height * S;
    const w = this._w(mx, my);
    if (!this._panObj) this._panObj = { position: new THREE.Vector3() };
    this._panObj.position.set(
      Math.max(-MAP.HALF, Math.min(MAP.HALF, w.x)), 0,
      Math.max(-MAP.HALF, Math.min(MAP.HALF, w.z)));
    this._engine.setFollowTarget(this._panObj);
  }

  // ---------------- 底图 ----------------
  _renderBase(g) {
    // 底色：深绿峡谷
    const grad = g.createLinearGradient(0, 0, S, S);
    grad.addColorStop(0, '#1c3020');
    grad.addColorStop(1, '#14241a');
    g.fillStyle = grad;
    g.fillRect(0, 0, S, S);

    // 野区深色块（四象限）
    g.fillStyle = 'rgba(8,18,10,.5)';
    g.fillRect(0, S / 2, S / 2, S / 2);   // 左下（蓝方野区侧）
    g.fillRect(S / 2, 0, S / 2, S / 2);   // 右上（红方野区侧）

    // 河道（对角线 z=-x：地图左上→右下）
    g.strokeStyle = 'rgba(70,130,190,.75)';
    g.lineWidth = MAP.RIVER_WIDTH / MAP.SIZE * S;
    g.lineCap = 'round';
    g.beginPath();
    g.moveTo(0, 0);
    g.lineTo(S, S);
    g.stroke();

    // 三条兵线（土路色）
    g.strokeStyle = 'rgba(150,125,80,.8)';
    g.lineWidth = 2.4;
    for (const key of Object.keys(MAP.LANES)) {
      g.beginPath();
      MAP.LANES[key].forEach(([x, z], i) => {
        const [mx, my] = this._m(x, z);
        if (i === 0) g.moveTo(mx, my); else g.lineTo(mx, my);
      });
      g.stroke();
    }

    // 基地区块（双方石台）
    for (const [base, color] of [[MAP.BLUE_BASE, '#2a5aa8'], [MAP.RED_BASE, '#a83a2a']]) {
      const [mx, my] = this._m(base.x, base.z);
      g.fillStyle = color;
      g.beginPath();
      g.arc(mx, my, MAP.BASE_PLATFORM_R / MAP.SIZE * S, 0, Math.PI * 2);
      g.fill();
    }

    // 龙坑标记（暴君/主宰）
    g.strokeStyle = 'rgba(230,200,120,.9)';
    g.lineWidth = 1.2;
    for (const pit of [MAP.TYRANT_PIT, MAP.OVERLORD_PIT]) {
      const [mx, my] = this._m(pit.x, pit.z);
      g.beginPath();
      g.arc(mx, my, 4, 0, Math.PI * 2);
      g.stroke();
    }

    // 边框
    g.strokeStyle = 'rgba(220,190,120,.55)';
    g.lineWidth = 1.5;
    g.strokeRect(0.5, 0.5, S - 1, S - 1);
  }

  // ---------------- 动态刷新 ----------------
  /** 每帧调用（内部按 0.25s 节流重绘） */
  update(state, dt) {
    if (!state || this.canvas.style.display === 'none') return;
    this._timer -= dt;
    if (this._timer > 0) return;
    this._timer = REFRESH;

    const g = this.ctx;
    g.clearRect(0, 0, S, S);
    g.drawImage(this._base, 0, 0);

    // ---- 单位圆点 ----
    for (const u of state.units) {
      if (!u.alive || u.kind === 'summon') continue;
      const [mx, my] = this._m(u.pos.x, u.pos.z);
      if (u.kind === 'hero') {
        // 英雄大点
        g.fillStyle = u.team === TEAM.BLUE ? '#4da6ff' : '#ff5a4d';
        g.beginPath();
        g.arc(mx, my, 3.6, 0, Math.PI * 2);
        g.fill();
        if (u.isPlayer) {
          g.strokeStyle = '#ffffff';
          g.lineWidth = 1.6;
          g.stroke();
        }
      } else if (u.kind === 'minion') {
        g.fillStyle = u.team === TEAM.BLUE ? 'rgba(120,180,255,.85)' : 'rgba(255,130,110,.85)';
        g.fillRect(mx - 1.1, my - 1.1, 2.2, 2.2);
      } else if (u.kind === 'monster') {
        g.fillStyle = '#e8c860';
        g.beginPath();
        g.arc(mx, my, 1.8, 0, Math.PI * 2);
        g.fill();
      } else if (u.kind === 'tower') {
        // 存活防御塔：菱形图标
        g.fillStyle = u.team === TEAM.BLUE ? '#66b8ff' : '#ff7a66';
        g.save();
        g.translate(mx, my);
        g.rotate(Math.PI / 4);
        g.fillRect(-2.4, -2.4, 4.8, 4.8);
        g.restore();
      } else if (u.kind === 'crystal') {
        g.fillStyle = u.team === TEAM.BLUE ? '#a0d8ff' : '#ffa090';
        g.save();
        g.translate(mx, my);
        g.rotate(Math.PI / 4);
        g.fillRect(-3.4, -3.4, 6.8, 6.8);
        g.strokeStyle = '#fff';
        g.lineWidth = 1;
        g.strokeRect(-3.4, -3.4, 6.8, 6.8);
        g.restore();
      }
    }

    // ---- 玩家视野框（相机朝向矩形，45° 旋转四边形） ----
    if (this._engine && this._engine.viewCenter) {
      const c = this._engine.viewCenter;
      const hw = 17, hh = 11;   // 视野半宽/半高（世界单位）
      const corners = [
        [c.x + A * hw + A * hh, c.z - A * hw + A * hh],
        [c.x - A * hw + A * hh, c.z + A * hw + A * hh],
        [c.x - A * hw - A * hh, c.z + A * hw - A * hh],
        [c.x + A * hw - A * hh, c.z - A * hw - A * hh],
      ];
      g.strokeStyle = 'rgba(255,255,255,.75)';
      g.lineWidth = 1.2;
      g.beginPath();
      corners.forEach(([x, z], i) => {
        const [mx, my] = this._m(x, z);
        if (i === 0) g.moveTo(mx, my); else g.lineTo(mx, my);
      });
      g.closePath();
      g.stroke();
    }
  }
}
