// ============================================================
// GameState：单位表 / 队伍 / 时间 / 比分 / 事件
// 阶段2：完整单位系统——
//   单位基类（队伍/位置/血蓝/AD/AP/双抗/移速/攻速/射程/碰撞半径/受击面向）
//   伤害公式 面板×100/(100+抗性)；死亡掉落金币经验；单位注册表与空间查询
//   防御塔/水晶单位化（顺序无敌机制）；泉水回血/敌入泉水真伤；回城/重生
// ============================================================
import * as THREE from 'three';
import {
  MAP, HERO, HEROES, SPAWN, TEAM, MINIONS, MONSTERS, TOWER_CFG, CRYSTAL_CFG,
  FOUNTAIN, ECON, EXP_TABLE, MAX_LEVEL, RESPAWN, RECALL_CFG, SKILL_ORDER,
  JUNGLE, BUFFS, HEAL_CFG, FLASH_CFG, BRUSH_CFG, ANNOUNCE, JUNGLE_PREF, CRIT,
  MINION_LANE_OFFSET, MINION_GROWTH,
} from '../config.js';
import { clamp, angleLerp, damp, dist, dist2, EventBus } from '../utils.js';
import { createHeroModel, createMinionModel, createMonsterModel } from '../world/models.js';
import { SkillSystem, SKILLS } from './skills.js';
import { Spawner } from './spawner.js';
import { updateMinion, updateTower, updateMonster, HeroAI } from './ai.js';
import { Shop } from './shop.js';

let _uid = 1;

// 共享 Buff 视觉资源
const SHIELD_GEO = new THREE.SphereGeometry(1.35, 12, 10);
const SHIELD_MAT = new THREE.MeshBasicMaterial({
  color: 0xffe080, transparent: true, opacity: 0.22,
  blending: THREE.AdditiveBlending, depthWrite: false,
});

// ---------------- 单位基类 ----------------
export class Unit {
  /**
   * @param {object} o { team, x, z, hp, mp, ad, ap, armor, mres, speed, aspeed, range, radius, name, model }
   */
  constructor(o) {
    this.id = _uid++;
    this.team = o.team;
    this.name = o.name || 'unit';
    this.kind = 'unit';              // hero|minion|tower|crystal|summon
    this.pos = new THREE.Vector3(o.x, 0, o.z);
    this.yaw = 0;
    this.hp = this.maxHp = o.hp;
    this.mp = this.maxMp = o.mp || 0;
    this.ad = o.ad || 0;
    this.ap = o.ap || 0;
    this.armor = o.armor || 0;
    this.mres = o.mres || 0;
    this.baseSpeed = o.speed;
    this.aspeed = o.aspeed || 1;     // 攻击次数/秒
    this.range = o.range || 1.5;
    this.radius = o.radius;
    this.model = o.model || null;
    this.static = false;             // 建筑：不移动/不推挤/不同步坐标
    this.noBar = false;              // 不显示血条（召唤物）
    this.barHeight = 3.1;
    this.alive = true;
    this.moving = false;

    // 战斗状态
    this.buffs = [];
    this.attackTimer = 0;
    this.target = null;
    this.lastCombatT = -99;          // 最近造成/承受伤害时刻（脱战判定）
    this.lastHitHeroT = -99;         // 最近攻击敌方英雄时刻（塔转火判定）
    this.lastAttacker = null;
    this.channel = null;             // 引导（回城）{type, t, dur}
    this.stealth = false;

    // 英雄成长
    this.level = 1;
    this.exp = 0;
    this.gold = 0;
    this._goldAcc = 0;               // 被动金币小数累积
    this.kills = 0; this.deaths = 0;
    this.assists = 0;                // 助攻（8s 内对受害者造成过伤害）
    this.creeps = 0;                 // 补刀数（击杀小兵）
    this.skillLevels = { s1: 0, s2: 0, ult: 0 };
    this.cooldowns = { s1: 0, s2: 0, ult: 0, recall: 0, heal: 0, flash: 0 };
    this.skillCastCount = { s1: 0, s2: 0, ult: 0 };
    this.respawnAt = null;

    // 阶段3：装备/暴击/草丛隐身
    this.crit = 0;                   // 暴击率（无尽战刃等）
    this.itemRegenPct = 0;           // 装备回血/s（霸者重装）
    this.shop = null;                // 英雄装备栏（spawnHero 初始化）
    this.items = null;               // = shop.slots（自检/阶段4 UI 用）
    this.inBrush = false;            // 是否在草丛内（每 tick 刷新）
    this.revealUntil = 0;            // 破隐截止时间（攻击/施法/同草敌人）

    if (this.model) this.model.position.copy(this.pos);
  }

  // ---------- Buff ----------
  hasBuff(type) { return this.buffs.some(b => b.type === type); }
  getBuff(type) { return this.buffs.find(b => b.type === type); }
  addBuff(buff) {
    // 同类型刷新（护盾/加速等取最新）
    const i = this.buffs.findIndex(b => b.type === buff.type && b.group === buff.group);
    if (i >= 0) this._removeBuffVisual(this.buffs.splice(i, 1)[0]);
    buff.maxDur = buff.dur;
    this.buffs.push(buff);
    if (buff.type === 'shield' && this.model) {
      const m = new THREE.Mesh(SHIELD_GEO, SHIELD_MAT);
      m.position.y = 1.4;
      m.scale.setScalar(this.radius * 1.5);
      this.model.add(m);
      buff._mesh = m;
    } else if (buff.type === 'stealth') {
      this.stealth = true;
      _setModelOpacity(this, 0.35);
    }
    return buff;
  }
  removeBuff(buff) {
    const i = this.buffs.indexOf(buff);
    if (i >= 0) {
      this.buffs.splice(i, 1);
      this._removeBuffVisual(buff);
    }
  }
  _removeBuffVisual(buff) {
    if (buff._mesh) { buff._mesh.parent && buff._mesh.parent.remove(buff._mesh); buff._mesh = null; }
    if (buff.type === 'stealth') { this.stealth = false; _setModelOpacity(this, 1); }
  }
  clearBuffs() { for (const b of this.buffs.slice()) this.removeBuff(b); }

  /** 硬控锁定（不能移动/攻击/施法） */
  isLocked() {
    return !!this.channel ||
      this.hasBuff('stun') || this.hasBuff('knockup') ||
      this.hasBuff('knockback') || this.hasBuff('dash');
  }
  canCast() { return !this.isLocked() && !this.hasBuff('silence'); }

  /** 实际移速：基础 × 加速 × (1-最强减速) */
  effSpeed() {
    if (this.isLocked()) return 0;
    let haste = 1, slow = 0;
    for (const b of this.buffs) {
      if (b.type === 'haste') haste *= b.mult;
      else if (b.type === 'slow') slow = Math.max(slow, b.pct);
    }
    return this.baseSpeed * haste * (1 - slow);
  }

  /** 朝某点移动一步（含平滑转身） */
  moveStep(dx, dz, dt) {
    const len = Math.hypot(dx, dz);
    if (len < 1e-4) { this.moving = false; return; }
    const sp = this.effSpeed();
    if (sp <= 0) { this.moving = false; return; }
    this.pos.x += (dx / len) * sp * dt;
    this.pos.z += (dz / len) * sp * dt;
    const targetYaw = Math.atan2(dx, dz);
    this.yaw = angleLerp(this.yaw, targetYaw, damp(HERO.TURN_SPEED, dt));
    this.moving = true;
  }

  /** 立即面向某点（受击面向/攻击面向） */
  face(x, z) { this.yaw = Math.atan2(x - this.pos.x, z - this.pos.z); }

