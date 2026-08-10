// ============================================================
// 技能系统（阶段2）
// 5 套英雄技能定义 + 施放逻辑 + 弹道（直线/指向/追踪）+ Buff 系统
// Buff 类型：haste/slow/stun/silence/knockup/knockback/stealth/shield/dot/mark
//            empower(强化普攻)/multishot(三连发)/aura_dot(亚瑟S2)/foxfire(妲己大)/dash(牛魔S2)
// CD: S1 8s / S2 10s / Ult 40s（每级 -3s）；技能基础伤害每级 +12%
// ============================================================
import { SKILL_COMMON, BUFFS, BRUSH_CFG } from '../config.js';
import { dist, dist2, TAU } from '../utils.js';

const C = SKILL_COMMON;

// 技能数值表（严格按设计文档）
export const SKILLS = {
  // ---------------- 亚瑟·战士（近战，无蓝条） ----------------
  arthur: {
    s1: {
      name: '誓约之盾', key: 'Q', cd: C.S1_CD, mana: 0, aim: 'self',
      // 3s 移速+30% 并强化下次普攻：额外 120+0.6AD + 沉默 1s
      cast(sk, u) {
        u.addBuff({ type: 'haste', mult: 1.3, dur: 3, group: 'arthur1' });
        u.addBuff({ type: 'empower', bonus: sk.scale(u, 120, 0.6, 'ad', 's1'), silence: 1, dur: 3, group: 'arthur1' });
        sk.state.vfx.groundCircle(u.pos.x, u.pos.z, 1.6, 0xffd860, 0.6);
        // p5b：技能金光改为附加发光壳（叠加层，不碰本体材质，0.6s 自动消退）
        sk.state.vfx.glowShell(u.model, 0xffd860, 0.6, 1.15, 0.32);
      },
    },
    s2: {
      name: '回旋打击', key: 'E', cd: C.S2_CD, mana: 0, aim: 'around', radius: 4,
      // 3s 内每秒对半径 4 内敌人 90+0.5AD
      cast(sk, u) {
        u.addBuff({
          type: 'aura_dot', dur: 3, tick: 1, tickT: 0.01, r: 4,
          amount: sk.scale(u, 90, 0.5, 'ad', 's2'), group: 'arthur2',
        });
        sk.state.vfx.groundCircle(u.pos.x, u.pos.z, 4, 0xffe080, 3);
      },
    },
    ult: {
      name: '圣剑裁决', key: 'R', cd: C.ULT_CD, mana: 0, aim: 'target', range: 8,
      // 跃向 8 内目标：350+1.0AD + 目标已损生命 12%
      cast(sk, u, target) {
        const missing = (target.maxHp - target.hp) * 0.12;
        // 跃击：瞬移到目标身前
        const dx = u.pos.x - target.pos.x, dz = u.pos.z - target.pos.z;
        const d = Math.hypot(dx, dz) || 1;
        u.pos.x = target.pos.x + dx / d * (target.radius + 1.2);
        u.pos.z = target.pos.z + dz / d * (target.radius + 1.2);
        u.face(target.pos.x, target.pos.z);
        u.syncModel();
        sk.state.vfx.burst(target.pos.x, 1, target.pos.z, { color: 0xffe680, count: 30, speed: 8, life: 0.6 });
        sk.state.vfx.groundCircle(target.pos.x, target.pos.z, 3, 0xffd040, 0.8);
        sk.dealSkillDamage(u, target, 350, 1.0, 'ad', 'ult', 'ad', missing);
      },
    },
  },

  // ---------------- 后羿·射手（远程） ----------------
  houyi: {
    s1: {
      name: '炙热之风', key: 'Q', cd: C.S1_CD, mana: C.S1_MANA, aim: 'self',
      // 4s 内普攻三连发（每发 60%AD）
      cast(sk, u) {
        u.addBuff({ type: 'multishot', dur: 4, group: 'houyi1' });
        sk.state.vfx.groundCircle(u.pos.x, u.pos.z, 1.6, 0xff9040, 0.6);
      },
    },
    s2: {
      name: '燎原箭雨', key: 'E', cd: C.S2_CD, mana: C.S2_MANA, aim: 'area', range: 12, radius: 6,
      // 对 6 半径区域 240+0.8AD + 减速 30% 2s
      cast(sk, u, target) {
        const cx = target.pos.x, cz = target.pos.z;
        sk.state.vfx.warnCircle(cx, cz, 6, 0.9, 0xff5030);   // AOE 红色预警圈（脉冲）
        // 箭雨延迟 0.4s 落下
        sk.delay(0.4, () => {
          sk.state.vfx.burst(cx, 3, cz, { color: 0xff8040, count: 36, speed: 7, life: 0.7, up: 8 });
          for (const e of sk.state.enemiesInRange(cx, cz, 6, u.team)) {
            sk.dealSkillDamage(u, e, 240, 0.8, 'ad', 's2');
            if (e.alive) e.addBuff({ type: 'slow', pct: 0.3, dur: 2 });
          }
        });
      },
    },
    ult: {
      name: '灼日之矢', key: 'R', cd: C.ULT_CD, mana: C.ULT_MANA, aim: 'line', range: 60,
      // 直线超远弹道，命中英雄眩晕 0.5~2.5s（随距离）+400+1.0AD
      cast(sk, u, target) {
        let dx = 1, dz = 1;
        if (target) { dx = target.pos.x - u.pos.x; dz = target.pos.z - u.pos.z; }
        else { dx = Math.sin(u.yaw); dz = Math.cos(u.yaw); }
        const len = Math.hypot(dx, dz) || 1;
        sk.state.vfx.directionArrow(u.pos.x, u.pos.z, dx / len, dz / len, 12, 0xff5030, 0.5);
        sk.spawnProjectile({
          source: u, dir: { x: dx / len, z: dz / len }, speed: 26, range: 60,
          radius: 1.6, color: 0xff4020, size: 0.7, pierceMinions: true,
          onHit: (e, traveled) => {
            if (e.kind !== 'hero') return false;   // 只拦英雄
            const stun = 0.5 + 2.0 * Math.min(1, traveled / 60);
            sk.dealSkillDamage(u, e, 400, 1.0, 'ad', 'ult');
            if (e.alive) e.addBuff({ type: 'stun', dur: stun });
            sk.state.vfx.burst(e.pos.x, 1.5, e.pos.z, { color: 0xff5030, count: 40, speed: 9, life: 0.8 });
            return true;
          },
        });
      },
    },
  },

  // ---------------- 妲己·法师（远程） ----------------
  daji: {
    s1: {
      name: '灵魂冲击', key: 'Q', cd: C.S1_CD, mana: C.S1_MANA, aim: 'line', range: 11,
      // 弹道 280+0.75AP
      cast(sk, u, target) {
        let dx = Math.sin(u.yaw), dz = Math.cos(u.yaw);
        if (target) {
          dx = target.pos.x - u.pos.x; dz = target.pos.z - u.pos.z;
          const len = Math.hypot(dx, dz) || 1; dx /= len; dz /= len;
        }
        sk.spawnProjectile({
          source: u, dir: { x: dx, z: dz }, speed: 20, range: 11,
          radius: 1.4, color: 0xff7ad0, size: 0.5,
          onHit: (e) => {
            sk.dealSkillDamage(u, e, 280, 0.75, 'ap', 's1', 'ap');
            sk.state.vfx.burst(e.pos.x, 1.2, e.pos.z, { color: 0xff7ad0, count: 18, speed: 6, life: 0.5 });
            return true;
          },
        });
      },
    },
    s2: {
      name: '偶像魅力', key: 'E', cd: C.S2_CD, mana: C.S2_MANA, aim: 'target', range: 10,
      // 弹道命中眩晕 1.2s + 180+0.5AP
      cast(sk, u, target) {
        sk.spawnProjectile({
          source: u, target, homing: true, speed: 18, range: 14,
          radius: 1.0, color: 0xff9ae0, size: 0.55,
          onHit: (e) => {
            sk.dealSkillDamage(u, e, 180, 0.5, 'ap', 's2', 'ap');
            if (e.alive) e.addBuff({ type: 'stun', dur: 1.2 });
            sk.state.vfx.burst(e.pos.x, 1.5, e.pos.z, { color: 0xff9ae0, count: 24, speed: 5, life: 0.6 });
            return true;
          },
        });
      },
    },
    ult: {
      name: '女王崇拜', key: 'R', cd: C.ULT_CD, mana: C.ULT_MANA, aim: 'around', radius: 10,
      // 5 团狐火自动追踪 10 内敌人（优先英雄），每团 160+0.45AP
      cast(sk, u) {
        u.addBuff({ type: 'foxfire', dur: 1.2, count: 5, interval: 0.18, tickT: 0.01, group: 'daji3' });
        sk.state.vfx.groundCircle(u.pos.x, u.pos.z, 10, 0xff7ad0, 0.7);
      },
    },
  },

  // ---------------- 牛魔·坦克（近战） ----------------
  niumo: {
    s1: {
      name: '咆哮之斧', key: 'Q', cd: C.S1_CD, mana: C.S1_MANA, aim: 'around', radius: 3.5,
      // 横扫 200+0.7AD + 减速 25%（2s）
      cast(sk, u) {
        sk.state.vfx.groundCircle(u.pos.x, u.pos.z, 3.5, 0xff5040, 0.5);
        sk.state.vfx.burst(u.pos.x, 1.2, u.pos.z, { color: 0xff5040, count: 20, speed: 6, life: 0.4 });
        for (const e of sk.state.enemiesInRange(u.pos.x, u.pos.z, 3.5, u.team)) {
          sk.dealSkillDamage(u, e, 200, 0.7, 'ad', 's1');
          if (e.alive) e.addBuff({ type: 'slow', pct: 0.25, dur: 2 });
        }
      },
    },
    s2: {
      name: '横行霸道', key: 'E', cd: C.S2_CD, mana: C.S2_MANA, aim: 'dash', range: 8,
      // 冲锋 8 距离，击退路径敌人 + 220+0.6AD
      cast(sk, u, target) {
        let dx = Math.sin(u.yaw), dz = Math.cos(u.yaw);
        if (target) {
          dx = target.pos.x - u.pos.x; dz = target.pos.z - u.pos.z;
          const len = Math.hypot(dx, dz) || 1; dx /= len; dz /= len;
        }
        u.addBuff({
          type: 'dash', dir: { x: dx, z: dz }, speed: 30, remaining: 8,
          hitSet: new Set(), dmg: sk.scale(u, 220, 0.6, 'ad', 's2'), dur: 0.5, group: 'niumo2',
        });
        sk.state.vfx.directionArrow(u.pos.x, u.pos.z, dx, dz, 8, 0xff5040, 0.5);
      },
    },
    ult: {
      name: '山崩地裂', key: 'R', cd: C.ULT_CD, mana: C.ULT_MANA, aim: 'around', radius: 5,
      // 半径 5 击飞 1s + 300+0.8AD + 自身 500 护盾 3s
      cast(sk, u) {
        sk.state.vfx.warnCircle(u.pos.x, u.pos.z, 5, 0.9, 0xff6040);
        sk.state.vfx.burst(u.pos.x, 0.5, u.pos.z, { color: 0xff6040, count: 46, speed: 8, life: 0.8, up: 10 });
        for (const e of sk.state.enemiesInRange(u.pos.x, u.pos.z, 5, u.team)) {
          sk.dealSkillDamage(u, e, 300, 0.8, 'ad', 'ult');
          if (e.alive) e.addBuff({ type: 'knockup', dur: 1 });
        }
        u.addBuff({ type: 'shield', value: 500, dur: 3, group: 'niumo3' });
      },
    },
  },

  // ---------------- 兰陵王·刺客（近战） ----------------
  lanlingwang: {
    // 被动：脱战 3s 后隐身（攻击/受击破隐）——逻辑在 state.js 英雄更新中
    s1: {
      name: '秘技·分身', key: 'Q', cd: C.S1_CD, mana: C.S1_MANA, aim: 'self',
      // 召唤分身普攻 3 次（每次 60%AD）
      cast(sk, u) {
        sk.state.spawnClone(u);
        sk.state.vfx.burst(u.pos.x, 1.2, u.pos.z, { color: 0x66d0c8, count: 16, speed: 5, life: 0.4 });
      },
    },
    s2: {
      name: '秘技·影蚀', key: 'E', cd: C.S2_CD, mana: C.S2_MANA, aim: 'target', range: 9,
      // 掷匕 260+0.7AD + 标记：3s 内再受兰陵王伤害触发 180+0.5AD
      cast(sk, u, target) {
        sk.spawnProjectile({
          source: u, target, homing: true, speed: 24, range: 12,
          radius: 1.0, color: 0x66d0c8, size: 0.4,
          onHit: (e) => {
            sk.dealSkillDamage(u, e, 260, 0.7, 'ad', 's2');
            if (e.alive) {
              e.addBuff({ type: 'mark', dur: 3, source: u, group: 'lanling2' });
              sk.state.floatText(e, '标记', 'info');
            }
            return true;
          },
        });
      },
    },
    ult: {
      name: '秘技·暗袭', key: 'R', cd: C.ULT_CD, mana: C.ULT_MANA, aim: 'target', range: 9,
      // 突进至 9 内目标身后：400+1.2AD
      cast(sk, u, target) {
        sk.state.vfx.burst(u.pos.x, 1.2, u.pos.z, { color: 0x66d0c8, count: 14, speed: 5, life: 0.35 });
        // 目标身后 = 目标背对其朝向的位置
        const bx = target.pos.x - Math.sin(target.yaw) * (target.radius + 1.0);
        const bz = target.pos.z - Math.cos(target.yaw) * (target.radius + 1.0);
        u.pos.x = bx; u.pos.z = bz;
        u.face(target.pos.x, target.pos.z);
        u.syncModel();
        sk.dealSkillDamage(u, target, 400, 1.2, 'ad', 'ult');
        sk.state.vfx.burst(target.pos.x, 1.2, target.pos.z, { color: 0x40e0d0, count: 26, speed: 7, life: 0.5 });
      },
    },
  },
};

