// ============================================================
// HUD（阶段4 完整版，王者荣耀横屏布局）
//   单位头顶血条：己方蓝条/敌方红条/中立黄条；塔与水晶带底座框；玩家名字白色高亮
//   右下：大普攻按钮(按住连攻) + S1/S2/Ult(冷却圆形扫过遮罩/蓝耗置灰/
//         按住拖动出瞄准指示器(方向箭头/范围圈)，松手向拖动方向施放，点按=自动瞄准)
//         + 召唤师技能 闪现(H)/恢复(G) + 回城(B)
//   底部中央：大血条+蓝条+等级圆标+金币+KDA+装备6格栏+商店按钮
//   顶部中央：比分+对局时间；右上静音开关（击杀播报条在 screens.js）
//   飘字（DOM 对象池）
// ============================================================
import * as THREE from 'three';
import { Pool } from '../utils.js';
import { TEAM, HEAL_CFG, FLASH_CFG } from '../config.js';
import { SKILLS } from '../game/skills.js';
import { ITEMS, ITEM_STYLE } from '../game/shop.js';

const FLOATER_LIFE = 0.9;   // 飘字时长(s)
const AIM_DRAG_PX = 20;     // 进入拖动瞄准的像素阈值
const AIM_FULL_PX = 110;    // 范围技能拖到此像素距离=最大射程

// 屏幕 → 世界方向映射（与 input.js 同一组基向量：相机固定偏航）
const A = Math.SQRT1_2;
const FWD = { x: A, z: A };     // 屏幕上方向
const RIGHT = { x: -A, z: A };  // 屏幕右方向