  /** 承伤入口（转发给 state 统一结算） */
  takeDamage(amount, source, opts) {
    if (this._state) this._state.dealDamage(source, this, amount, opts);
  }

  heal(amount) {
    if (!this.alive) return;
    this.hp = Math.min(this.maxHp, this.hp + amount);
  }
  healMp(amount) {
    if (!this.alive) return;
    this.mp = Math.min(this.maxMp, this.mp + amount);
  }

  die(killer) {
    if (this._state) this._state.onUnitDied(this, killer);
    else this.alive = false;
  }

  /** 同步逻辑坐标 → 模型（击飞时加上腾空抛物线） */
  syncModel() {
    if (!this.model || this.static) return;
    this.model.position.copy(this.pos);
    const kb = this.getBuff('knockup');
    if (kb) {
      const p = 1 - kb.dur / kb.maxDur;
      this.model.position.y = Math.sin(p * Math.PI) * 1.4;
    }
    this.model.rotation.y = this.yaw;
  }
}

// 模型透明度（隐身/死亡消散共用；首次调用备份原始材质参数）
function _setModelOpacity(unit, factor) {
  const model = unit.model;
  if (!model) return;
  if (!model.userData._matBackup) {
    const backup = [];
    model.traverse(o => {
      if (o.isMesh && o.material) backup.push({ mat: o.material, transparent: o.material.transparent, opacity: o.material.opacity });
    });
    model.userData._matBackup = backup;
  }
  for (const b of model.userData._matBackup) {
    b.mat.transparent = factor < 1 ? true : b.transparent;
    b.mat.opacity = b.opacity * (factor < 1 ? factor : 1);
    if (factor >= 1) b.mat.opacity = b.opacity;   // 恢复
    b.mat.needsUpdate = false;
  }
  if (factor >= 1) {
    // 恢复原始值
    for (const b of model.userData._matBackup) {
      b.mat.transparent = b.transparent;
      b.mat.opacity = b.opacity;
    }
  }
}

// ---------------- 游戏状态 ----------------
export class GameState {
  /**
   * @param {object} o { scene, mapData, vfx }
   */
  constructor({ scene, mapData, vfx }) {
    this.scene = scene;
    this.vfx = vfx;
    this.colliders = mapData.colliders;
    this.mapLanes = mapData.lanes;
    this.events = new EventBus();
    this.units = [];
    this.time = 0;
    this.score = { blue: 0, red: 0 };
    this.over = false;
    this.winner = null;
    this.player = null;
    this.attackHeld = false;         // 空格/普攻按钮按住
    this._actionQueue = [];          // 玩家动作队列（技能/回城）
    this._demo = null;
    this.stats = { deaths: 0, towersDown: 0 };   // 自检统计

    // 阶段3：AI 英雄 / 播报 / 推塔与屠龙统计
    this.aiHeroes = [];              // HeroAI 控制器
    this.towersLost = { blue: 0, red: 0 };        // 各方被推塔数
    this.objectiveKills = {          // 各方屠龙/主宰次数
      tyrant: { blue: 0, red: 0 },
      overlord: { blue: 0, red: 0 },
    };
    this._firstBlood = false;
    this._teamDeaths = { blue: [], red: [] };     // 各方英雄死亡时间戳（团灭判定）
    this._lastAce = -99;

    // 草丛判定预计算（cos/sin）
    this._brushCache = MAP.BRUSHES.map(b => ({
      x: b.x, z: b.z, cos: Math.cos(-b.rot), sin: Math.sin(-b.rot),
    }));

    this.skills = new SkillSystem(this);
    this.spawner = new Spawner(this);

    // ---- 防御塔单位化 ----
    this.towerUnits = [];
    for (const t of mapData.towers) {
      const laneName = { mid: '中路', top: '上路', bot: '下路' }[t.lane];
      const u = new Unit({
        team: t.team, x: t.x, z: t.z, hp: TOWER_CFG.HP, mp: 0,
        ad: TOWER_CFG.AD, armor: TOWER_CFG.ARMOR, mres: TOWER_CFG.MRES,
        speed: 0, aspeed: TOWER_CFG.ASPEED, range: TOWER_CFG.RANGE,
        radius: TOWER_CFG.RADIUS, name: `${laneName}${['一', '二', '三'][t.tier - 1]}塔`,
        model: t.mesh,
      });
      u.kind = 'tower';
      u.static = true;
      u.lane = t.lane;
      u.tier = t.tier;
      u.barHeight = 9.6;
      u.combo = 0;
      u.comboTarget = null;
      const col = this.colliders.find(c => c.type === 'tower' && c.x === t.x && c.z === t.z);
      if (col) col.unit = u;   // 塔毁后碰撞体失效
      this._register(u);
      this.towerUnits.push(u);
    }

    // ---- 水晶单位化 ----
    this.crystals = {};
    for (const c of mapData.crystals) {
      const u = new Unit({
        team: c.team, x: c.x, z: c.z, hp: CRYSTAL_CFG.HP, mp: 0,
        armor: CRYSTAL_CFG.ARMOR, mres: CRYSTAL_CFG.MRES,
        speed: 0, range: 0, radius: CRYSTAL_CFG.RADIUS,
        name: '水晶', model: c.core,
      });
      u.kind = 'crystal';
      u.static = true;
      u.barHeight = 10.2;
      u._coreLight = c.light;
      const col = this.colliders.find(cc => cc.type === 'crystal' && cc.x === c.x && cc.z === c.z);
      if (col) col.unit = u;
      this._register(u);
      this.crystals[c.team] = u;
    }

    // ---- 泉水 ----
    this.fountains = [
      { team: TEAM.BLUE, x: MAP.BLUE_FOUNTAIN.x, z: MAP.BLUE_FOUNTAIN.z },
      { team: TEAM.RED, x: MAP.RED_FOUNTAIN.x, z: MAP.RED_FOUNTAIN.z },
    ];
  }

  _register(u) {
    u._state = this;
    this.units.push(u);
    this.events.emit('unitSpawned', u);
    if (u.model) this.scene.add(u.model);
    return u;
  }

  // ---------------- 空间查询 ----------------
  /** 范围内单位 @param filter (u)=>bool */
  unitsInCircle(x, z, r, filter) {
    const out = [];
    const r2 = r * r;
    for (const u of this.units) {
      if (!u.alive) continue;
      if (dist2(x, z, u.pos.x, u.pos.z) <= r2 && (!filter || filter(u))) out.push(u);
    }
    return out;
  }

  /** 可被攻击的敌人（排除隐身/草丛隐身/不可选中） */
  targetable(u, byTeam) {
    if (!u.alive || u.team === byTeam || u.stealth || u.untargetable || u.kind === 'summon') return false;
    // 草丛隐身：在草丛内且未破隐 → 敌方不可锁定
    if (u.inBrush && this.time >= (u.revealUntil || 0)) return false;
    return true;
  }

  /** 点是否在草丛内（旋转矩形近似） */
  isInBrush(x, z) {
    const hw = MAP.BRUSH_W / 2 + BRUSH_CFG.MARGIN;
    const hl = MAP.BRUSH_L / 2 + BRUSH_CFG.MARGIN;
    for (const b of this._brushCache) {
      const dx = x - b.x, dz = z - b.z;
      const lx = dx * b.cos - dz * b.sin;
      const lz = dx * b.sin + dz * b.cos;
      if (Math.abs(lx) <= hw && Math.abs(lz) <= hl) return true;
    }
    return false;
  }

