// ============================================================
// 刷兵/重生/经济系统（阶段2）
// 小兵波次：首波 t=10s，之后每 30s；3 近战+2 法师，每第 3 波+1 炮车；三路同时
// 英雄重生计时：8s + 2s×等级
// 被动金币：2/s 从 2:00 起
// 阶段3：野怪营地（红/蓝BUFF+小野，双方对称，清空 60s 刷新）
//        暴君 8:00 首刷（击杀后 3:00 刷新）、主宰 10:00 首刷（击杀后 4:00 刷新）
//        主宰击杀方三路下一波兵强化为主宰先锋（属性 ×1.8）
// ============================================================
import { TEAM, WAVE, ECON, MAP, JUNGLE } from '../config.js';

export class Spawner {
  /** @param {GameState} state */
  constructor(state) {
    this.state = state;
    this.waveCount = 0;
    this.nextWaveAt = WAVE.FIRST;

    // ---- 野怪营地表（双方对称 + 中立龙坑） ----
    // camp: { id, team('blue'|'red'|null 中立), type, x, z, count, respawn,
    //         first(首刷时刻), respawnAt, units:[Unit] }
    this.camps = [];
    const JB = MAP.JUNGLE_BLUE;
    for (const [team, s] of [[TEAM.BLUE, 1], [TEAM.RED, -1]]) {
      this.camps.push(
        { id: `${team}.redBuff`, team, type: 'redBuff', x: JB.redBuff.x * s, z: JB.redBuff.z * s, count: 1, respawn: JUNGLE.RESPAWN, first: 0 },
        { id: `${team}.blueBuff`, team, type: 'blueBuff', x: JB.blueBuff.x * s, z: JB.blueBuff.z * s, count: 1, respawn: JUNGLE.RESPAWN, first: 0 },
      );
      JB.small.forEach((p, i) => {
        this.camps.push({ id: `${team}.small${i + 1}`, team, type: 'small', x: p.x * s, z: p.z * s, count: 2, respawn: JUNGLE.RESPAWN, first: 0 });
      });
    }
    this.camps.push(
      { id: 'tyrant', team: null, type: 'tyrant', x: MAP.TYRANT_PIT.x, z: MAP.TYRANT_PIT.z, count: 1, respawn: JUNGLE.TYRANT_RESPAWN, first: JUNGLE.TYRANT_FIRST },
      { id: 'overlord', team: null, type: 'overlord', x: MAP.OVERLORD_PIT.x, z: MAP.OVERLORD_PIT.z, count: 1, respawn: JUNGLE.OVERLORD_RESPAWN, first: JUNGLE.OVERLORD_FIRST },
    );
    for (const c of this.camps) {
      c.units = [];
      c.respawnAt = c.first;   // 首刷时刻（普通营地为 0）
    }
    // 龙坑快捷引用（AI 打龙判断用）
    this.objectives = this.camps.filter(c => c.id === 'tyrant' || c.id === 'overlord');

    // 主宰强化波标记：overlordWave[team]=true → 该方下一波兵 ×1.8
    this.overlordWave = { blue: false, red: false };
  }

  /** 固定步长更新：按时间轴出兵/复活/被动金币/野怪刷新 */
  update(dt) {
    const s = this.state;

    // ---- 出兵 ----
    if (s.time >= this.nextWaveAt) {
      this.waveCount++;
      this.spawnWave(this.waveCount);
      this.nextWaveAt = WAVE.FIRST + this.waveCount * WAVE.INTERVAL;
    }

    // ---- 野怪刷新 ----
    for (const camp of this.camps) {
      if (camp.units.some(u => u.alive)) continue;       // 营地有活怪
      if (camp.respawnAt == null || s.time < camp.respawnAt) continue;
      this.spawnCamp(camp);
    }

    // ---- 英雄重生 ----
    for (const u of s.units) {
      if (u.kind === 'hero' && !u.alive && u.respawnAt != null && s.time >= u.respawnAt) {
        s.respawnHero(u);
      }
    }

    // ---- 被动金币（2/s，2:00 起） ----
    if (s.time >= ECON.PASSIVE_GOLD_START) {
      for (const u of s.units) {
        if (u.kind !== 'hero' || !u.alive) continue;
        u._goldAcc += ECON.PASSIVE_GOLD_RATE * dt;
        if (u._goldAcc >= 1) {
          const g = Math.floor(u._goldAcc);
          u._goldAcc -= g;
          u.gold += g;
        }
      }
    }
  }

