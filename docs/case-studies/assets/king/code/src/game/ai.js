// ============================================================
// AI 系统
// 阶段2：小兵仇恨（8 范围发现敌人、优先最近、受击反击）+ 沿兵线行走
//        防御塔 AI（优先小兵 / 转火攻我方英雄的敌英雄 / 连击增伤 / 激光）
// 阶段3：野怪 AI（3.5 范围索敌 / leash 脱战回营满血）
//        英雄 AI 状态机 LANE / JUNGLE / TEAMFIGHT / PUSH / RETREAT / DEFEND / OBJECTIVE
//        （分路 1上1中2下1野、英雄优先权重1.5、技能CD好就用、大招只打英雄、
//          残血撤退回城、恢复术、回泉水买装、打龙时机判断）
// ============================================================
import {
  MINION_AGGRO_R, MINION_LEASH_R, TOWER_CFG, MAP, FOUNTAIN,
  JUNGLE, AI_CFG, RECALL_CFG, TEAM,
  MINION_WP_ARRIVE_R, MINION_STUCK_T, MINION_STUCK_DIST,
  MINION_SELF_DEFEND_R, BASE_GATES,
} from '../config.js';
import { dist, dist2 } from '../utils.js';
import { SKILLS } from './skills.js';

/** 小兵目标获取：8 范围内最近敌人（含建筑；隐身/草丛/召唤物/野怪/无敌建筑不可选） */
function acquireMinionTarget(m, state) {
  // 终局(p4)：超级兵优先进攻建筑（塔/水晶），不被小兵/英雄吸走仇恨
  if (m.preferStructure) {
    let bs = null, bsD2 = MINION_AGGRO_R * MINION_AGGRO_R;
    for (const u of state.units) {
      if (u.kind !== 'tower' && u.kind !== 'crystal') continue;
      if (!state.targetable(u, m.team)) continue;
      if (u.kind === 'tower' && state.isTowerInvuln(u)) continue;
      if (u.kind === 'crystal' && state.isCrystalInvuln(u)) continue;
      const d2s = dist2(m.pos.x, m.pos.z, u.pos.x, u.pos.z);
      if (d2s < bsD2) { bsD2 = d2s; bs = u; }
    }
    if (bs) return bs;
  }
  let best = null, bestD2 = MINION_AGGRO_R * MINION_AGGRO_R;
  for (const u of state.units) {
    if (u.kind === 'monster') continue;          // 小兵不理会野怪
    if (!state.targetable(u, m.team)) continue;
    // 修复(p3)：跳过无敌建筑（塔顺序无敌/水晶保护），不空耗兵线
    if (u.kind === 'tower' && state.isTowerInvuln(u)) continue;
    if (u.kind === 'crystal' && state.isCrystalInvuln(u)) continue;
    const d2 = dist2(m.pos.x, m.pos.z, u.pos.x, u.pos.z);
    if (d2 < bestD2) { bestD2 = d2; best = u; }
  }
  return best;
}

/** 小兵固定步长更新 */
export function updateMinion(m, state, dt) {
  if (!m.alive) return;

  // 终局(p4)：路径已走完且敌方水晶可攻击 → 水晶优先
  // （修复：原逻辑下基地内小兵被每波新刷的守军/回防英雄持续吸走仇恨，
  //   水晶回血 1%/s 抵消零星伤害 → 满血僵持。现仅 3.5 内有敌人时自卫反击）
  const ec = state.crystals[m.team === 'blue' ? 'red' : 'blue'];
  if (ec && ec.alive && !state.isCrystalInvuln(ec) && m.wpIndex >= m.path.length) {
    let threat = null, threatD2 = MINION_SELF_DEFEND_R * MINION_SELF_DEFEND_R;
    for (const u of state.units) {
      if (u.kind === 'tower' || u.kind === 'crystal' || u.kind === 'monster') continue;
      if (!state.targetable(u, m.team)) continue;
      const d2u = dist2(m.pos.x, m.pos.z, u.pos.x, u.pos.z);
      if (d2u < threatD2) { threatD2 = d2u; threat = u; }
    }
    const tgt = threat || ec;
    m.target = tgt;
    const dt2 = dist(m.pos.x, m.pos.z, tgt.pos.x, tgt.pos.z);
    m.face(tgt.pos.x, tgt.pos.z);
    if (dt2 <= m.range + tgt.radius + 0.2) {
      m.moving = false;
      state.performAttack(m, tgt);
    } else {
      m.moveStep(tgt.pos.x - m.pos.x, tgt.pos.z - m.pos.z, dt);
      if (!threat) _minionStuckCheck(m, ec, dt);
    }
    return;
  }

  // 目标维持/重选（超 leash 放弃）
  let t = m.target;
  if (t && (!t.alive || t.kind === 'monster' || !state.targetable(t, m.team) ||
      dist2(m.pos.x, m.pos.z, t.pos.x, t.pos.z) > MINION_LEASH_R * MINION_LEASH_R)) {
    t = null;
  }
  if (!t) t = acquireMinionTarget(m, state);
  m.target = t;

  if (t) {
    const d = dist(m.pos.x, m.pos.z, t.pos.x, t.pos.z);
    m.face(t.pos.x, t.pos.z);
    if (d <= m.range + t.radius + 0.2) {
      m.moving = false;
      state.performAttack(m, t);
    } else {
      m.moveStep(t.pos.x - m.pos.x, t.pos.z - m.pos.z, dt);
    }
    return;
  }

  // 无目标：沿兵线路径点行走（修复(p3)：宽到达半径 + 横向偏移 + 卡死自救）
  const enemyCrystal = state.crystals[m.team === 'blue' ? 'red' : 'blue'];
  const wp = m.path[m.wpIndex];
  if (wp) {
    if (dist(m.pos.x, m.pos.z, wp.x, wp.z) < MINION_WP_ARRIVE_R) {
      m.wpIndex++;
    } else {
      // 瞄准点 = 路径点 + 路径方向垂线 × laneOffset（散成多条并行细流）
      const prev = m.path[m.wpIndex - 1];
      let ax = wp.x, az = wp.z;
      if (prev && m.laneOffset) {
        let dx = wp.x - prev.x, dz = wp.z - prev.z;
        const L = Math.hypot(dx, dz) || 1;
        dx /= L; dz /= L;
        ax += -dz * m.laneOffset;
        az += dx * m.laneOffset;
      }
      m.moveStep(ax - m.pos.x, az - m.pos.z, dt);
      _minionStuckCheck(m, enemyCrystal, dt);
      return;
    }
  }
  // 路径走完：直取敌方水晶
  if (enemyCrystal && enemyCrystal.alive && !state.isCrystalInvuln(enemyCrystal)) {
    const d = dist(m.pos.x, m.pos.z, enemyCrystal.pos.x, enemyCrystal.pos.z);
    if (d <= m.range + enemyCrystal.radius + 0.2) {
      m.moving = false;
      state.performAttack(m, enemyCrystal);
    } else {
      m.moveStep(enemyCrystal.pos.x - m.pos.x, enemyCrystal.pos.z - m.pos.z, dt);
      _minionStuckCheck(m, enemyCrystal, dt);
    }
    return;
  }
  // 修复(p3)：水晶无敌（或已毁）→ 转攻最近可攻击的敌塔（换路推进）
  const tw = _nearestVulnerableTower(m, state);
  if (tw) {
    const d = dist(m.pos.x, m.pos.z, tw.pos.x, tw.pos.z);
    if (d <= m.range + tw.radius + 0.2) {
      m.moving = false;
      state.performAttack(m, tw);
    } else {
      m.moveStep(tw.pos.x - m.pos.x, tw.pos.z - m.pos.z, dt);
      _minionStuckCheck(m, enemyCrystal, dt);
    }
  } else {
    m.moving = false;
  }
}