  /** 每 tick 刷新所有单位的草丛状态（同草敌人互相显形） */
  _updateBrush() {
    for (const u of this.units) {
      if (!u.alive || u.static) continue;
      u.inBrush = this.isInBrush(u.pos.x, u.pos.z);
    }
    // 同草丛内的敌方单位互相显形
    for (const u of this.units) {
      if (!u.alive || !u.inBrush || u.static) continue;
      for (const e of this.units) {
        if (!e.alive || !e.inBrush || e.team === u.team || e.static) continue;
        if (dist2(u.pos.x, u.pos.z, e.pos.x, e.pos.z) <= BRUSH_CFG.SAME_BRUSH_R * BRUSH_CFG.SAME_BRUSH_R) {
          u.revealUntil = Math.max(u.revealUntil, this.time + 0.25);
          break;
        }
      }
    }
  }

  /** 无敌建筑不可作为目标（塔顺序无敌/水晶保护）：修复(p3)，避免对免疫目标空耗输出 */
  _invulnerableStructure(u) {
    if (u.kind === 'tower') return this.isTowerInvuln(u);
    if (u.kind === 'crystal') return this.isCrystalInvuln(u);
    return false;
  }

  /** 范围搜索敌人 */
  enemiesInRange(x, z, r, byTeam, opts = {}) {
    return this.unitsInCircle(x, z, r, u => {
      if (!this.targetable(u, byTeam)) return false;
      if (!opts.structures && (u.kind === 'tower' || u.kind === 'crystal')) return false;
      if (this._invulnerableStructure(u)) return false;
      if (opts.heroesOnly && u.kind !== 'hero') return false;
      return true;
    });
  }

  /** 最近敌人 */
  nearestEnemy(unit, maxR, opts = {}) {
    let best = null, bestD2 = maxR * maxR;
    for (const u of this.units) {
      if (!this.targetable(u, unit.team)) continue;
      if (!opts.structures && (u.kind === 'tower' || u.kind === 'crystal')) continue;
      if (this._invulnerableStructure(u)) continue;
      if (opts.heroesOnly && u.kind !== 'hero') continue;
      const d2 = dist2(unit.pos.x, unit.pos.z, u.pos.x, u.pos.z);
      if (d2 < bestD2) { bestD2 = d2; best = u; }
    }
    return best;
  }

  /** 自动瞄准最优目标：范围内血量最低敌方英雄优先，否则最近敌人 */
  autoTarget(unit, range) {
    const foes = this.enemiesInRange(unit.pos.x, unit.pos.z, range + 1.5, unit.team);
    if (!foes.length) return null;
    let hero = null;
    for (const f of foes) {
      if (f.kind === 'hero' && (!hero || f.hp < hero.hp)) hero = f;
    }
    if (hero) return hero;
    let best = null, bd = Infinity;
    for (const f of foes) {
      const d2 = dist2(unit.pos.x, unit.pos.z, f.pos.x, f.pos.z);
      if (d2 < bd) { bd = d2; best = f; }
    }
    return best;
  }

  // ---------------- 伤害结算 ----------------
  /**
   * @param {Unit|null} source 伤害来源
   * @param {Unit} target
   * @param {number} amount 面板伤害
   * @param {object} opts { type:'ad'|'ap'|'true', isBasic, isMarkProc }
   */
  dealDamage(source, target, amount, opts = {}) {
    if (!target.alive || this.over) return 0;
    // 建筑无敌机制
    if (target.kind === 'tower' && this.isTowerInvuln(target)) {
      if (source && source.isPlayer) this.floatText(target, '免疫', 'immune');
      return 0;
    }
    if (target.kind === 'crystal' && this.isCrystalInvuln(target)) {
      if (source && source.isPlayer) this.floatText(target, '免疫', 'immune');
      return 0;
    }

    const type = opts.type || 'ad';
    let dmg = amount;
    // 炮车/超级兵对塔 2 倍伤害
    if (source && (source.minionType === 'cannon' || source.minionType === 'super') &&
        target.kind === 'tower') dmg *= source.cfg.towerMult;
    // 终局(p5)：残存防御塔为水晶供能——每座存活塔 -8% 受伤（下限 30%，塔先水晶后）
    // （修复：p4 固定 ×0.1 时解防水晶伤害被回血抵消，比赛无法终结）
    if (target.kind === 'crystal') {
      let guard = 0;
      for (const t of this.towerUnits) if (t.team === target.team && t.alive) guard++;
      dmg *= Math.max(CRYSTAL_CFG.TOWER_GUARD_MIN, 1 - guard * CRYSTAL_CFG.TOWER_GUARD_PER);
    }
    // 抗性减免（真实伤害不减）
    if (type !== 'true') {
      const resist = type === 'ap' ? target.mres : target.armor;
      dmg = dmg * 100 / (100 + resist);
    }
    dmg = Math.max(1, Math.round(dmg));

    // 兰陵王标记触发：3s 内再受兰陵王伤害触发额外伤害
    if (source && !opts.isMarkProc) {
      const mark = target.getBuff('mark');
      if (mark && mark.source === source) {
        target.removeBuff(mark);
        this.dealDamage(source, target, 180 + 0.5 * source.ad, { type: 'ad', isMarkProc: true });
      }
    }

    // 护盾吸收
    const shield = target.getBuff('shield');
    if (shield) {
      const absorbed = Math.min(shield.value, dmg);
      shield.value -= absorbed;
      dmg -= absorbed;
      if (shield.value <= 0) target.removeBuff(shield);
    }

    target.hp -= dmg;
    target.lastCombatT = this.time;
    target.lastAttacker = source;
    if (source) {
      source.lastCombatT = this.time;
      if (source.kind === 'hero' && target.kind === 'hero') {
        source.lastHitHeroT = this.time;
        // 助攻记录：8s 内对受害者造成过伤害的英雄（击杀结算时授予）
        target._dmgBy = target._dmgBy || {};
        target._dmgBy[source.id] = { u: source, t: this.time };
      }
      // 攻击破隐
      if (source.stealth && opts.isBasic) source.removeBuff(source.getBuff('stealth'));
    }
    // 受击面向：未交战小兵被攻击时转身反击攻击者（塔除外，避免小兵越塔送死）
    if (target.kind === 'minion' && source && source.kind !== 'tower' && source.alive) {
      if (!target.target || !target.target.alive) {
        target.target = source;
        target.face(source.pos.x, source.pos.z);
      }
    }
    // 回城被伤害打断
    if (target.channel && target.channel.type === 'recall') {
      target.channel = null;
      target.cooldowns.recall = RECALL_CFG.CD;
      this.floatText(target, '回城被打断', 'info');
    }
    // 隐身单位被打显形
    if (target.stealth) target.removeBuff(target.getBuff('stealth'));

    // 飘字与受击闪红（控制数量：仅玩家相关）
    if ((source && source.isPlayer) || target.isPlayer) {
      this.floatText(target, String(dmg), target.isPlayer ? 'dmgTaken' : (type === 'ap' ? 'dmgAp' : 'dmg'));
    }
    this.vfx.flashHit(target.model, target.radius);

    if (target.hp <= 0) {
      target.hp = 0;
      this.onUnitDied(target, source);
    }
    return dmg;
  }

  /** 头顶飘字（DOM 对象池在 hud） */
  floatText(unit, text, cls) {
    this.events.emit('floatText', { x: unit.pos.x, y: unit.barHeight * 0.8, z: unit.pos.z, text, cls });
  }