// ============================================================
export class SkillSystem {
  /** @param {GameState} state */
  constructor(state) {
    this.state = state;
    this.projectiles = [];   // 活跃弹道
    this._delayed = [];      // 延迟效果 {t, fn}
  }

  /** 技能 CD（大招随等级减；蓝BUFF -20% CD） */
  cdOf(unit, slot) {
    let cd;
    if (slot === 'ult') cd = C.ULT_CD - C.ULT_CD_PER_LEVEL * (unit.skillLevels.ult - 1);
    else cd = slot === 's1' ? C.S1_CD : C.S2_CD;
    if (unit.hasBuff && unit.hasBuff('blueBuff')) cd *= (1 - BUFFS.BLUE_CDR);
    return cd;
  }

  /** 是否可施放（已加点/CD/蓝耗/状态） */
  castable(unit, slot) {
    if (!unit || !unit.alive) return false;
    if (unit.skillLevels[slot] <= 0) return false;
    if (unit.cooldowns[slot] > 0) return false;
    if (!unit.canCast()) return false;
    const def = SKILLS[unit.heroId] && SKILLS[unit.heroId][slot];
    if (!def) return false;
    if (unit.mp < def.mana) return false;
    return true;
  }

  /**
   * 施放技能
   * @param {Unit} caster @param {string} slot 's1'|'s2'|'ult'
   * @param {object} opts { auto: 自动瞄准, target: 指定目标 }
   * @returns {boolean}
   */
  cast(caster, slot, opts = {}) {
    if (!this.castable(caster, slot)) return false;
    const def = SKILLS[caster.heroId][slot];

    // 目标解析（auto=点按自动瞄准最优目标；dir=拖动瞄准方向施放）
    let target = opts.target || null;
    if (!target && opts.dir && (def.aim === 'line' || def.aim === 'dash' || def.aim === 'area')) {
      // 方向施放：构造虚拟目标点（line/dash 取最大射程方向；area 取拖动距离钳制后的落点）
      const dcast = def.aim === 'area'
        ? Math.min(def.range || 10, opts.dir.dist || def.range || 10)
        : (def.range || 10);
      target = {
        pos: { x: caster.pos.x + opts.dir.x * dcast, z: caster.pos.z + opts.dir.z * dcast },
        radius: 0, alive: true,
      };
    }
    if (!target && def.range && (def.aim === 'target' || def.aim === 'area' || def.aim === 'line' || def.aim === 'dash')) {
      target = this.state.autoTarget(caster, def.range);
      if (!target && (def.aim === 'target' || def.aim === 'area')) return false;  // 必须指向目标的技能
    }
    if (def.aim === 'target' && target) {
      if (dist(caster.pos.x, caster.pos.z, target.pos.x, target.pos.z) > def.range + target.radius + 1) return false;
    }

    // 扣蓝 / 进 CD
    caster.mp -= def.mana;
    caster.cooldowns[slot] = this.cdOf(caster, slot);
    caster.skillCastCount[slot]++;
    caster.lastCombatT = this.state.time;
    if (caster.stealth) caster.removeBuff(caster.getBuff('stealth'));   // 施法破隐
    caster.revealUntil = this.state.time + BRUSH_CFG.REVEAL_ON_ATTACK;  // 草丛破隐 1s
    if (target) caster.face(target.pos.x, target.pos.z);

    def.cast(this, caster, target);
    this.state.events.emit('skillCast', { unit: caster, slot, name: def.name });
    if (caster.isPlayer) this.state.floatText(caster, def.name, 'skill');
    return true;
  }