/** 最近可攻击（非无敌）的敌塔：水晶未解锁时引导破路小兵换路推进 */
function _nearestVulnerableTower(m, state) {
  let best = null, bestD2 = Infinity;
  for (const t of state.towerUnits) {
    if (!t.alive || t.team === m.team) continue;
    if (state.isTowerInvuln(t)) continue;
    const d2 = dist2(m.pos.x, m.pos.z, t.pos.x, t.pos.z);
    if (d2 < bestD2) { bestD2 = d2; best = t; }
  }
  return best;
}

/** 卡死自救（仅赶路状态）：3s 位移 <1 → 视为到达当前路径点 + 向下个目标瞬移 1 单位 */
function _minionStuckCheck(m, enemyCrystal, dt) {
  m._stuckT += dt;
  if (m._stuckT < MINION_STUCK_T) return;
  const moved = dist(m.pos.x, m.pos.z, m._stuckPos.x, m._stuckPos.z);
  m._stuckT = 0;
  m._stuckPos = { x: m.pos.x, z: m.pos.z };
  if (!m.moving || moved >= MINION_STUCK_DIST) return;
  if (m.wpIndex < m.path.length) m.wpIndex++;   // 当前路径点视为到达
  // 向下个目标（路径点/敌方水晶）方向瞬移 1 单位脱困
  const next = m.path[m.wpIndex] ||
    (enemyCrystal && enemyCrystal.alive ? { x: enemyCrystal.pos.x, z: enemyCrystal.pos.z } : null);
  if (next) {
    const dx = next.x - m.pos.x, dz = next.z - m.pos.z;
    const L = Math.hypot(dx, dz) || 1;
    m.pos.x += dx / L;
    m.pos.z += dz / L;
  }
}