  // ---------------- 死亡结算 ----------------
  onUnitDied(unit, killer) {
    if (!unit.alive) return;
    unit.alive = false;
    unit.channel = null;
    unit.target = null;
    this.stats.deaths++;
    // 击杀归属（召唤物归主人）
    const credit = killer && killer.kind === 'summon' ? killer.owner : killer;

    if (unit.kind === 'minion') {
      // 金币给击杀者；经验 14 半径共享
      if (credit && credit.kind === 'hero') {
        this.giveGold(credit, unit.cfg.gold);
        credit.creeps++;   // 补刀数（结算界面展示）
      }
      if (credit) {
        const sharers = this.unitsInCircle(unit.pos.x, unit.pos.z, ECON.EXP_SHARE_R,
          u => u.kind === 'hero' && u.team === credit.team);
        for (const h of sharers) this.giveExp(h, unit.cfg.exp);
      }
      unit._purge = true;
      this.vfx.dissolve(unit.model);
      this.events.emit('unitDied', unit);
    } else if (unit.kind === 'summon') {
      unit._purge = true;
      this.vfx.dissolve(unit.model);
      this.events.emit('unitDied', unit);
    } else if (unit.kind === 'monster') {
      // 野怪死亡：金币经验给击杀者；BUFF/龙效果；营地刷新计时
      const camp = unit.camp;
      if (camp) {
        const i = camp.units.indexOf(unit);
        if (i >= 0) camp.units.splice(i, 1);
        if (!camp.units.some(u => u.alive)) camp.respawnAt = this.time + camp.respawn;   // 清空后刷新
      }
      if (credit && credit.kind === 'hero') {
        const mtype = unit.monsterType;
        if (mtype === 'tyrant') {
          // 暴君：全队 +150 金 + 经验
          this.objectiveKills.tyrant[credit.team]++;
          for (const h of this.units) {
            if (h.kind === 'hero' && h.team === credit.team) {
              this.giveGold(h, JUNGLE.TYRANT_TEAM_GOLD);
              this.giveExp(h, JUNGLE.TYRANT_TEAM_EXP);
            }
          }
          console.debug(`[野区] ${credit.team} 击杀暴君（全队+${JUNGLE.TYRANT_TEAM_GOLD}金）`);
        } else if (mtype === 'overlord') {
          // 主宰：击杀方三路下一波兵强化为主宰先锋
          this.objectiveKills.overlord[credit.team]++;
          this.spawner.overlordWave[credit.team] = true;
          console.debug(`[野区] ${credit.team} 击杀主宰（下波兵强化 ×${JUNGLE.OVERLORD_WAVE_MULT}）`);
        } else {
          this.giveGold(credit, unit.cfg.gold);
          // 红/蓝 BUFF 授予击杀者
          if (mtype === 'redBuff') {
            credit.addBuff({ type: 'redBuff', dur: BUFFS.RED_DUR, group: 'jungleBuff' });
            this.floatText(credit, '红BUFF', 'level');
            console.debug(`[野区] ${credit.name}(${credit.team}) 获得红BUFF`);
          } else if (mtype === 'blueBuff') {
            credit.addBuff({ type: 'blueBuff', dur: BUFFS.BLUE_DUR, group: 'jungleBuff' });
            this.floatText(credit, '蓝BUFF', 'level');
            console.debug(`[野区] ${credit.name}(${credit.team}) 获得蓝BUFF`);
          }
        }
        // 经验 14 半径共享
        const sharers = this.unitsInCircle(unit.pos.x, unit.pos.z, ECON.EXP_SHARE_R,
          u => u.kind === 'hero' && u.team === credit.team);
        for (const h of sharers) this.giveExp(h, unit.cfg.exp);
      }
      unit._purge = true;
      this.vfx.dissolve(unit.model);
      this.events.emit('unitDied', unit);
      this.events.emit('monsterKilled', { monster: unit, type: unit.monsterType, byTeam: credit ? credit.team : null });
    } else if (unit.kind === 'hero') {
      unit.deaths++;
      unit.respawnAt = this.time + RESPAWN.BASE + RESPAWN.PER_LEVEL * unit.level;
      if (credit && credit.kind === 'hero') {
        credit.kills++;
        this.giveGold(credit, ECON.HERO_KILL_GOLD);
        this.giveExp(credit, ECON.HERO_KILL_EXP);
        // ---- 击杀播报：第一滴血/多杀（8s 窗口） ----
        if (!this._firstBlood) {
          this._firstBlood = true;
          this._announce('firstBlood', { unit: credit, victim: unit });
        }
        credit._killTimes = (credit._killTimes || []).filter(t => this.time - t <= ANNOUNCE.MULTI_WINDOW);
        credit._killTimes.push(this.time);
        const multiName = { 2: 'doubleKill', 3: 'tripleKill', 4: 'quadraKill', 5: 'pentaKill' }[credit._killTimes.length];
        if (multiName) this._announce(multiName, { unit: credit, victim: unit, count: credit._killTimes.length });
        // ---- 超神：不死连杀 5 人 ----
        credit._streak = (credit._streak || 0) + 1;
        if (credit._streak === 5) this._announce('godlike', { unit: credit });
      }
      unit._streak = 0;   // 阵亡清零连杀
      // ---- 助攻：8s 内对受害者造成过伤害的其他存活英雄 +80 金 ----
      if (unit._dmgBy) {
        for (const id of Object.keys(unit._dmgBy)) {
          const r = unit._dmgBy[id];
          if (r.u !== credit && r.u.alive && r.u.kind === 'hero' &&
              r.u.team !== unit.team && this.time - r.t <= 8) {
            r.u.assists++;
            this.giveGold(r.u, ECON.ASSIST_GOLD);
          }
        }
        unit._dmgBy = null;
      }
      this.score[credit ? credit.team : (unit.team === TEAM.BLUE ? TEAM.RED : TEAM.BLUE)]++;
      // ---- 团灭判定：对方 5 人 10s 内全部阵亡 ----
      this._teamDeaths[unit.team].push(this.time);
      const recentDeaths = this._teamDeaths[unit.team].filter(t => this.time - t <= ANNOUNCE.ACE_WINDOW).length;
      const allDead = this.units.every(h => h.kind !== 'hero' || h.team !== unit.team || !h.alive);
      if (allDead && recentDeaths >= 5 && this.time - this._lastAce > ANNOUNCE.ACE_CD) {
        this._lastAce = this.time;
        this._announce('ace', { team: unit.team === TEAM.BLUE ? TEAM.RED : TEAM.BLUE });
      }
      this.vfx.dissolve(unit.model);
      this.events.emit('unitDied', unit);
      this.events.emit('heroDown', { unit, killer: credit });
    } else if (unit.kind === 'tower') {
      this.stats.towersDown++;
      this.towersLost[unit.team]++;
      console.debug(`[播报] ${unit.team === TEAM.BLUE ? '蓝方' : '红方'}${unit.name}被摧毁`);
      // 破塔全队 +200 金
      if (credit) {
        for (const h of this.units) {
          if (h.kind === 'hero' && h.team === credit.team) this.giveGold(h, ECON.TOWER_TEAM_GOLD);
        }
      }
      this.vfx.collapse(unit.model);
      this.vfx.burst(unit.pos.x, 6, unit.pos.z, { color: 0xffaa50, count: 40, speed: 9, life: 0.9 });
      this.events.emit('unitDied', unit);
      this.events.emit('towerDown', { tower: unit, byTeam: credit ? credit.team : null });
    } else if (unit.kind === 'crystal') {
      this.vfx.burst(unit.pos.x, 6, unit.pos.z, { color: 0xffffff, count: 80, speed: 14, life: 1.4 });
      this.vfx.burst(unit.pos.x, 3, unit.pos.z, { color: 0xff6040, count: 60, speed: 10, life: 1.2 });
      if (unit.model) unit.model.visible = false;
      if (unit._coreLight) unit._coreLight.intensity = 0;
      // 终局(p4)：记录水晶击杀者（验收汇报用：minion/hero/…）
      this.crystalKiller = credit
        ? { kind: credit.kind, minionType: credit.minionType || null, name: credit.name || null }
        : null;
      this.events.emit('unitDied', unit);
      this.endGame(credit ? credit.team : (unit.team === TEAM.BLUE ? TEAM.RED : TEAM.BLUE));
    }
  }