export class HUD {
  /**
   * @param {HTMLElement} root #ui 根节点
   */
  constructor(root) {
    this.root = root;
    this.state = null;
    this.onSkill = null;      // (slot)=>void 点按自动瞄准
    this.onSkillDir = null;   // (slot, {x,z,dist})=>void 拖动方向施放
    this.onRecall = null;     // ()=>void
    this.onFlash = null;
    this.onHeal = null;
    this.onShop = null;
    this.onMute = null;
    this.attackHeld = false;
    this.floaterCount = 0;    // 自检统计

    // ---- 摇杆 DOM（input.js 绑定行为）----
    this.joyBase = document.createElement('div');
    this.joyBase.id = 'joy-base';
    this.joyKnob = document.createElement('div');
    this.joyKnob.id = 'joy-knob';
    this.joyBase.appendChild(this.joyKnob);
    root.appendChild(this.joyBase);

    // ---- 血条层 ----
    this.barLayer = document.createElement('div');
    root.appendChild(this.barLayer);

    // 血条对象池
    this._barPool = new Pool(() => {
      const el = document.createElement('div');
      el.className = 'hpbar';
      el.innerHTML = '<div class="fill"></div><div class="mp"><i></i></div><div class="name"></div>';
      el.style.display = 'none';
      el.style.left = '0'; el.style.top = '0';   // 定位走 transform（p5b）
      this.barLayer.appendChild(el);
      return {
        el,
        fill: el.querySelector('.fill'),
        mpRow: el.querySelector('.mp'),
        mp: el.querySelector('.mp i'),
        nameEl: el.querySelector('.name'),
        unit: null,
      };
    }, (bar) => {
      bar.unit = null;
      bar.el.style.display = 'none';
    });
    this._activeBars = [];

    // ---- 飘字层（DOM 对象池） ----
    this.floaterLayer = document.createElement('div');
    root.appendChild(this.floaterLayer);
    this._floaterPool = new Pool(() => {
      const el = document.createElement('div');
      el.className = 'float-text';
      el.style.display = 'none';
      el.style.left = '0'; el.style.top = '0';   // 定位走 transform（p5b）
      this.floaterLayer.appendChild(el);
      return { el, age: 0, x: 0, y: 0, z: 0, active: false };
    }, (f) => { f.active = false; f.el.style.display = 'none'; });
    this._floaters = [];

    // ---- HUD 框架容器（选将界面时整体隐藏） ----
    this.chrome = document.createElement('div');
    this.chrome.id = 'hud-chrome';
    root.appendChild(this.chrome);

    // ---- 顶部中央：比分 + 对局时间 ----
    this.topbar = document.createElement('div');
    this.topbar.id = 'topbar';
    this.topbar.setAttribute('data-ui', '1');
    this.topbar.innerHTML =
      '<span class="sc blue" id="tb-blue">0</span>' +
      '<span class="vs">:</span>' +
      '<span class="sc red" id="tb-red">0</span>' +
      '<span class="sep"></span>' +
      '<span id="tb-time">00:00</span>';
    this.chrome.appendChild(this.topbar);
    this._tbBlue = this.topbar.querySelector('#tb-blue');
    this._tbRed = this.topbar.querySelector('#tb-red');
    this._tbTime = this.topbar.querySelector('#tb-time');

    // ---- 右上静音开关 ----
    this.muteBtn = document.createElement('div');
    this.muteBtn.id = 'mute-btn';
    this.muteBtn.setAttribute('data-ui', '1');
    this.muteBtn.textContent = '🔊';
    this.muteBtn.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      if (this.onMute) {
        const on = this.onMute();
        this.muteBtn.textContent = on ? '🔊' : '🔇';
      }
    });
    this.chrome.appendChild(this.muteBtn);

    // ---- 底部中央：玩家面板（血蓝/等级/金币/KDA/装备栏/商店） ----
    this.panel = document.createElement('div');
    this.panel.id = 'player-panel';
    this.panel.setAttribute('data-ui', '1');
    this.panel.innerHTML = `
      <div class="lv" id="pp-lv">1</div>
      <div class="bars">
        <div class="hp"><i id="pp-hp"></i><span id="pp-hp-t"></span></div>
        <div class="mp"><i id="pp-mp"></i></div>
        <div class="info"><span id="pp-name"></span><span id="pp-kda">0/0/0</span><span class="gold">◈ <b id="pp-gold">0</b></span></div>
      </div>
      <div class="items" id="pp-items"></div>
      <div class="shop-btn" id="pp-shop">商店</div>`;
    this.chrome.appendChild(this.panel);
    this._pp = {
      lv: this.panel.querySelector('#pp-lv'),
      hp: this.panel.querySelector('#pp-hp'),
      hpT: this.panel.querySelector('#pp-hp-t'),
      mp: this.panel.querySelector('#pp-mp'),
      name: this.panel.querySelector('#pp-name'),
      kda: this.panel.querySelector('#pp-kda'),
      gold: this.panel.querySelector('#pp-gold'),
    };
    // 装备 6 格
    this._itemSlots = [];
    const itemsBox = this.panel.querySelector('#pp-items');
    for (let i = 0; i < 6; i++) {
      const s = document.createElement('div');
      s.className = 'islot';
      itemsBox.appendChild(s);
      this._itemSlots.push({ el: s, cur: undefined });
    }
    this.panel.querySelector('#pp-shop').addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      if (this.onShop) this.onShop();
    });

    // ---- 右下操作区：技能/普攻/召唤师技能/回城 ----
    this.controls = document.createElement('div');
    this.controls.id = 'controls';
    this.controls.setAttribute('data-ui', '1');
    this.chrome.appendChild(this.controls);

    this._skillBtns = {};
    this._aiming = null;    // 拖动瞄准状态 {slot, id, x0, y0, active, dist}
    const skillDefs = [
      { slot: 's1', key: 'Q', cls: 's1' },
      { slot: 's2', key: 'E', cls: 's2' },
      { slot: 'ult', key: 'R', cls: 'ult' },
    ];
    for (const sd of skillDefs) {
      const btn = document.createElement('div');
      btn.className = 'skill-btn ' + sd.cls;
      btn.setAttribute('data-ui', '1');
      btn.innerHTML = `<div class="label"></div><div class="cd"></div><div class="key">${sd.key}</div>`;
      btn.addEventListener('pointerdown', (e) => this._aimStart(e, sd.slot));
      this.controls.appendChild(btn);
      this._skillBtns[sd.slot] = {
        el: btn, cd: btn.querySelector('.cd'), label: btn.querySelector('.label'),
        cdMax: 0,
      };
    }
    // 拖动瞄准的全局指针事件
    window.addEventListener('pointermove', (e) => this._aimMove(e));
    window.addEventListener('pointerup', (e) => this._aimEnd(e));
    window.addEventListener('pointercancel', (e) => this._aimEnd(e));

    // 普攻大按钮（按住连续攻击）
    this.atkBtn = document.createElement('div');
    this.atkBtn.className = 'atk-btn';
    this.atkBtn.setAttribute('data-ui', '1');
    this.atkBtn.innerHTML = '<div class="label">攻</div>';
    this.atkBtn.addEventListener('pointerdown', (e) => { e.stopPropagation(); this.attackHeld = true; });
    window.addEventListener('pointerup', () => { this.attackHeld = false; });
    window.addEventListener('pointercancel', () => { this.attackHeld = false; });
    this.controls.appendChild(this.atkBtn);

    // 召唤师技能小按钮：闪现(H) / 恢复(G)
    this._sumBtns = {};
    const sumDefs = [
      { slot: 'flash', key: 'H', label: '闪现', cls: 'flash-btn', cb: 'onFlash', maxCd: FLASH_CFG.CD },
      { slot: 'heal', key: 'G', label: '恢复', cls: 'heal-btn', cb: 'onHeal', maxCd: HEAL_CFG.CD },
    ];
    for (const sd of sumDefs) {
      const btn = document.createElement('div');
      btn.className = 'sum-btn ' + sd.cls;
      btn.setAttribute('data-ui', '1');
      btn.innerHTML = `<div class="label">${sd.label}</div><div class="cd"></div><div class="key">${sd.key}</div>`;
      btn.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        if (this[sd.cb]) this[sd.cb]();
      });
      this.controls.appendChild(btn);
      this._sumBtns[sd.slot] = { el: btn, cd: btn.querySelector('.cd'), maxCd: sd.maxCd };
    }

    // 回城按钮(B)
    this.recallBtn = document.createElement('div');
    this.recallBtn.className = 'recall-btn';
    this.recallBtn.setAttribute('data-ui', '1');
    this.recallBtn.innerHTML = '<div class="label">回城</div><div class="cd"></div><div class="key">B</div>';
    this.recallBtn.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      if (this.onRecall) this.onRecall();
    });
    this.controls.appendChild(this.recallBtn);
    this._recallCd = this.recallBtn.querySelector('.cd');

    // ---- FPS 计数（p5：右上角小字，?fps=0 关闭） ----
    this._fpsEl = null;
    if (new URLSearchParams(location.search).get('fps') !== '0') {
      this._fpsEl = document.createElement('div');
      this._fpsEl.id = 'fps-counter';
      this._fpsEl.style.cssText =
        'position:fixed;top:44px;right:12px;z-index:60;' +
        'font:11px/1.3 ui-monospace,Menlo,monospace;color:#9fd0ff;' +
        'background:rgba(6,10,16,.45);padding:2px 6px;border-radius:4px;pointer-events:none;';
      this._fpsEl.textContent = 'FPS --';
      root.appendChild(this._fpsEl);
    }
    this._fpsFrames = 0;
    this._fpsT = 0;

    this._v3 = new THREE.Vector3();   // 投影复用
  }

  /** 显示/隐藏 HUD 框架（选将/结算时隐藏） */
  setVisible(v) {
    this.chrome.style.display = v ? '' : 'none';
  }

  /** 绑定 GameState：订阅事件 + 为既有单位挂血条 */
  bindState(state) {
    this.state = state;
    for (const u of state.units) this.attachBar(u);
    state.events.on('unitSpawned', (u) => this.attachBar(u));
    state.events.on('unitDied', (u) => this.detachBar(u));
    state.events.on('floatText', (p) => this._spawnFloater(p));
    if (state.player) this._pp.name.textContent = state.player.name;
  }

  // ---------------- 血条 ----------------
  attachBar(unit) {
    if (unit.noBar) return null;
    const bar = this._barPool.acquire();
    bar.unit = unit;
    bar.el.style.display = 'block';
    bar.el.classList.toggle('enemy', unit.team === TEAM.RED);
    bar.el.classList.toggle('neutral', unit.team !== TEAM.RED && unit.team !== TEAM.BLUE);
    bar.el.classList.toggle('structure', unit.kind === 'tower' || unit.kind === 'crystal');
    bar.el.classList.toggle('self', !!unit.isPlayer);
    bar.nameEl.textContent = (unit.kind === 'hero' || unit.kind === 'tower' || unit.kind === 'crystal') ? unit.name : '';
    this._activeBars.push(bar);
    return bar;
  }

  detachBar(unit) {
    const i = this._activeBars.findIndex(b => b.unit === unit);
    if (i >= 0) {
      this._barPool.release(this._activeBars[i]);
      this._activeBars.splice(i, 1);
    }
  }

  /** 世界坐标 → 屏幕坐标 */
  worldToScreen(worldPos, camera, out) {
    this._v3.copy(worldPos).project(camera);
    out.x = (this._v3.x * 0.5 + 0.5) * window.innerWidth;
    out.y = (-this._v3.y * 0.5 + 0.5) * window.innerHeight;
    out.visible = this._v3.z < 1 &&
      this._v3.x > -1.1 && this._v3.x < 1.1 && this._v3.y > -1.1 && this._v3.y < 1.1;
    return out;
  }

  // ---------------- 飘字 ----------------
  _spawnFloater(p) {
    const f = this._floaterPool.acquire();
    f.x = p.x; f.y = p.y; f.z = p.z;
    f.age = 0;
    f.active = true;
    f.el.textContent = p.text;
    f.el.className = 'float-text ' + (p.cls || '');
    f.el.style.display = 'block';
    this._floaters.push(f);
    this.floaterCount++;
  }

  // ---------------- 技能拖动瞄准 ----------------
  _aimStart(e, slot) {
    e.stopPropagation();
    this._aiming = { slot, id: e.pointerId, x0: e.clientX, y0: e.clientY, active: false, dist: 0 };
  }

  _aimMove(e) {
    const am = this._aiming;
    if (!am || e.pointerId !== am.id) return;
    const p = this.state && this.state.player;
    if (!p || !this.state.vfx) return;
    const def = SKILLS[p.heroId] && SKILLS[p.heroId][am.slot];
    if (!def || def.aim === 'self') return;                       // 自身增益无指示器
    if (!this.state.skills.castable(p, am.slot)) return;          // 不可施放不显示
    const dx = e.clientX - am.x0, dy = e.clientY - am.y0;
    const lenPx = Math.hypot(dx, dy);
    if (!am.active && lenPx < AIM_DRAG_PX) return;
    am.active = true;

    // 屏幕拖动方向 → 世界方向
    const sx = dx / (lenPx || 1), sy = dy / (lenPx || 1);
    const wx = RIGHT.x * sx + FWD.x * (-sy);
    const wz = RIGHT.z * sx + FWD.z * (-sy);
    const px = p.pos.x, pz = p.pos.z;

    if (def.aim === 'around') {
      this.state.vfx.aimShow({ x: px, z: pz, r: def.radius || 4 });
    } else if (def.aim === 'target') {
      this.state.vfx.aimShow({ x: px, z: pz, r: def.range || 8 });
    } else if (def.aim === 'line' || def.aim === 'dash') {
      this.state.vfx.aimShow({ x: px, z: pz, r: 0, dir: { x: wx, z: wz }, len: Math.min(def.range || 10, 24) });
    } else if (def.aim === 'area') {
      const d = (def.range || 10) * Math.min(1, lenPx / AIM_FULL_PX);
      am.dist = d;
      this.state.vfx.aimShow({
        x: px, z: pz, r: 0, dir: { x: wx, z: wz }, len: d,
        dropX: px + wx * d, dropZ: pz + wz * d, dropR: def.radius || 3,
      });
    }
  }

  _aimEnd(e) {
    const am = this._aiming;
    if (!am || e.pointerId !== am.id) return;
    this._aiming = null;
    if (this.state && this.state.vfx) this.state.vfx.aimHide();
    if (!am.active) {
      if (this.onSkill) this.onSkill(am.slot);     // 点按：自动瞄准
      return;
    }
    // 拖动施放：仅方向类技能支持（line/dash/area），其余回落自动瞄准
    const p = this.state && this.state.player;
    const def = p && SKILLS[p.heroId] && SKILLS[p.heroId][am.slot];
    if (def && (def.aim === 'line' || def.aim === 'dash' || def.aim === 'area')) {
      const dx = e.clientX - am.x0, dy = e.clientY - am.y0;
      const lenPx = Math.hypot(dx, dy) || 1;
      const sx = dx / lenPx, sy = dy / lenPx;
      const dir = {
        x: RIGHT.x * sx + FWD.x * (-sy),
        z: RIGHT.z * sx + FWD.z * (-sy),
        dist: def.aim === 'area' ? am.dist : undefined,
      };
      if (this.onSkillDir) this.onSkillDir(am.slot, dir);
    } else if (this.onSkill) {
      this.onSkill(am.slot);
    }
  }

  // ---------------- 每帧刷新 ----------------
  update(camera) {
    // FPS 计数（0.5s 刷新一次）
    if (this._fpsEl) {
      this._fpsFrames++;
      const now = performance.now();
      if (!this._fpsT) this._fpsT = now;
      const span = now - this._fpsT;
      if (span >= 500) {
        this._fpsEl.textContent = 'FPS ' + Math.round(this._fpsFrames / span * 1000);
        this._fpsFrames = 0;
        this._fpsT = now;
      }
    }
    const tmp = { x: 0, y: 0, visible: false };

    // 血条（p5b：transform 定位避免每帧 layout；小兵血条仅在受伤/战斗后显示 3s——王者荣耀同款）
    for (const bar of this._activeBars) {
      const u = bar.unit;
      if (!u || !u.alive) { bar.el.style.display = 'none'; continue; }
      if (u.kind === 'minion' && this.state && this.state.time - u.lastCombatT > 3) {
        bar.el.style.display = 'none'; continue;
      }
      this._v3.set(u.pos.x, u.pos.y + (u.barHeight || 3.1), u.pos.z);
      this.worldToScreen(this._v3, camera, tmp);
      if (!tmp.visible) { bar.el.style.display = 'none'; continue; }   // 隐藏屏幕外血条
      bar.el.style.display = 'block';
      bar.el.style.transform = `translate3d(${tmp.x | 0}px,${tmp.y | 0}px,0) translate(-50%,-100%)`;
      bar.fill.style.width = (u.hp / u.maxHp * 100).toFixed(1) + '%';
      if (u.maxMp > 0) {
        bar.mpRow.style.display = '';
        bar.mp.style.width = (u.mp / u.maxMp * 100).toFixed(1) + '%';
      } else {
        bar.mpRow.style.display = 'none';
      }
    }

    // 飘字（上浮+淡出）
    const dt = this._lastUpdate ? (performance.now() - this._lastUpdate) / 1000 : 0.016;
    this._lastUpdate = performance.now();
    for (let i = this._floaters.length - 1; i >= 0; i--) {
      const f = this._floaters[i];
      f.age += dt;
      if (f.age >= FLOATER_LIFE) {
        this._floaterPool.release(f);
        this._floaters.splice(i, 1);
        continue;
      }
      this._v3.set(f.x, f.y + f.age * 2.2, f.z);
      this.worldToScreen(this._v3, camera, tmp);
      if (!tmp.visible) { f.el.style.display = 'none'; continue; }
      f.el.style.display = 'block';
      f.el.style.transform = `translate3d(${tmp.x | 0}px,${tmp.y | 0}px,0) translate(-50%,-100%)`;
      f.el.style.opacity = String(1 - (f.age / FLOATER_LIFE) ** 2);
    }

    if (!this.state) return;

    // 顶部时间/比分
    const t = Math.floor(this.state.time);
    const mm = String(Math.floor(t / 60)).padStart(2, '0');
    const ss = String(t % 60).padStart(2, '0');
    this._tbTime.textContent = `${mm}:${ss}`;
    this._tbBlue.textContent = String(this.state.score.blue);
    this._tbRed.textContent = String(this.state.score.red);

    // 玩家面板 + 技能按钮
    const p = this.state.player;
    if (!p) return;
    this._pp.lv.textContent = String(p.level);
    this._pp.hp.style.width = (p.hp / p.maxHp * 100).toFixed(1) + '%';
    this._pp.hpT.textContent = `${Math.ceil(p.hp)}/${Math.ceil(p.maxHp)}`;
    this._pp.mp.style.width = p.maxMp > 0 ? (p.mp / p.maxMp * 100).toFixed(1) + '%' : '0%';
    this._pp.kda.textContent = `${p.kills}/${p.deaths}/${p.assists}`;
    this._pp.gold.textContent = String(Math.floor(p.gold));

    // 装备栏 6 格
    for (let i = 0; i < 6; i++) {
      const s = this._itemSlots[i];
      const itemId = p.items ? p.items[i] : null;
      if (s.cur === itemId) continue;
      s.cur = itemId;
      if (itemId && ITEM_STYLE[itemId]) {
        const st = ITEM_STYLE[itemId];
        s.el.textContent = st.icon;
        s.el.style.background = `linear-gradient(160deg, ${st.color}, rgba(10,14,22,.9))`;
        s.el.classList.add('filled');
        s.el.title = ITEMS[itemId] ? ITEMS[itemId].name : itemId;
      } else {
        s.el.textContent = '';
        s.el.style.background = '';
        s.el.classList.remove('filled');
        s.el.title = '';
      }
    }

    // 技能按钮：冷却扫过遮罩 / 蓝耗置灰 / 未加点锁定
    const defs = SKILLS[p.heroId] || {};
    for (const slot of Object.keys(this._skillBtns)) {
      const b = this._skillBtns[slot];
      const def = defs[slot];
      const learned = p.skillLevels[slot] > 0;
      const cd = p.cooldowns[slot];
      b.label.textContent = def ? def.name.slice(0, 2) : '';
      b.el.classList.toggle('locked', !learned);
      b.el.classList.toggle('nomana', !!def && p.maxMp > 0 && p.mp < def.mana);
      this._updateCdMask(b, cd, b.cdMax, () => { if (cd > b.cdMax) b.cdMax = cd; });
    }
    // 召唤师技能 CD
    for (const slot of Object.keys(this._sumBtns)) {
      const b = this._sumBtns[slot];
      this._updateCdMask(b, p.cooldowns[slot], b.maxCd);
    }
    // 回城 CD / 引导
    if (p.channel && p.channel.type === 'recall') {
      this._recallCd.style.display = 'flex';
      this._recallCd.style.background = 'rgba(6,10,16,.72)';
      this._recallCd.textContent = (p.channel.dur - p.channel.t).toFixed(1);
    } else {
      this._updateCdMask({ cd: this._recallCd }, p.cooldowns.recall, 8);
    }
  }

  /** 冷却圆形扫过遮罩：conic-gradient 从顶部顺时针扫（p5b：0.1s 粒度降频，避免每帧重写 gradient） */
  _updateCdMask(b, cd, cdMax, bumpMax) {
    if (!b) return;
    const tick = cd > 0 ? Math.ceil(cd * 10) : 0;
    if (b._cdTick === tick) return;
    b._cdTick = tick;
    if (cd > 0) {
      if (bumpMax) bumpMax();
      const max = Math.max(cdMax || 0, cd);
      const frac = Math.min(1, cd / max);
      b.cd.style.display = 'flex';
      b.cd.style.background =
        `conic-gradient(rgba(4,8,14,.85) ${frac * 360}deg, rgba(4,8,14,.12) 0deg)`;
      b.cd.textContent = cd >= 1 ? String(Math.ceil(cd)) : cd.toFixed(1);
    } else {
      b.cd.style.display = 'none';
      if (bumpMax) b.cdMax = 0;
    }
  }

  dispose() {
    this.root.innerHTML = '';
  }
}