/** 防御塔固定步长更新 */
export function updateTower(t, state, dt) {
  if (!t.alive) return;

  // 目标维持
  let target = t.target;
  if (target && (!target.alive || !state.targetable(target, t.team) ||
      dist2(t.pos.x, t.pos.z, target.pos.x, target.pos.z) > TOWER_CFG.RANGE * TOWER_CFG.RANGE)) {
    target = null;
  }

  if (!target) {
    const foes = state.enemiesInRange(t.pos.x, t.pos.z, TOWER_CFG.RANGE, t.team, { structures: false });
    // 转火规则：敌英雄在塔范围内攻击我方英雄 → 优先该英雄
    let aggroHero = null;
    for (const f of foes) {
      if (f.kind === 'monster') continue;        // 塔不打野怪
      if (f.kind === 'hero' && state.time - f.lastHitHeroT < TOWER_CFG.HERO_AGGRO_T) {
        if (!aggroHero || dist2(t.pos.x, t.pos.z, f.pos.x, f.pos.z) <
            dist2(t.pos.x, t.pos.z, aggroHero.pos.x, aggroHero.pos.z)) aggroHero = f;
      }
    }
    if (aggroHero) {
      target = aggroHero;
    } else {
      // 优先小兵，无小兵才打英雄
      let minion = null, hero = null;
      for (const f of foes) {
        if (f.kind === 'monster') continue;
        const d2f = dist2(t.pos.x, t.pos.z, f.pos.x, f.pos.z);
        if (f.kind === 'minion' && (!minion || d2f < dist2(t.pos.x, t.pos.z, minion.pos.x, minion.pos.z))) minion = f;
        if (f.kind === 'hero' && (!hero || d2f < dist2(t.pos.x, t.pos.z, hero.pos.x, hero.pos.z))) hero = f;
      }
      target = minion || hero;
    }
    // 切换目标重置连击
    if (target !== t.comboTarget) { t.comboTarget = target; t.combo = 0; }
  }
  t.target = target;

  if (target && t.attackTimer <= 0) {
    t.attackTimer = 1 / TOWER_CFG.ASPEED;
    // 对同一英雄连击每次 +30%（最多 +90%）
    let mult = 1;
    if (target.kind === 'hero') {
      mult = 1 + TOWER_CFG.COMBO_STEP * Math.min(t.combo, TOWER_CFG.COMBO_MAX);
      t.combo = Math.min(t.combo + 1, TOWER_CFG.COMBO_MAX);
    }
    // 激光弹道视觉（塔顶水晶 → 目标）
    const tc = t.team === 'blue' ? 0x66aaff : 0xff7a66;
    state.vfx.beam(
      { x: t.pos.x, y: 7.5, z: t.pos.z },
      { x: target.pos.x, y: 1.2, z: target.pos.z },
      tc, 0.22);
    state.dealDamage(t, target, TOWER_CFG.AD * mult, { type: 'ad' });
  }
}

// ============================================================
// 阶段3：野怪 AI
//   只主动攻击 3.5 范围内敌人；被攻击会反击；离营超过 leash 半径脱战回营并回满血
// ============================================================
export function updateMonster(m, state, dt) {
  if (!m.alive) return;
  const camp = m.camp;
  if (!camp) { m.moving = false; return; }
  const dHome = dist(m.pos.x, m.pos.z, camp.x, camp.z);

  // 回营中：到达即满血
  if (m.returning) {
    m.target = null;
    if (dHome <= JUNGLE.CAMP_ARRIVE_R) {
      m.returning = false;
      m.hp = m.maxHp;
      m.moving = false;
    } else {
      m.moveStep(camp.x - m.pos.x, camp.z - m.pos.z, dt);
    }
    return;
  }

  // 目标维持（目标离营过远 → 脱战回营）
  let t = m.target;
  if (t && (!t.alive || !state.targetable(t, m.team) ||
      dist(t.pos.x, t.pos.z, camp.x, camp.z) > JUNGLE.LEASH_R)) {
    t = null;
    m.target = null;
    if (dHome > JUNGLE.CAMP_ARRIVE_R) { m.returning = true; return; }
  }

  // 索敌：3.5 范围内敌人 / 复仇最近的攻击者
  if (!t) {
    if (m.lastAttacker && m.lastAttacker.alive && state.targetable(m.lastAttacker, m.team) &&
        state.time - m.lastCombatT < 3 &&
        dist(m.lastAttacker.pos.x, m.lastAttacker.pos.z, camp.x, camp.z) <= JUNGLE.LEASH_R) {
      t = m.lastAttacker;
    } else {
      t = state.nearestEnemy(m, JUNGLE.AGGRO_R) || null;
    }
    m.target = t;
  }

  if (t) {
    const d = dist(m.pos.x, m.pos.z, t.pos.x, t.pos.z);
    m.face(t.pos.x, t.pos.z);
    if (d <= m.range + t.radius + 0.2) {
      m.moving = false;
      state.performAttack(m, t);
    } else if (dist(t.pos.x, t.pos.z, camp.x, camp.z) <= JUNGLE.LEASH_R) {
      m.moveStep(t.pos.x - m.pos.x, t.pos.z - m.pos.z, dt);
    } else {
      // 目标跑出 leash：放弃回营
      m.target = null;
      m.returning = true;
    }
    return;
  }

  // 无目标：回营待命，脱战快速回满
  if (dHome > JUNGLE.CAMP_ARRIVE_R) {
    m.moveStep(camp.x - m.pos.x, camp.z - m.pos.z, dt);
  } else {
    m.moving = false;
    if (state.time - m.lastCombatT > 3 && m.hp < m.maxHp) {
      m.heal(m.maxHp * 0.25 * dt);
    }
  }
}