  /** 胜利判定 */
  endGame(winner) {
    if (this.over) return;
    this.over = true;
    this.winner = winner;
    console.debug(`[播报] 比赛结束 ${winner} 获胜，用时 ${Math.floor(this.time / 60)}:${String(Math.floor(this.time % 60)).padStart(2, '0')}`);
    this.events.emit('victory', { winner });
  }

  /**
   * 击杀/关键事件播报（阶段3 console.debug 输出；阶段4 UI 横幅订阅这些事件）
   * 事件：firstBlood/doubleKill/tripleKill/quadraKill/pentaKill/ace（+ 聚合 'announce'）
   */
  _announce(type, data = {}) {
    const name = ANNOUNCE.NAMES[type] || type;
    const who = data.unit ? `${data.unit.name}(${data.unit.team})` : (data.team || '');
    console.debug(`[播报] ${name} ${who} t=${this.time.toFixed(1)}`);
    this.events.emit(type, data);
    this.events.emit('announce', { type, ...data });
  }

  /**
   * 召唤师技能：恢复（8s 回 25% 血，CD 90s）
   * @param {Unit} u @returns {boolean}
   */
  castHeal(u) {
    if (!u || !u.alive || u.cooldowns.heal > 0) return false;
    u.cooldowns.heal = HEAL_CFG.CD;
    u.addBuff({ type: 'healRegen', dur: HEAL_CFG.DUR, group: 'summonerHeal' });
    this.floatText(u, '恢复', 'level');
    this.events.emit('summonerHeal', { unit: u });
    return true;
  }

  // ---------------- 经济 / 经验 ----------------
  giveGold(hero, amount) {
    hero.gold += amount;
    if (hero.isPlayer) this.floatText(hero, `+${amount}`, 'gold');
  }

  giveExp(hero, amount) {
    if (hero.level >= MAX_LEVEL) return;
    hero.exp += amount;
    while (hero.level < MAX_LEVEL && hero.exp >= EXP_TABLE[hero.level - 1]) {
      hero.exp -= EXP_TABLE[hero.level - 1];
      this.levelUp(hero);
    }
  }

  /** 升级：属性成长 + 自动加点（4/8/12 大招，其余 S1>S2 交替） */
  levelUp(hero) {
    hero.level++;
    const g = HEROES[hero.heroId].growth;
    hero.maxHp += g.hp; hero.hp += g.hp;
    hero.maxMp += g.mp; hero.mp += g.mp;
    hero.ad += g.ad; hero.ap += g.ap;
    hero.armor += g.armor; hero.mres += g.mres;
    // 自动加点
    const slot = SKILL_ORDER[hero.level - 2];
    if (slot) hero.skillLevels[slot]++;
    this.vfx.levelUp(hero.pos.x, hero.pos.z);
    if (hero.isPlayer) this.floatText(hero, `升级 Lv.${hero.level}`, 'level');
    this.events.emit('levelUp', { unit: hero });
  }

  // ---------------- 生成接口 ----------------
  /** 生成英雄（玩家或 AI） */
  spawnHero(heroId, team, x, z) {
    const cfg = HEROES[heroId];
    const model = createHeroModel(heroId, team);
    const u = new Unit({
      team, x, z, hp: cfg.hp, mp: cfg.mp, ad: cfg.ad, ap: cfg.ap,
      armor: cfg.armor, mres: cfg.mres, speed: cfg.speed, aspeed: cfg.aspeed,
      range: cfg.range, radius: HERO.RADIUS, name: cfg.name, model,
    });
    u.kind = 'hero';
    u.heroId = heroId;
    u.cfg = cfg;
    u.skillLevels.s1 = 1;   // 1 级自带 S1
    u.yaw = team === TEAM.BLUE ? Math.atan2(1, 1) : Math.atan2(-1, -1);
    u.homePos = team === TEAM.BLUE ? MAP.BLUE_FOUNTAIN : MAP.RED_FOUNTAIN;
    u.shop = new Shop(u);   // 装备栏（AI 自动购买；玩家 UI 在阶段4）
    u.items = u.shop.slots;
    u.syncModel();
    return this._register(u);
  }

  /**
   * 装配 9 个 AI 英雄（蓝方 4 队友 + 红方 5 敌人）
   * 蓝方 AI 从 5 英雄池中去掉玩家英雄后分配（不与玩家重复）；红方 5 人满编
   * （英雄池仅 5 个，红蓝双方跨队无法避免重复，保证队内不重复）
   * 分路：每方 1上1中2下1野；玩家默认占下路位，蓝方 AI 补上/中/下2/野
   * @param {string} playerHeroId
   */
  setupAI(playerHeroId) {
    const pool = Object.keys(HEROES);
    const mk = (heroId, team, role) => {
      const f = team === TEAM.BLUE ? MAP.BLUE_FOUNTAIN : MAP.RED_FOUNTAIN;
      const u = this.spawnHero(heroId, team, f.x, f.z);
      u.isAI = true;
      this.aiHeroes.push(new HeroAI(u, this, role));
      return u;
    };
    const assign = (heroIds, roles) => {
      // 打野位优先给适合的英雄
      const jungle = JUNGLE_PREF.find(h => heroIds.includes(h)) || heroIds[0];
      const rest = heroIds.filter(h => h !== jungle);
      mk(jungle, roles.team, 'jungle');
      roles.lanes.forEach((role, i) => { if (rest[i]) mk(rest[i], roles.team, role); });
    };
    assign(pool.filter(h => h !== playerHeroId), { team: TEAM.BLUE, lanes: ['top', 'mid', 'bot2'] });
    assign(pool.slice(), { team: TEAM.RED, lanes: ['top', 'mid', 'bot', 'bot2'] });
  }

  /** 生成野怪 @param type 'redBuff'|'blueBuff'|'small'|'tyrant'|'overlord' */
  spawnMonster(type, x, z, camp) {
    const cfg = MONSTERS[type];
    const model = createMonsterModel(type);
    const u = new Unit({
      team: 'neutral', x, z, hp: cfg.hp, mp: 0,
      ad: cfg.ad, armor: cfg.armor, mres: cfg.mres,
      speed: cfg.speed, aspeed: cfg.aspeed, range: cfg.range,
      radius: cfg.radius, name: cfg.name, model,
    });
    u.kind = 'monster';
    u.monsterType = type;
    u.cfg = cfg;
    u.camp = camp || null;
    u.returning = false;
    u.barHeight = type === 'tyrant' ? 5.4 : (type === 'overlord' ? 6.6 : (type === 'small' ? 2.2 : 3.8));
    u.yaw = Math.atan2(-x, -z);   // 面向地图中心
    u.syncModel();
    return this._register(u);
  }

  /** 生成玩家英雄（URL ?hero= 选择，默认 arthur） */
  spawnPlayer(heroId = 'arthur') {
    if (!HEROES[heroId]) heroId = 'arthur';
    const u = this.spawnHero(heroId, TEAM.BLUE, SPAWN.x, SPAWN.z);
    u.isPlayer = true;
    this.player = u;
    return u;
  }

