// ============================================================
// 输入：左下虚拟摇杆（Pointer Events，多点触控/鼠标）
//      + 键盘 WASD/方向键 回退
// 输出：归一化的世界坐标移动向量 {x, z}
// 屏幕"上" = 世界 (1,0,1)/√2（朝红方），屏幕"右" = (-1,0,1)/√2
// ============================================================
import { CAMERA } from '../config.js';

const A = Math.SQRT1_2; // 1/√2
// 世界基向量（与相机固定偏航一致）
const FWD = { x: A, z: A };     // 屏幕上方向
const RIGHT = { x: -A, z: A };  // 屏幕右方向

const JOY_RADIUS = 52;          // 摇杆最大行程 px
const JOY_ZONE_W = 0.45;        // 屏幕左侧 45% 为摇杆热区

export class Input {
  /**
   * @param {object} opts { base, knob } 摇杆 DOM（由 hud 创建）
   */
  constructor(opts) {
    this._base = opts.base;
    this._knob = opts.knob;

    this._joyId = null;         // 当前摇杆指针 id
    this._joyVec = { x: 0, y: 0 }; // 屏幕空间摇杆向量（右+ 下+），长度≤1
    this._joyCenter = { x: 0, y: 0 };
    this._keys = new Set();

    this._onPointerDown = this._down.bind(this);
    this._onPointerMove = this._move.bind(this);
    this._onPointerUp = this._up.bind(this);
    window.addEventListener('pointerdown', this._onPointerDown);
    window.addEventListener('pointermove', this._onPointerMove);
    window.addEventListener('pointerup', this._onPointerUp);
    window.addEventListener('pointercancel', this._onPointerUp);

    // 阶段2：技能/普攻/回城按键 + 鼠标位置（技能指示示意用）
    this._actionQueue = [];        // 's1'|'s2'|'ult'|'recall'
    this.attackHeld = false;       // 空格按住
    this.mouse = { x: 0, y: 0 };
    window.addEventListener('keydown', e => {
      this._keys.add(e.code);
      if (e.code === 'Space') { this.attackHeld = true; e.preventDefault(); }
      else if (e.code === 'KeyQ') this._actionQueue.push('s1');
      else if (e.code === 'KeyE') this._actionQueue.push('s2');
      else if (e.code === 'KeyR') this._actionQueue.push('ult');
      else if (e.code === 'KeyB') this._actionQueue.push('recall');
      else if (e.code === 'KeyH') this._actionQueue.push('flash');  // 召唤师技能：闪现
      else if (e.code === 'KeyG') this._actionQueue.push('heal');   // 召唤师技能：恢复
    });
    window.addEventListener('keyup', e => {
      this._keys.delete(e.code);
      if (e.code === 'Space') this.attackHeld = false;
    });
    window.addEventListener('blur', () => { this._keys.clear(); this.attackHeld = false; });
    window.addEventListener('pointermove', e => { this.mouse.x = e.clientX; this.mouse.y = e.clientY; });
  }

  /** 取出并清空动作队列 */
  consumeActions() {
    const q = this._actionQueue;
    this._actionQueue = [];
    return q;
  }

  _down(e) {
    if (this._joyId !== null) return;                    // 已有一个摇杆指针
    if (e.clientX > window.innerWidth * JOY_ZONE_W) return; // 右侧留给技能区
    if (e.target.closest && e.target.closest('[data-ui]')) return; // 不抢 UI 按钮
    this._joyId = e.pointerId;
    this._joyCenter.x = e.clientX;
    this._joyCenter.y = e.clientY;
    this._base.style.display = 'block';
    this._base.style.left = e.clientX + 'px';
    this._base.style.top = e.clientY + 'px';
    this._setKnob(0, 0);
  }

  _move(e) {
    if (e.pointerId !== this._joyId) return;
    let dx = e.clientX - this._joyCenter.x;
    let dy = e.clientY - this._joyCenter.y;
    const len = Math.hypot(dx, dy);
    if (len > JOY_RADIUS) { dx = dx / len * JOY_RADIUS; dy = dy / len * JOY_RADIUS; }
    this._setKnob(dx, dy);
    this._joyVec.x = dx / JOY_RADIUS;
    this._joyVec.y = dy / JOY_RADIUS;
  }

  _up(e) {
    if (e.pointerId !== this._joyId) return;
    this._joyId = null;
    this._joyVec.x = 0; this._joyVec.y = 0;
    this._base.style.display = 'none';
  }

  _setKnob(dx, dy) {
    this._knob.style.transform =
      `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
  }

  /** 键盘向量（屏幕空间：x 右+，y 上+） */
  _keyVec() {
    let x = 0, y = 0;
    const k = this._keys;
    if (k.has('KeyW') || k.has('ArrowUp')) y += 1;
    if (k.has('KeyS') || k.has('ArrowDown')) y -= 1;
    if (k.has('KeyD') || k.has('ArrowRight')) x += 1;
    if (k.has('KeyA') || k.has('ArrowLeft')) x -= 1;
    return { x, y };
  }

  /**
   * 归一化世界坐标移动向量；无输入时返回零向量
   * @returns {{x:number, z:number}}
   */
  getMoveVector() {
    // 屏幕空间合成（摇杆 y 向下为正，取反）
    const kv = this._keyVec();
    let sx = this._joyVec.x + kv.x;
    let sy = -this._joyVec.y + kv.y;   // sy：屏幕上方向为正
    const len = Math.hypot(sx, sy);
    if (len > 1) { sx /= len; sy /= len; }
    if (len < 0.08) return { x: 0, z: 0 };  // 死区
    // 映射到世界坐标
    return {
      x: RIGHT.x * sx + FWD.x * sy,
      z: RIGHT.z * sx + FWD.z * sy,
    };
  }

  dispose() {
    window.removeEventListener('pointerdown', this._onPointerDown);
    window.removeEventListener('pointermove', this._onPointerMove);
    window.removeEventListener('pointerup', this._onPointerUp);
    window.removeEventListener('pointercancel', this._onPointerUp);
  }
}