// ============================================================
// 阶段3：英雄 AI 状态机
//   LANE 守线（跟己方小兵推进/索敌交战/不越塔）
//   JUNGLE 打野（按营地顺序清野）
//   OBJECTIVE 打龙（附近无敌方英雄且状态健康）
//   PUSH 推塔（己方兵线进塔且塔可攻击）
//   RETREAT 撤退（HP<30% 撤向泉水，安全则回城，恢复术）
//   DEFEND 回防（己方塔/水晶 4s 内被攻且自己在 40 内）
//   TEAMFIGHT 团战支援（15 半径内 ≥3 英雄交战，55 内前往）
// ============================================================
export class HeroAI {
  /**
   * @param {Unit} unit AI 控制的英雄
   * @param {GameState} state
   * @param {string} role 'top'|'mid'|'bot'|'bot2'|'jungle'
   */
  constructor(unit, state, role) {
    this.unit = unit;
    this.state = state;
    this.role = role;
    this.lane = role === 'bot2' ? 'bot' : role;   // bot2 与 bot 同路
    this.mode = 'LANE';
    this._wasAlive = true;
    this._stickT = 0;
    this._lastPos = null;
    this._detour = null;
    this._detourSign = 1;
    this._buysThisVisit = 0;
    // 敌方泉水（禁止深入）
    this._enemyFountain = unit.team === TEAM.BLUE ? MAP.RED_FOUNTAIN : MAP.BLUE_FOUNTAIN;
    // 终局(p5)：基地门路点导航——双方基地中心 + 三个出入口（与 map.js 围墙缺口同几何）
    const ob = unit.team === TEAM.BLUE ? MAP.BLUE_BASE : MAP.RED_BASE;
    const eb = unit.team === TEAM.BLUE ? MAP.RED_BASE : MAP.BLUE_BASE;
    this._bases = [
      { x: ob.x, z: ob.z, gates: BASE_GATES[unit.team] },
      { x: eb.x, z: eb.z, gates: BASE_GATES[unit.team === TEAM.BLUE ? 'red' : 'blue'] },
    ];
    this._activeGate = null;     // 当前导航经过的门点
    this._gateBan = null;        // 卡死换门：{gate, until}
    this._hsT = 0;               // 硬卡死计时
    this._hsPos = null;
  }

  // ---------------- 主更新 ----------------
  update(dt) {
    const u = this.unit, s = this.state;
    if (s.over) return;
    if (!u.alive) { this._wasAlive = false; return; }
    if (!this._wasAlive) {          // 重生：重置状态机
      this._wasAlive = true;
      this.mode = 'LANE';
      this._detour = null;
    }

    // 回城引导中
    if (u.channel && u.channel.type === 'recall') {
      u.channel.t += dt;
      u.moving = false;
      if (u.channel.t >= u.channel.dur) {
        u.channel = null;
        u.pos.set(u.homePos.x, 0, u.homePos.z);
        u.syncModel();
      }
      return;
    }
    if (u.isLocked()) { u.moving = false; return; }

    const hpPct = u.hp / u.maxHp;
    const dHome = dist(u.pos.x, u.pos.z, u.homePos.x, u.homePos.z);
    const atHome = dHome < FOUNTAIN.R + 1.5;

    // 泉水买装（按推荐出装）
    if (atHome) {
      this._buy();
    } else {
      this._buysThisVisit = 0;
    }

    // ---- 撤退（HP<30%） ----
    if (this.mode === 'RETREAT') {
      if (atHome && hpPct >= AI_CFG.RETURN_HP) {
        this.mode = 'LANE';
      } else {
        this._retreat(dt, atHome);
        return;
      }
    } else if (hpPct < AI_CFG.RETREAT_HP) {
      this.mode = 'RETREAT';
      this._retreat(dt, atHome);
      return;
    }

    // ---- 恢复术：血量偏低且近期在战斗 ----
    if (hpPct < AI_CFG.HEAL_HP && u.cooldowns.heal <= 0 && s.time - u.lastCombatT < 3) {
      s.castHeal(u);
    }

    // ---- 回防（己方塔/水晶被攻；水晶被攻全员回防不限距离） ----
    const def = this._findDefense();
    if (def) {
      this.mode = 'DEFEND';
      if (this._engage(dt, AI_CFG.ENGAGE_R + u.range * 0.5)) return;
      this._moveTo(def.pos.x, def.pos.z, dt);
      return;
    }

    // ---- 强攻水晶(p5)：敌水晶已解防且自己已进入敌基地围墙圈 → 水晶优先于缠斗 ----
    // （残血撤退/回防已在上方优先处理；修复杂交：原 _engage 排除建筑、GROUP_PUSH
    //   塔先水晶后，导致英雄站在解防水晶旁永不攻击 → 比赛无法终结）
    const ec = s.crystals[u.team === TEAM.BLUE ? 'red' : 'blue'];
    if (ec && ec.alive && !s.isCrystalInvuln(ec)) {
      const eb = u.team === TEAM.BLUE ? MAP.RED_BASE : MAP.BLUE_BASE;
      if (dist2(u.pos.x, u.pos.z, eb.x, eb.z) < (MAP.WALL_R + 2) * (MAP.WALL_R + 2)) {
        this.mode = 'ASSAULT';
        this._castSkills(ec);
        this._attackUnit(ec, dt, false, true);
        return;
      }
    }

    // ---- 团战支援 ----
    const fight = this._findTeamfight();
    if (fight && hpPct > 0.4) {
      this.mode = 'TEAMFIGHT';
      if (this._engage(dt, AI_CFG.ENGAGE_R + u.range * 0.5)) return;
      this._moveTo(fight.x, fight.z, dt);
      return;
    }

    // ---- 终局集团推进(p4/p5)：敌方水晶已解防且己方存活 ≥2 人 → 集合强拆水晶 ----
    // 优先级高于打野/带线/打龙（但让位于撤退/回防/贴脸自卫，避免无限换家僵持）
    // p5：目标改为水晶优先——p4 塔先规则在守卫减伤改为梯度(0.3~1.0)后已无必要，
    //   且实测"舍近求远绕去拆边塔"会被泉水守军无限消耗（36 分钟僵持）；
    //   水晶守卫梯度下多人集火 + 超级兵可实际完成击杀
    if (this._shouldGroupPush()) {
      this.mode = 'GROUP_PUSH';
      // p5：集团推进只反击贴脸敌人（原 12+ 索敌半径在敌方基地常年被守军/兵线吸住，
      //   永远"先打架"摸不到建筑 → 终局拉锯 6+ 分钟）
      if (this._engage(dt, Math.max(4, u.range * 0.8))) return;
      const ec = s.crystals[u.team === TEAM.BLUE ? 'red' : 'blue'];
      this._attackUnit(ec, dt, false, true);   // 强拆水晶：不要求兵线掩护
      return;
    }

    // ---- 打龙/主宰 ----
    const obj = this._findObjective();
    if (obj) {
      this.mode = 'OBJECTIVE';
      if (this._engage(dt, AI_CFG.ENGAGE_R)) return;   // 先应付来犯敌人
      this._attackUnit(obj, dt, true);
      return;
    }

    // ---- 默认：分路 / 打野 ----
    this.mode = this.role === 'jungle' ? 'JUNGLE' : 'LANE';
    if (this.role === 'jungle') this._jungle(dt);
    else this._lane(dt);
  }