  /** 生成小兵 @param team @param lane 'mid'|'top'|'bot' @param type 'melee'|'mage'|'cannon' */
  spawnMinion(team, lane, type, ox = 0, oz = 0) {
    const cfg = MINIONS[type];
    const pts = MAP.LANES[lane];
    const path = (team === TEAM.BLUE ? pts : pts.slice().reverse()).map(([x, z]) => ({ x, z }));
    const start = path[0];
    const model = createMinionModel(type, team);
    const u = new Unit({
      team, x: start.x + ox, z: start.z + oz, hp: cfg.hp, mp: 0,
      ad: cfg.ad, armor: cfg.armor, mres: cfg.mres,
      speed: cfg.speed, aspeed: cfg.aspeed, range: cfg.range,
      radius: cfg.radius, name: '', model,
    });
    u.kind = 'minion';
    u.minionType = type;
    u.cfg = cfg;
    u.lane = lane;
    u.path = path;
    u.wpIndex = 1;
    // 修复(p3)：横向随机偏移（出生固定）+ 卡死追踪，避免同轨叠罗汉与永久堵塞
    u.laneOffset = (Math.random() * 2 - 1) * MINION_LANE_OFFSET;
    u._stuckT = 0;
    u._stuckPos = { x: u.pos.x, z: u.pos.z };
    u.barHeight = type === 'cannon' ? 3.4 : (type === 'super' ? 3.6 : 2.5);
    // 终局(p4)：12:00 后小兵属性随时间成长（每分钟 +8% HP/AD）
    if (this.time > MINION_GROWTH.START) {
      const g = 1 + MINION_GROWTH.RATE * (this.time - MINION_GROWTH.START) / 60;
      u.maxHp = u.hp = Math.round(u.maxHp * g);
      u.ad = Math.round(u.ad * g);
    }
    // 终局(p4)：超级兵优先进攻建筑（塔/水晶）
    u.preferStructure = type === 'super';
    u.yaw = team === TEAM.BLUE ? Math.atan2(1, 1) : Math.atan2(-1, -1);
    u.syncModel();
    return this._register(u);
  }

  /** 兰陵王分身（不可选中的召唤物） */
  spawnClone(owner) {
    const model = createHeroModel(owner.heroId, owner.team);
    _setModelOpacity({ model }, 0.6);
    const u = new Unit({
      team: owner.team, x: owner.pos.x + 1.2, z: owner.pos.z + 0.5,
      hp: 1, mp: 0, speed: owner.baseSpeed * 1.1, aspeed: 1.2,
      range: 2.6, radius: 0.8, name: '分身', model,
    });
    u.kind = 'summon';
    u.untargetable = true;
    u.noBar = true;
    u.owner = owner;
    u.attacksLeft = 3;
    u.life = 6;
    u.yaw = owner.yaw;
    u.syncModel();
    return this._register(u);
  }

  /** 英雄重生 */
  respawnHero(u) {
    u.alive = true;
    u.hp = u.maxHp;
    u.mp = u.maxMp;
    u.respawnAt = null;
    u.clearBuffs();
    u.channel = null;
    u.attackTimer = 0;
    u.revealUntil = 0;
    u.inBrush = false;
    u.pos.set(u.homePos.x, 0, u.homePos.z);
    if (u.model) {
      if (!u.model.parent) this.scene.add(u.model);   // 消散时可能被移出场景
      u.model.visible = true;
      u.model.rotation.z = 0;
      if (u.model.userData._origScale !== undefined) u.model.scale.setScalar(u.model.userData._origScale);
      // 恢复消散/隐身时改过的材质
      u.model.traverse(o => {
        if (o.isMesh && o.material && o.material.userData._orig) {
          o.material.transparent = o.material.userData._orig.transparent;
          o.material.opacity = o.material.userData._orig.opacity;
        }
      });
    }
    u.syncModel();
    this.events.emit('unitSpawned', u);   // 重新挂血条
  }

  // ---------------- 无敌机制 ----------------
  /** 塔顺序无敌：同路前塔未破则后塔无敌 */
  isTowerInvuln(tower) {
    if (tower.tier <= 1) return false;
    return this.towerUnits.some(t =>
      t.team === tower.team && t.lane === tower.lane && t.tier < tower.tier && t.alive);
  }

  /** 水晶无敌：本方任意一路 3 塔全破，或存活塔 ≤3（≥6 塔被破）→ 解除无敌
   *  修复(终局僵持)：原规则仅"一路全破"，AI 分散拆塔时三路各剩 1-2 座会永久无敌，
   *  实测 22 分钟无法分出胜负。增加存活塔总数条件保证比赛必然可终结。 */
  isCrystalInvuln(crystal) {
    let aliveTotal = 0;
    for (const t of this.towerUnits) {
      if (t.team === crystal.team && t.alive) aliveTotal++;
    }
    if (aliveTotal <= 3) return false;
    for (const lane of ['mid', 'top', 'bot']) {
      if (!this.towerUnits.some(t => t.team === crystal.team && t.lane === lane && t.alive)) {
        return false;   // 该路 3 塔已全破
      }
    }
    return true;
  }

  // ---------------- 玩家操作 ----------------
  queueAction(name) { this._actionQueue.push(name); }
  tryCastSkill(slot) { return this.skills.cast(this.player, slot, { auto: true }); }

  tryRecall() {
    const p = this.player;
    if (!p || !p.alive || p.channel || p.isLocked()) return false;
    if (p.cooldowns.recall > 0) return false;
    p.channel = { type: 'recall', t: 0, dur: RECALL_CFG.CHANNEL };
    this.floatText(p, '回城引导中…', 'info');
    this.events.emit('recallStart', { unit: p });
    return true;
  }

  /** 召唤师技能：闪现（向移动方向/朝向位移 8，CD 120s，仅玩家） */
  tryFlash() {
    const p = this.player;
    if (!p || !p.alive || p.channel || p.isLocked()) return false;
    if (p.cooldowns.flash > 0) return false;
    p.cooldowns.flash = FLASH_CFG.CD;
    const dir = p._lastMove || { x: Math.sin(p.yaw), z: Math.cos(p.yaw) };
    this.vfx.burst(p.pos.x, 1.2, p.pos.z, { color: 0xc0e8ff, count: 16, speed: 5, life: 0.35 });
    p.pos.x = clamp(p.pos.x + dir.x * FLASH_CFG.DIST, -MAP.PLAY_BOUND, MAP.PLAY_BOUND);
    p.pos.z = clamp(p.pos.z + dir.z * FLASH_CFG.DIST, -MAP.PLAY_BOUND, MAP.PLAY_BOUND);
    p.syncModel();
    this.vfx.burst(p.pos.x, 1.2, p.pos.z, { color: 0xc0e8ff, count: 20, speed: 6, life: 0.4 });
    this.floatText(p, '闪现', 'skill');
    this.events.emit('flashCast', { unit: p });
    return true;
  }