  /** 技能伤害：基础×(1+12%×(技能等级-1)) + 比例×面板 */
  scale(unit, base, ratio, statKey, slot) {
    const lvl = unit.skillLevels[slot] || 1;
    return base * (1 + C.DMG_PER_LEVEL * (lvl - 1)) + ratio * (unit[statKey] || 0);
  }

  /** @param type 'ad'|'ap' 对应的抗性类型 */
  dealSkillDamage(source, target, base, ratio, statKey, slot, type = 'ad', extraFlat = 0) {
    const amount = this.scale(source, base, ratio, statKey, slot) + extraFlat;
    return this.state.dealDamage(source, target, amount, { type });
  }

  /** 延迟效果 */
  delay(t, fn) { this._delayed.push({ t, fn }); }

  /**
   * 生成弹道
   * cfg: { source, target(追踪), dir(直线), speed, range, radius, color, size,
   *        homing, pierceMinions, delay, onHit(unit, traveled)→bool 是否命中销毁 }
   */
  spawnProjectile(cfg) {
    const p = {
      ...cfg,
      x: cfg.source.pos.x, z: cfg.source.pos.z,
      y: 1.4,
      traveled: 0,
      delayT: cfg.delay || 0,
      dead: false,
      visual: null,
    };
    p.visual = this.state.vfx.tracer(cfg.color || 0xffffff, cfg.size || 0.4);
    this.projectiles.push(p);
    return p;
  }