  // ---------------- 撤退 ----------------
  _retreat(dt, atHome) {
    const u = this.unit, s = this.state;
    // 恢复术
    if (u.cooldowns.heal <= 0 && u.hp / u.maxHp < AI_CFG.HEAL_HP) s.castHeal(u);
    if (atHome) { u.moving = false; return; }
    const dHome = dist(u.pos.x, u.pos.z, u.homePos.x, u.homePos.z);
    // 安全（附近无敌、2s 未受击）且离家尚远 → 引导回城
    const danger = s.nearestEnemy(u, AI_CFG.RECALL_SAFE_R, { heroesOnly: true }) ||
      s.time - u.lastCombatT < 2;
    if (!danger && u.cooldowns.recall <= 0 && dHome > 25) {
      u.channel = { type: 'recall', t: 0, dur: RECALL_CFG.CHANNEL };
      return;
    }
    this._moveTo(u.homePos.x, u.homePos.z, dt);
  }

  // ---------------- 回防目标：最近被攻的己方塔/水晶 ----------------
  _findDefense() {
    const u = this.unit, s = this.state;
    // 终局(p4)：水晶被攻击 → 全员回防（不限距离）
    const crystal = s.crystals[u.team];
    if (crystal && crystal.alive &&
        s.time - crystal.lastCombatT <= AI_CFG.DEFEND_RECENT &&
        crystal.lastAttacker && crystal.lastAttacker.team !== u.team) {
      return crystal;
    }
    let best = null, bestD2 = AI_CFG.DEFEND_RANGE * AI_CFG.DEFEND_RANGE;
    const check = (t) => {
      if (!t.alive || t.team !== u.team) return;
      if (s.time - t.lastCombatT > AI_CFG.DEFEND_RECENT) return;
      if (!t.lastAttacker || t.lastAttacker.team === u.team) return;
      const d2 = dist2(u.pos.x, u.pos.z, t.pos.x, t.pos.z);
      if (d2 < bestD2) { bestD2 = d2; best = t; }
    };
    for (const t of s.towerUnits) check(t);
    return best;
  }

  // ---------------- 终局(p4)：是否进入集团推水晶 ----------------
  _shouldGroupPush() {
    const u = this.unit, s = this.state;
    const ec = s.crystals[u.team === TEAM.BLUE ? 'red' : 'blue'];
    if (!ec || !ec.alive || s.isCrystalInvuln(ec)) return false;
    let alive = 0;
    for (const h of s.units) {
      if (h.kind === 'hero' && h.team === u.team && h.alive) alive++;
    }
    return alive >= AI_CFG.GROUP_PUSH_MIN;
  }

  // ---------------- 团战点：15 半径内 ≥3 英雄交战 ----------------
  _findTeamfight() {
    const u = this.unit, s = this.state;
    let best = null, bestCnt = 0;
    for (const h of s.units) {
      if (h.kind !== 'hero' || !h.alive) continue;
      if (s.time - h.lastCombatT > AI_CFG.FIGHT_RECENT) continue;
      let cnt = 0;
      for (const o of s.units) {
        if (o.kind !== 'hero' || !o.alive) continue;
        if (dist2(h.pos.x, h.pos.z, o.pos.x, o.pos.z) <= AI_CFG.FIGHT_R * AI_CFG.FIGHT_R) cnt++;
      }
      if (cnt >= AI_CFG.FIGHT_HEROES && cnt > bestCnt) { bestCnt = cnt; best = h; }
    }
    if (!best) return null;
    if (dist2(u.pos.x, u.pos.z, best.pos.x, best.pos.z) > AI_CFG.FIGHT_JOIN_R * AI_CFG.FIGHT_JOIN_R) return null;
    return { x: best.pos.x, z: best.pos.z };
  }

  // ---------------- 打龙判断 ----------------
  _findObjective() {
    const u = this.unit, s = this.state;
    if (u.hp / u.maxHp < AI_CFG.OBJECTIVE_HP) return null;
    for (const camp of s.spawner.objectives) {
      const m = camp.units.find(x => x.alive);
      if (!m) continue;
      // 龙坑附近无敌方英雄
      const enemyNear = s.enemiesInRange(camp.x, camp.z, AI_CFG.OBJECTIVE_SAFE_R, u.team, { heroesOnly: true });
      if (enemyNear.length) continue;
      if (this.role === 'jungle') return m;
      // 非打野：队友已在打且自己在附近 → 支援
      if (m.lastAttacker && m.lastAttacker.team === u.team && s.time - m.lastCombatT < 2 &&
          dist2(u.pos.x, u.pos.z, camp.x, camp.z) < 16 * 16) return m;
    }
    return null;
  }