  /** 普攻（含强化普攻/三连发/破隐） */
  performAttack(attacker, target) {
    if (!attacker.alive || !target || !target.alive) return false;
    if (attacker.attackTimer > 0 || attacker.isLocked()) return false;
    attacker.attackTimer = 1 / attacker.aspeed;
    attacker.face(target.pos.x, target.pos.z);
    attacker.lastCombatT = this.time;
    if (attacker.stealth) attacker.removeBuff(attacker.getBuff('stealth'));   // 攻击破隐
    attacker.revealUntil = this.time + BRUSH_CFG.REVEAL_ON_ATTACK;            // 草丛破隐 1s

    // 强化普攻（亚瑟 S1）
    let bonus = 0, silence = 0;
    const emp = attacker.getBuff('empower');
    if (emp) {
      bonus = emp.bonus; silence = emp.silence || 0;
      attacker.removeBuff(emp);
    }
    const multi = attacker.getBuff('multishot');   // 后羿 S1 三连发
    // 暴击判定（200%）
    let adBase = attacker.ad;
    if (attacker.crit > 0 && Math.random() < attacker.crit) adBase *= CRIT.MULT;

    const applyHit = (dmg, sil) => {
      this.dealDamage(attacker, target, dmg, { type: 'ad', isBasic: true });
      if (sil && target.alive) target.addBuff({ type: 'silence', dur: sil });
      // 红BUFF：普攻附带灼烧（40+0.2AD）+减速 15%
      if (target.alive && attacker.hasBuff('redBuff') &&
          target.kind !== 'tower' && target.kind !== 'crystal') {
        const burn = (BUFFS.RED_BURN_BASE + BUFFS.RED_BURN_AD * attacker.ad) / BUFFS.RED_BURN_TICKS;
        target.addBuff({ type: 'dot', dur: 2, interval: 1, tickT: 0.01, amount: burn, source: attacker, dmgType: 'ad', group: 'redBurn' });
        target.addBuff({ type: 'slow', pct: BUFFS.RED_SLOW, dur: BUFFS.RED_SLOW_DUR, group: 'redSlow' });
      }
    };

    if (attacker.range <= 3) {
      // 近战即时命中
      applyHit(adBase + bonus, silence);
      this.vfx.burst(target.pos.x, 1.2, target.pos.z, { color: 0xfff0c0, count: 6, speed: 4, life: 0.3 });
      this.events.emit('basicAttack', { attacker, target });
    } else {
      // 远程弹道（追踪）
      const shots = multi ? 3 : 1;
      for (let i = 0; i < shots; i++) {
        const dmg = adBase * (multi ? 0.6 : 1) + (i === 0 ? bonus : 0);
        this.skills.spawnProjectile({
          source: attacker, target, homing: true,
          speed: 22, radius: 0.9,
          color: attacker.kind === 'minion'
            ? (attacker.minionType === 'cannon' ? 0xffe0a0 : (attacker.team === TEAM.BLUE ? 0x66aaff : 0xff7a66))
            : (attacker.heroId === 'daji' ? 0xff7ad0 : 0xffa040),
          size: attacker.minionType === 'cannon' ? 0.5 : 0.32,
          delay: i * 0.09,
          onHit: () => applyHit(dmg, i === 0 ? silence : 0),
        });
      }
      this.events.emit('basicAttack', { attacker, target });
    }
    return true;
  }

  // ---------------- demo 模式 ----------------
  /** ?demo=1：脚本控制玩家——跟中路兵线推进/自动普攻/CD 好放技能/残血撤退 */
  enableDemo() { this._demo = { retreat: false }; }

  _demoControl(dt) {
    const p = this.player;
    const out = { move: null, attack: false };
    if (!p.alive || this.over) return out;

    const hpPct = p.hp / p.maxHp;
    const home = p.homePos;
    const dHome = dist(p.pos.x, p.pos.z, home.x, home.z);

    // 残血撤退 / 泉水恢复
    if (this._demo.retreat) {
      if (dHome < FOUNTAIN.R && hpPct > 0.9) this._demo.retreat = false;
      else { out.move = { x: home.x - p.pos.x, z: home.z - p.pos.z }; return out; }
    } else if (hpPct < 0.35) {
      this._demo.retreat = true;
      out.move = { x: home.x - p.pos.x, z: home.z - p.pos.z };
      return out;
    }

    // 目标：12 内最近敌人（含建筑）
    const target = this.nearestEnemy(p, 12, { structures: true });

    // CD 好了就放技能
    this._demoSkills(p, target);

    if (target && target.kind === 'tower') {
      // 无己方小兵在塔附近时不越塔
      const covered = this.unitsInCircle(target.pos.x, target.pos.z, 10,
        u => u.team === p.team && u.kind === 'minion').length > 0;
      const d = dist(p.pos.x, p.pos.z, target.pos.x, target.pos.z);
      if (!covered) {
        if (d < TOWER_CFG.RANGE + 2) out.move = { x: p.pos.x - target.pos.x, z: p.pos.z - target.pos.z };
        return out;
      }
    }

    if (target) {
      const d = dist(p.pos.x, p.pos.z, target.pos.x, target.pos.z);
      const reach = p.range + target.radius;
      if (d <= reach + 0.2) {
        out.attack = true;
      } else if (p.range > 5) {
        // 远程保持射程：远了靠近，太近拉开
        if (d > p.range * 0.9) out.move = { x: target.pos.x - p.pos.x, z: target.pos.z - p.pos.z };
        else if (d < p.range * 0.5) out.move = { x: p.pos.x - target.pos.x, z: p.pos.z - target.pos.z };
      } else {
        out.move = { x: target.pos.x - p.pos.x, z: target.pos.z - p.pos.z };
      }
      return out;
    }

    // 无敌人：跟随己方中路最前小兵，否则沿中路推进
    let anchor = null, bestProg = -Infinity;
    for (const u of this.units) {
      if (u.alive && u.team === p.team && u.kind === 'minion' && u.lane === 'mid') {
        const prog = u.pos.x + u.pos.z;   // 蓝方推进方向 (+,+)
        if (prog > bestProg) { bestProg = prog; anchor = u; }
      }
    }
    let ax, az;
    if (anchor) { ax = anchor.pos.x; az = anchor.pos.z; }
    else {
      // 找中路下一个路径点
      const pts = MAP.LANES.mid;
      ax = 70; az = 70;
      for (const [x, z] of pts) {
        if (x + z > p.pos.x + p.pos.z + 2) { ax = x; az = z; break; }
      }
    }
    if (dist(p.pos.x, p.pos.z, ax, az) > 5) out.move = { x: ax - p.pos.x, z: az - p.pos.z };
    return out;
  }

  /** demo 自动放技能：按技能形态决定时机 */
  _demoSkills(p, target) {
    const defs = SKILLS[p.heroId];
    for (const slot of ['s1', 's2', 'ult']) {
      if (!this.skills.castable(p, slot)) continue;
      const def = defs[slot];
      let want = false;
      if (def.aim === 'self') {
        // 自身增益：附近有敌即用
        want = !!this.nearestEnemy(p, 8);
      } else if (def.aim === 'around') {
        want = this.enemiesInRange(p.pos.x, p.pos.z, (def.radius || 4) + 0.5, p.team).length > 0;
      } else {
        // target/line/area/dash：有优选目标且在射程内
        want = !!target && dist(p.pos.x, p.pos.z, target.pos.x, target.pos.z) <= (def.range || 8);
      }
      if (want) this.skills.cast(p, slot, { auto: true });
    }
  }