  /** 固定步长更新：弹道 / Buff / 延迟效果 */
  update(dt) {
    const st = this.state;

    // ---- 延迟效果 ----
    for (let i = this._delayed.length - 1; i >= 0; i--) {
      const d = this._delayed[i];
      d.t -= dt;
      if (d.t <= 0) { this._delayed.splice(i, 1); d.fn(); }
    }

    // ---- 弹道 ----
    for (let i = this.projectiles.length - 1; i >= 0; i--) {
      const p = this.projectiles[i];
      if (p.delayT > 0) { p.delayT -= dt; continue; }
      let tx, tz;
      if (p.homing && p.target) {
        if (!p.target.alive) { this._killProjectile(i, p); continue; }
        tx = p.target.pos.x; tz = p.target.pos.z;
        const dx = tx - p.x, dz = tz - p.z;
        const len = Math.hypot(dx, dz) || 1;
        p.dir = { x: dx / len, z: dz / len };
      }
      const step = p.speed * dt;
      p.x += p.dir.x * step;
      p.z += p.dir.z * step;
      p.traveled += step;

      // 命中检测
      let hit = false;
      const foes = st.enemiesInRange(p.x, p.z, p.radius + 1.2, p.source.team, { structures: p.hitStructures });
      for (const e of foes) {
        if (dist(p.x, p.z, e.pos.x, e.pos.z) <= p.radius + e.radius) {
          const destroy = p.onHit(e, p.traveled);
          if (destroy !== false) { hit = true; break; }
        }
      }
      // 追踪型到点也算结束
      const arrived = p.homing && p.target &&
        dist(p.x, p.z, p.target.pos.x, p.target.pos.z) < 0.5;
      if (hit || arrived || (p.range && p.traveled >= p.range)) {
        if (!hit && arrived && p.target && p.target.alive) p.onHit(p.target, p.traveled);
        this._killProjectile(i, p);
        continue;
      }
      if (p.visual) p.visual.setPos(p.x, p.y, p.z);
    }

    // ---- Buff  Tick ----
    for (const u of st.units) {
      if (!u.alive) continue;
      for (let bi = u.buffs.length - 1; bi >= 0; bi--) {
        const b = u.buffs[bi];
        b.dur -= dt;
        let expired = b.dur <= 0;

        if (b.type === 'dot') {
          b.tickT -= dt;
          if (b.tickT <= 0) {
            b.tickT = b.interval;
            st.dealDamage(b.source, u, b.amount, { type: b.dmgType || 'ad' });
          }
        } else if (b.type === 'aura_dot') {
          // 亚瑟 S2：周期性对周围敌人造成伤害
          b.tickT -= dt;
          if (b.tickT <= 0) {
            b.tickT = b.tick;
            st.vfx.groundCircle(u.pos.x, u.pos.z, b.r, 0xffe080, 0.4);
            for (const e of st.enemiesInRange(u.pos.x, u.pos.z, b.r, u.team)) {
              st.dealDamage(u, e, b.amount, { type: 'ad' });
            }
          }
        } else if (b.type === 'foxfire') {
          // 妲己大招：间歇放出追踪狐火（优先英雄）
          b.tickT -= dt;
          if (b.tickT <= 0 && b.count > 0) {
            b.tickT = b.interval;
            b.count--;
            const foes = st.enemiesInRange(u.pos.x, u.pos.z, 10, u.team);
            if (foes.length) {
              const heroes = foes.filter(f => f.kind === 'hero');
              const pool = heroes.length ? heroes : foes;
              const target = pool[Math.floor(Math.random() * pool.length)];
              this.spawnProjectile({
                source: u, target, homing: true, speed: 20, range: 16,
                radius: 1.0, color: 0xff5ac0, size: 0.5,
                onHit: (e) => {
                  this.dealSkillDamage(u, e, 160, 0.45, 'ap', 'ult', 'ap');
                  st.vfx.burst(e.pos.x, 1.3, e.pos.z, { color: 0xff5ac0, count: 14, speed: 5, life: 0.4 });
                  return true;
                },
              });
            }
          }
          if (b.count <= 0) expired = true;
        } else if (b.type === 'dash') {
          // 牛魔 S2 冲锋：携单位前冲并击退路径敌人
          const step = Math.min(b.speed * dt, b.remaining);
          u.pos.x += b.dir.x * step;
          u.pos.z += b.dir.z * step;
          u.yaw = Math.atan2(b.dir.x, b.dir.z);
          b.remaining -= step;
          for (const e of st.enemiesInRange(u.pos.x, u.pos.z, 1.8, u.team)) {
            if (b.hitSet.has(e.id)) continue;
            b.hitSet.add(e.id);
            st.dealDamage(u, e, b.dmg, { type: 'ad' });
            if (e.alive) e.addBuff({ type: 'knockback', vx: b.dir.x * 12, vz: b.dir.z * 12, dur: 0.3 });
          }
          if (b.remaining <= 0) expired = true;
        } else if (b.type === 'knockback') {
          u.pos.x += b.vx * dt;
          u.pos.z += b.vz * dt;
        }

        if (expired) u.removeBuff(b);
      }
    }
  }

  _killProjectile(i, p) {
    if (p.visual) {
      this.state.vfx.burst(p.x, p.y, p.z, { color: p.color || 0xffffff, count: 8, speed: 3.5, life: 0.3 });
      p.visual.release();
    }
    this.projectiles.splice(i, 1);
  }
}