  // ---------------- 守线 ----------------
  _lane(dt) {
    const u = this.unit, s = this.state;

    // 1) 交战（含清兵，英雄权重 1.5）
    if (this._engage(dt, Math.max(AI_CFG.ENGAGE_R, u.range + 4))) return;

    // 2) 推塔/推水晶（己方兵线进塔且塔可攻击）
    const pushT = this._pushTarget();
    if (pushT) {
      this.mode = 'PUSH';
      this._attackUnit(pushT, dt, false);
      return;
    }

    // 3) 跟随己方兵线推进；无兵线则守在己方该路最前存活塔附近
    const anchor = this._foremostMinion();
    if (anchor) {
      const d = dist(u.pos.x, u.pos.z, anchor.pos.x, anchor.pos.z);
      if (d > 4) { this._moveTo(anchor.pos.x, anchor.pos.z, dt); return; }
      u.moving = false;
      return;
    }
    const tw = this._ownForwardTower();
    if (tw) {
      const d = dist(u.pos.x, u.pos.z, tw.pos.x, tw.pos.z);
      if (d > 6) { this._moveTo(tw.pos.x, tw.pos.z, dt); return; }
      u.moving = false;
      return;
    }
    // 塔全破：沿兵线向敌方基地推进
    const crystal = s.crystals[u.team === TEAM.BLUE ? 'red' : 'blue'];
    if (crystal && crystal.alive) this._moveTo(crystal.pos.x, crystal.pos.z, dt);
    else u.moving = false;
  }

  // ---------------- 打野 ----------------
  _jungle(dt) {
    const u = this.unit, s = this.state;

    // 1) 来犯敌人
    if (this._engage(dt, Math.max(9, u.range + 3))) return;

    // 2) 按营地顺序清野
    const camp = this._nextCamp();
    if (camp) {
      const m = camp.units.find(x => x.alive);
      if (m) { this._attackUnit(m, dt, true); return; }
    }

    // 3) 野区清空：前往最近将刷新的自家营地待命（半路遇敌由 engage 处理）
    const wait = this._nextRespawnCamp();
    if (wait) {
      const d = dist(u.pos.x, u.pos.z, wait.x, wait.z);
      if (d > 6) { this._moveTo(wait.x, wait.z, dt); return; }
    }
    u.moving = false;
  }

  /** 下一个有活怪的自家营地（按离家最近的贪心顺序） */
  _nextCamp() {
    const route = this._campRoute();
    for (const camp of route) {
      if (camp.units.some(x => x.alive)) return camp;
    }
    return null;
  }

  _nextRespawnCamp() {
    const route = this._campRoute();
    let best = null;
    for (const camp of route) {
      if (camp.units.some(x => x.alive)) continue;
      if (!best || camp.respawnAt < best.respawnAt) best = camp;
    }
    return best;
  }

  /** 自家营地清野顺序（缓存） */
  _campRoute() {
    if (this._route) return this._route;
    const s = this.state;
    const camps = s.spawner.camps.filter(c => c.team === this.unit.team);
    // 贪心最近邻排序：从自家泉水出发
    const route = [];
    let cx = this.unit.homePos.x, cz = this.unit.homePos.z;
    const rest = camps.slice();
    while (rest.length) {
      let bi = 0, bd = Infinity;
      for (let i = 0; i < rest.length; i++) {
        const d2 = dist2(cx, cz, rest[i].x, rest[i].z);
        if (d2 < bd) { bd = d2; bi = i; }
      }
      const c = rest.splice(bi, 1)[0];
      route.push(c);
      cx = c.x; cz = c.z;
    }
    this._route = route;
    return route;
  }

  // ---------------- 交战：索敌（英雄权重1.5）→ 技能 → 普攻/走位 ----------------
  /** @returns {boolean} 是否有目标并处理了战斗 */
  _engage(dt, radius) {
    const u = this.unit, s = this.state;
    const target = this._selectTarget(radius);
    if (!target) return false;
    this._castSkills(target);
    this._attackUnit(target, dt, false);
    return true;
  }

  /** 范围内最优敌人：等效距离 = 实际距离 / （英雄1.5）；排除建筑/野怪/敌方泉水内 */
  _selectTarget(radius) {
    const u = this.unit, s = this.state;
    const foes = s.enemiesInRange(u.pos.x, u.pos.z, radius, u.team);
    let best = null, bestScore = Infinity;
    for (const f of foes) {
      if (f.kind === 'tower' || f.kind === 'crystal') continue;
      if (f.kind === 'monster') {
        // 只反击正在攻击自己的野怪（打野清野走 _attackUnit 显式指定）
        if (f.target !== u) continue;
      }
      // 不追进敌方泉水
      if (dist2(f.pos.x, f.pos.z, this._enemyFountain.x, this._enemyFountain.z) <
          (FOUNTAIN.R + 2) * (FOUNTAIN.R + 2)) continue;
      const d = dist(u.pos.x, u.pos.z, f.pos.x, f.pos.z);
      const score = d / (f.kind === 'hero' ? AI_CFG.HERO_WEIGHT : 1);
      if (score < bestScore) { bestScore = score; best = f; }
    }
    if (!best) return null;
    // 越塔保护：目标在敌塔射程内且无己方小兵掩护 → 不换命
    if (best.kind === 'hero' && this._underEnemyTower(best) && !this._towerCover(this._enemyTowerNear(best))) {
      const tw = this._enemyTowerNear(u);
      if (tw) {
        // 自己在塔射程内：撤出
        const d = dist(u.pos.x, u.pos.z, tw.pos.x, tw.pos.z);
        if (d < TOWER_CFG.RANGE + 0.5) {
          this._moveTo(u.pos.x + (u.pos.x - tw.pos.x), u.pos.z + (u.pos.z - tw.pos.z), 1 / 30);
          return null;
        }
      }
      return null;
    }
    return best;
  }