  // ---------------- 主逻辑更新 ----------------
  /**
   * @param {number} dt 1/30
   * @param {{x:number,z:number}} moveVec 输入移动向量
   */
  update(dt, moveVec) {
    this.time += dt;
    if (this.over) return;

    this.spawner.update(dt);
    this.skills.update(dt);
    this._updateBrush();

    // ---- 玩家控制 ----
    const p = this.player;
    if (p && p.alive) {
      let mv = moveVec;
      let wantAttack = this.attackHeld;
      if (this._demo) {
        const c = this._demoControl(dt);
        if (c.move) mv = c.move; else mv = { x: 0, z: 0 };
        wantAttack = wantAttack || c.attack;
      }
      // 回城引导
      if (p.channel) {
        p.channel.t += dt;
        if (p.channel.t >= p.channel.dur) {
          p.channel = null;
          p.pos.set(p.homePos.x, 0, p.homePos.z);
          this.floatText(p, '已回城', 'info');
        }
      } else if (!p.isLocked()) {
        const mag = Math.hypot(mv.x, mv.z);
        if (mag > 0.01) {
          p.moveStep(mv.x, mv.z, dt);
          p._lastMove = { x: mv.x / mag, z: mv.z / mag };   // 闪现方向用
        } else p.moving = false;
      } else {
        p.moving = false;
      }
      // 普攻：锁定最近敌人（按住空格/普攻键，或 demo 自动）
      if (wantAttack && !p.isLocked() && !p.channel) {
        const t = this.nearestEnemy(p, Math.max(p.range + 2.5, 8), { structures: true });
        if (t) {
          const d = dist(p.pos.x, p.pos.z, t.pos.x, t.pos.z);
          if (d <= p.range + t.radius + 0.2) {
            this.performAttack(p, t);
          } else if (!this._demo && this.attackHeld && d < 10 && !p.isLocked()) {
            p.moveStep(t.pos.x - p.pos.x, t.pos.z - p.pos.z, dt);   // 按住普攻自动追击
          }
        }
      }
      // 动作队列（技能/回城/恢复/闪现；{skill,dir} 为拖动瞄准施放）
      for (const a of this._actionQueue) {
        if (a === 'recall') this.tryRecall();
        else if (a === 'heal') this.castHeal(p);
        else if (a === 'flash') this.tryFlash();
        else if (a && typeof a === 'object' && a.skill) this.skills.cast(p, a.skill, { dir: a.dir });
        else this.tryCastSkill(a);
      }
      this._actionQueue.length = 0;
    } else {
      this._actionQueue.length = 0;
    }

    // ---- AI 英雄决策（阶段3） ----
    for (const ai of this.aiHeroes) ai.update(dt);

    // ---- 单位 AI / 计时 / 回复 ----
    for (const u of this.units) {
      if (!u.alive) continue;
      u.attackTimer = Math.max(0, u.attackTimer - dt);
      for (const k of Object.keys(u.cooldowns)) u.cooldowns[k] = Math.max(0, u.cooldowns[k] - dt);
      if (u.kind === 'minion') updateMinion(u, this, dt);
      else if (u.kind === 'tower') updateTower(u, this, dt);
      else if (u.kind === 'summon') this._updateSummon(u, dt);
      else if (u.kind === 'monster') updateMonster(u, this, dt);
      else if (u.kind === 'hero') {
        u.healMp(u.maxMp * 0.012 * dt);
        if (this.time - u.lastCombatT > 5) u.heal(u.maxHp * 0.006 * dt);
        // 蓝BUFF：额外回蓝 3%/s
        if (u.hasBuff('blueBuff')) u.healMp(u.maxMp * BUFFS.BLUE_MP_REGEN * dt);
        // 召唤师技能"恢复"：8s 回 25% 血
        if (u.hasBuff('healRegen')) u.heal(u.maxHp * HEAL_CFG.PCT / HEAL_CFG.DUR * dt);
        // 霸者重装：回血/s
        if (u.itemRegenPct > 0) u.heal(u.maxHp * u.itemRegenPct * dt);
        // 兰陵王被动：脱战 3s 后隐身
        if (u.heroId === 'lanlingwang' && !u.stealth && this.time - u.lastCombatT > 3) {
          u.addBuff({ type: 'stealth', dur: 9999 });
        }
      } else if (u.kind === 'crystal') {
        // 终局(p5)：水晶脱战 5s 后才回血——边打边回会抵消进攻方输出（基地拉锯无法终结）
        if (this.time - u.lastCombatT > 5) u.heal(u.maxHp * CRYSTAL_CFG.REGEN * dt);
      }
    }

    // ---- 泉水 ----
    for (const f of this.fountains) {
      const inside = this.unitsInCircle(f.x, f.z, FOUNTAIN.R, u => u.kind === 'hero' || u.kind === 'minion');
      for (const u of inside) {
        if (u.team === f.team) {
          u.heal(u.maxHp * FOUNTAIN.HEAL_PCT * dt);
          u.healMp(u.maxMp * FOUNTAIN.HEAL_PCT * dt);
        } else {
          this.dealDamage(null, u, FOUNTAIN.ENEMY_DPS * dt, { type: 'true' });
        }
      }
    }

    // ---- 单位间软推挤 ----
    this._separate();

    // ---- 静态碰撞 / 边界 / 模型同步 ----
    const B = MAP.PLAY_BOUND;
    for (const u of this.units) {
      if (!u.alive || u.static) continue;
      for (const c of this.colliders) {
        if (c.unit && !c.unit.alive) continue;   // 塔/水晶已毁，碰撞失效
        const dx = u.pos.x - c.x, dz = u.pos.z - c.z;
        const rr = c.r + u.radius;
        const d2 = dx * dx + dz * dz;
        if (d2 < rr * rr && d2 > 1e-6) {
          const d = Math.sqrt(d2);
          const push = (rr - d) / d;
          u.pos.x += dx * push;
          u.pos.z += dz * push;
        }
      }
      u.pos.x = clamp(u.pos.x, -B, B);
      u.pos.z = clamp(u.pos.z, -B, B);
      u.syncModel();
    }

    // 清理已消亡单位（小兵/召唤物）
    if (this.units.some(u => u._purge)) this.units = this.units.filter(u => !u._purge);
  }

  /** 兰陵王分身：追踪普攻 3 次（60%AD）后消散 */
  _updateSummon(u, dt) {
    u.life -= dt;
    if (u.life <= 0 || u.attacksLeft <= 0) { u.die(null); return; }
    const owner = u.owner;
    let t = u.target;
    if (!t || !t.alive || !this.targetable(t, u.team)) {
      t = this.nearestEnemy(u, 8) || this.nearestEnemy(owner, 8);
      u.target = t;
    }
    if (!t) { u.moving = false; return; }
    const d = dist(u.pos.x, u.pos.z, t.pos.x, t.pos.z);
    if (d <= u.range + t.radius) {
      u.moving = false;
      if (u.attackTimer <= 0) {
        u.attackTimer = 1 / u.aspeed;
        u.face(t.pos.x, t.pos.z);
        this.dealDamage(u, t, owner.ad * 0.6, { type: 'ad', isBasic: true });
        this.vfx.burst(t.pos.x, 1.2, t.pos.z, { color: 0x66d0c8, count: 5, speed: 4, life: 0.25 });
        u.attacksLeft--;
      }
    } else {
      u.moveStep(t.pos.x - u.pos.x, t.pos.z - u.pos.z, dt);
    }
  }

  /** 单位间软推挤（英雄/小兵/召唤物）；同队小兵弱化推挤，避免兵线自我拥堵 */
  _separate() {
    const list = this.units;
    for (let i = 0; i < list.length; i++) {
      const a = list[i];
      if (!a.alive || a.static) continue;
      for (let j = i + 1; j < list.length; j++) {
        const b = list[j];
        if (!b.alive || b.static) continue;
        // 修复(p3)：同队小兵之间软推挤（更小半径+更小力度）
        const soft = a.kind === 'minion' && b.kind === 'minion' && a.team === b.team;
        const dx = b.pos.x - a.pos.x, dz = b.pos.z - a.pos.z;
        const rr = (a.radius + b.radius) * (soft ? 0.55 : 0.8);
        const d2 = dx * dx + dz * dz;
        if (d2 < rr * rr && d2 > 1e-6) {
          const d = Math.sqrt(d2);
          const push = (rr - d) / d * (soft ? 0.2 : 0.5);
          a.pos.x -= dx * push; a.pos.z -= dz * push;
          b.pos.x += dx * push; b.pos.z += dz * push;
        }
      }
    }
  }

  /** 渲染帧：驱动模型动画 */
  animate(dt) {
    for (const u of this.units) {
      if (u.model && u.model.userData.update && u.alive) u.model.userData.update(dt, u.moving);
    }
  }
}