  /** 一波兵：双方三路同时（主宰击杀方本波强化 ×1.8；破一路后该路每波+1超级兵） */
  spawnWave(n) {
    const hasCannon = n % WAVE.CANNON_EVERY === 0;
    for (const team of [TEAM.BLUE, TEAM.RED]) {
      const empowered = this.overlordWave[team];
      const spawned = [];
      for (const lane of ['mid', 'top', 'bot']) {
        // 阵型：近战前排横排，法师后排，炮车居中
        const formation = [];
        for (let i = 0; i < WAVE.MELEE; i++) formation.push({ type: 'melee', o: (i - 1) * 2.2, back: 0 });
        for (let i = 0; i < WAVE.MAGE; i++) formation.push({ type: 'mage', o: (i - 0.5) * 2.2, back: -2.6 });
        if (hasCannon) formation.push({ type: 'cannon', o: 0, back: -4.6 });
        // 终局(p4)：该路敌方 3 塔全破 → 每波附加 1 个超级兵（优先打塔和水晶）
        if (this._laneCleared(team, lane)) formation.push({ type: 'super', o: 0, back: -6.4 });
        for (const f of formation) {
          spawned.push(this._spawnAt(this.state, team, lane, f.type, f.o, f.back));
        }
      }
      if (empowered) {
        this.overlordWave[team] = false;
        for (const u of spawned) this._empowerMinion(u);
        console.debug(`[野区] ${team} 主宰先锋出击（本波兵 ×${JUNGLE.OVERLORD_WAVE_MULT}）`);
      }
    }
  }

  /** 终局(p4)：某路敌方 3 塔是否已全破（是 → 该路出兵附加超级兵） */
  _laneCleared(team, lane) {
    const enemy = team === TEAM.BLUE ? TEAM.RED : TEAM.BLUE;
    return !this.state.towerUnits.some(t => t.team === enemy && t.lane === lane && t.alive);
  }

  /** 主宰先锋：属性 ×1.8 + 体型放大 */
  _empowerMinion(u) {
    const k = JUNGLE.OVERLORD_WAVE_MULT;
    u.maxHp = Math.round(u.maxHp * k);
    u.hp = u.maxHp;
    u.ad = Math.round(u.ad * k);
    u.armor = Math.round(u.armor * k);
    u.mres = Math.round(u.mres * k);
    u.overlordBuffed = true;
    if (u.model) u.model.scale.multiplyScalar(1.3);
  }

  /** 在兵线路径起点附近生成（o=横向偏移，back=沿路径反方向偏移） */
  _spawnAt(state, team, lane, type, side, back) {
    // 取路径前两点计算朝向（红方反向）
    const raw = state.mapLanes[lane];
    const path = team === TEAM.BLUE ? raw : raw.slice().reverse();
    const [x0, z0] = path[0];
    const [x1, z1] = path[1];
    let dx = x1 - x0, dz = z1 - z0;
    const len = Math.hypot(dx, dz) || 1;
    dx /= len; dz /= len;
    // 垂直方向（右侧）
    const px = -dz, pz = dx;
    const ox = px * side + dx * back;
    const oz = pz * side + dz * back;
    return state.spawnMinion(team, lane, type, ox, oz);
  }

  /** 刷一个野怪营地 */
  spawnCamp(camp) {
    camp.respawnAt = null;
    for (let i = 0; i < camp.count; i++) {
      // 多怪营地横向排开
      const ox = camp.count > 1 ? (i - (camp.count - 1) / 2) * 2.4 : 0;
      const u = this.state.spawnMonster(camp.type, camp.x + ox, camp.z + (camp.count > 1 ? 0.8 : 0), camp);
      camp.units.push(u);
    }
  }
}