  _enemyTowerNear(p) {
    const u = this.unit, s = this.state;
    for (const t of s.towerUnits) {
      if (!t.alive || t.team === u.team) continue;
      if (dist2(p.pos.x, p.pos.z, t.pos.x, t.pos.z) <= (TOWER_CFG.RANGE + 1) * (TOWER_CFG.RANGE + 1)) return t;
    }
    return null;
  }

  _underEnemyTower(p) { return !!this._enemyTowerNear(p); }

  /** 己方小兵是否已进入某塔范围（越塔/推塔掩护） */
  _towerCover(tower) {
    if (!tower) return false;
    return this.state.unitsInCircle(tower.pos.x, tower.pos.z, AI_CFG.TOWER_COVER_R,
      m => m.kind === 'minion' && m.team === this.unit.team).length > 0;
  }

  // ---------------- 技能使用：CD 好就用；大招只打英雄 ----------------
  _castSkills(target) {
    const u = this.unit, s = this.state;
    const defs = SKILLS[u.heroId];
    if (!defs) return;
    const d = dist(u.pos.x, u.pos.z, target.pos.x, target.pos.z);
    for (const slot of ['s1', 's2', 'ult']) {
      if (!s.skills.castable(u, slot)) continue;
      const def = defs[slot];
      if (slot === 'ult' && target.kind !== 'hero') continue;   // 大招优先打英雄
      const reach = (def.range || def.radius || 8) + (target.radius || 0);
      let want = false;
      if (def.aim === 'self') want = d < 10;
      else if (def.aim === 'around') want = d <= reach;
      else want = d <= Math.min(reach, slot === 'ult' ? 45 : reach);  // 后羿大限制在 45 内
      if (want) s.skills.cast(u, slot, { target });
    }
  }

  // ---------------- 攻击指定单位（含走位/风筝） ----------------
  // @param forceStructure 终局(p4) GROUP_PUSH 强拆水晶：不要求兵线掩护（水晶无火力）
  _attackUnit(target, dt, isMonster, forceStructure) {
    const u = this.unit, s = this.state;
    if (!target || !target.alive) return;
    const d = dist(u.pos.x, u.pos.z, target.pos.x, target.pos.z);
    const reach = u.range + (target.radius || 0) + 0.2;

    // 打塔时确认兵线掩护，否则不硬抗
    if (target.kind === 'tower' || target.kind === 'crystal') {
      if (!forceStructure && !this._towerCover(target)) {
        // 无兵线：在塔射程外等待
        if (d < TOWER_CFG.RANGE + 1) {
          this._moveTo(u.pos.x + (u.pos.x - target.pos.x), u.pos.z + (u.pos.z - target.pos.z), dt);
        } else u.moving = false;
        return;
      }
    }

    if (d <= reach) {
      u.moving = false;
      s.performAttack(u, target);
      return;
    }
    // 远程风筝：保持 0.6~0.9 倍射程
    if (u.range > 5 && d < u.range * 0.55 && !isMonster) {
      this._moveTo(u.pos.x + (u.pos.x - target.pos.x) * 0.5, u.pos.z + (u.pos.z - target.pos.z) * 0.5, dt);
      return;
    }
    this._moveTo(target.pos.x, target.pos.z, dt);
  }

  // ---------------- 推塔目标 ----------------
  // 修复(p3)：原逻辑只考虑 30 内本路塔 → 前线拉锯时英雄永远够不到塔/水晶，比赛僵持
  // 新逻辑：1) 本路最近可攻击敌塔（不限距离，引导英雄随兵线压到塔下）
  //        2) 本路塔全破 → 全图最近可攻击敌塔（换路推进）
  //        3) 水晶已解锁且兵线已到 → 直取水晶（不限距离）
  _pushTarget() {
    const u = this.unit, s = this.state;
    let best = null, bestD2 = Infinity;
    for (const t of s.towerUnits) {
      if (!t.alive || t.team === u.team || t.lane !== this.lane) continue;
      if (s.isTowerInvuln(t)) continue;
      const d2 = dist2(u.pos.x, u.pos.z, t.pos.x, t.pos.z);
      if (d2 < bestD2) { bestD2 = d2; best = t; }
    }
    if (!best) {   // 本路已通：换路推最近可攻击的塔
      for (const t of s.towerUnits) {
        if (!t.alive || t.team === u.team) continue;
        if (s.isTowerInvuln(t)) continue;
        const d2 = dist2(u.pos.x, u.pos.z, t.pos.x, t.pos.z);
        if (d2 < bestD2) { bestD2 = d2; best = t; }
      }
    }
    if (best && this._towerCover(best)) return best;
    const crystal = s.crystals[u.team === TEAM.BLUE ? 'red' : 'blue'];
    if (crystal && crystal.alive && !s.isCrystalInvuln(crystal) &&
        this._towerCover(crystal)) return crystal;
    return null;
  }

