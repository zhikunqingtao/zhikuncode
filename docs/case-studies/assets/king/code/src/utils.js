// ============================================================
// 通用工具：数学 / 随机数 / 对象池 / 事件总线
// ============================================================

export const TAU = Math.PI * 2;

export function clamp(v, min, max) { return v < min ? min : (v > max ? max : v); }

export function lerp(a, b, t) { return a + (b - a) * t; }

// 帧率无关的指数平滑系数
export function damp(rate, dt) { return 1 - Math.exp(-rate * dt); }

export function dist2(ax, az, bx, bz) {
  const dx = ax - bx, dz = az - bz;
  return dx * dx + dz * dz;
}

export function dist(ax, az, bx, bz) { return Math.sqrt(dist2(ax, az, bx, bz)); }

// 角度插值（走最短弧）
export function angleLerp(a, b, t) {
  let d = (b - a) % TAU;
  if (d > Math.PI) d -= TAU;
  if (d < -Math.PI) d += TAU;
  return a + d * t;
}

// 点到线段距离（用于兵线/碰撞判定）
export function distToSegment(px, pz, ax, az, bx, bz) {
  const abx = bx - ax, abz = bz - az;
  const len2 = abx * abx + abz * abz;
  let t = len2 > 0 ? ((px - ax) * abx + (pz - az) * abz) / len2 : 0;
  t = clamp(t, 0, 1);
  return dist(px, pz, ax + abx * t, az + abz * t);
}

// 确定性随机数（地图装饰布局需要每次加载一致）
export function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------- 对象池 ----------------
export class Pool {
  /**
   * @param {Function} factory 创建新对象
   * @param {Function} [reset] 回收时的清理回调
   */
  constructor(factory, reset) {
    this._factory = factory;
    this._reset = reset || null;
    this._free = [];
  }
  acquire() {
    return this._free.length > 0 ? this._free.pop() : this._factory();
  }
  release(obj) {
    if (this._reset) this._reset(obj);
    this._free.push(obj);
  }
}

// ---------------- 事件总线 ----------------
export class EventBus {
  constructor() { this._map = new Map(); }
  on(type, fn) {
    if (!this._map.has(type)) this._map.set(type, []);
    this._map.get(type).push(fn);
    return () => this.off(type, fn);
  }
  off(type, fn) {
    const arr = this._map.get(type);
    if (arr) {
      const i = arr.indexOf(fn);
      if (i >= 0) arr.splice(i, 1);
    }
  }
  emit(type, payload) {
    const arr = this._map.get(type);
    if (arr) for (const fn of arr.slice()) fn(payload);
  }
}