  /** 己方本路最前小兵（离敌方基地最近） */
  _foremostMinion() {
    const u = this.unit, s = this.state;
    const enemyBase = u.team === TEAM.BLUE ? MAP.RED_BASE : MAP.BLUE_BASE;
    let best = null, bestD2 = Infinity;
    for (const m of s.units) {
      if (m.kind !== 'minion' || !m.alive || m.team !== u.team || m.lane !== this.lane) continue;
      const d2 = dist2(m.pos.x, m.pos.z, enemyBase.x, enemyBase.z);
      if (d2 < bestD2) { bestD2 = d2; best = m; }
    }
    return best;
  }

  /** 己方本路最前存活塔（序号最小） */
  _ownForwardTower() {
    const u = this.unit, s = this.state;
    let best = null;
    for (const t of s.towerUnits) {
      if (!t.alive || t.team !== u.team || t.lane !== this.lane) continue;
      if (!best || t.tier < best.tier) best = t;
    }
    return best;
  }

  // ---------------- 路点导航(p5)：目标与自身分处某基地围墙内外两侧时，先走该基地最优门点 ----------------
  // （英雄 _moveTo 为直线走法，非门方向撞墙即被碰撞推挤卡死；小兵有兵线路径点不受影响）
  _gateRoute(x, z) {
    const u = this.unit;
    const R2 = (MAP.WALL_R - 1.5) * (MAP.WALL_R - 1.5);   // 围墙内判定
    this._activeGate = null;
    for (const b of this._bases) {
      const dIn = dist2(x, z, b.x, b.z) < R2;
      const uIn = dist2(u.pos.x, u.pos.z, b.x, b.z) < R2;
      if (dIn === uIn) continue;                          // 同侧：直线可达
      // 选门：hero→gate→dest 总程最短；硬卡死时被禁用的门暂时跳过
      const banned = this._gateBan && this.state.time < this._gateBan.until ? this._gateBan.gate : null;
      let best = null, bestCost = Infinity;
      for (const g of b.gates) {
        if (g === banned) continue;
        const cost = Math.hypot(u.pos.x - g.x, u.pos.z - g.z) + Math.hypot(x - g.x, z - g.z);
        if (cost < bestCost) { bestCost = cost; best = g; }
      }
      if (!best) { this._gateBan = null; best = b.gates[0]; }   // 全被禁：解禁兜底
      this._activeGate = best;
      if (dist(u.pos.x, u.pos.z, best.x, best.z) > 3.2) return best;
      return { x, z };   // 已到门口：直线穿门
    }
    return { x, z };
  }

  // ---------------- 移动（含防卡死侧向绕行 + 基地门路点） ----------------
  _moveTo(x, z, dt) {
    const u = this.unit;
    // p5：跨基地围墙 → 先导航到门点
    const rt = this._gateRoute(x, z);
    x = rt.x; z = rt.z;
    // 禁止深入敌方泉水
    const ef = this._enemyFountain;
    if (dist2(x, z, ef.x, ef.z) < (FOUNTAIN.R + 4) * (FOUNTAIN.R + 4)) {
      u.moving = false;
      return;
    }
    // 绕行点生效中
    if (this._detour) {
      this._detour.t -= dt;
      if (this._detour.t <= 0) this._detour = null;
      else { u.moveStep(this._detour.x - u.pos.x, this._detour.z - u.pos.z, dt); return; }
    }
    u.moveStep(x - u.pos.x, z - u.pos.z, dt);

    // 防卡：每 0.5s 检查实际位移，过小则取侧向绕行点
    this._stickT += dt;
    if (this._stickT >= 0.5) {
      if (this._lastPos) {
        const moved = dist(u.pos.x, u.pos.z, this._lastPos.x, this._lastPos.z);
        const expect = u.effSpeed() * this._stickT;
        if (u.moving && moved < expect * 0.3 && moved < 2) {
          const dx = x - u.pos.x, dz = z - u.pos.z;
          const L = Math.hypot(dx, dz) || 1;
          this._detourSign = -this._detourSign;
          this._detour = {
            x: u.pos.x + (-dz / L) * 9 * this._detourSign + dx / L * 3,
            z: u.pos.z + (dx / L) * 9 * this._detourSign + dz / L * 3,
            t: 1.1,
          };
        }
      }
      this._lastPos = { x: u.pos.x, z: u.pos.z };
      this._stickT = 0;
    }

    // 硬卡死自救(p5)：2s 位移 <0.5 → 禁用当前门点 4s（换门）+ 侧向抖动
    this._hsT += dt;
    if (this._hsT >= 2) {
      const moved = this._hsPos ? dist(u.pos.x, u.pos.z, this._hsPos.x, this._hsPos.z) : 99;
      if (u.moving && moved < 0.5) {
        if (this._activeGate) this._gateBan = { gate: this._activeGate, until: this.state.time + 4 };
        this._detourSign = -this._detourSign;
        const dx = x - u.pos.x, dz = z - u.pos.z;
        const L = Math.hypot(dx, dz) || 1;
        this._detour = {
          x: u.pos.x + (-dz / L) * 6 * this._detourSign,
          z: u.pos.z + (dx / L) * 6 * this._detourSign,
          t: 0.8,
        };
      }
      this._hsPos = { x: u.pos.x, z: u.pos.z };
      this._hsT = 0;
    }
  }

  // ---------------- 买装 ----------------
  _buy() {
    const u = this.unit;
    if (!u.shop) return;
    while (this._buysThisVisit < AI_CFG.BUY_MAX_PER_VISIT && u.shop.buyRecommended()) {
      this._buysThisVisit++;
    }
  }
}
