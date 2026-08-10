// ============================================================
// 全局数值配置：地图 / 相机 / 英雄 / 配色
// 所有数值集中在此，业务代码禁止散落魔法数字
// ============================================================

export const TEAM = { BLUE: 'blue', RED: 'red' };

// 队伍主题色（模型/血条/水晶/泉水等）
export const TEAM_COLOR = {
  blue: { main: 0x3a6fd8, bright: 0x66aaff, dark: 0x27468a, ring: 0x4da6ff },
  red:  { main: 0xd84a3a, bright: 0xff7a66, dark: 0x8a2e24, ring: 0xff5a4d },
};

// ---------------- 地图 ----------------
export const MAP = {
  SIZE: 180,              // 世界 x,z ∈ [-90, 90]
  HALF: 90,
  PLAY_BOUND: 86,         // 单位可活动边界
  RIVER_WIDTH: 14,        // 河道宽（对角线 z = -x）
  RIVER_LEN: 262,         // 河面网格长度（略超出地图）
  GROUND_TEX: 2048,       // 地面 Canvas 纹理分辨率

  BLUE_BASE: { x: -72, z: -72 },
  RED_BASE:  { x: 72,  z: 72 },
  BASE_PLATFORM_R: 20,    // 基地石台半径
  WALL_R: 21,             // 基地围墙半径
  WALL_SEGMENTS: 26,      // 围墙分段数
  // 修复(p4)：出入口半角 13°→15°（对齐兵线实际穿越点后保证净宽约 10 单位，兵线/英雄顺畅进出）
  WALL_GAP_DEG: 15,       // 每个出入口半角（度）

  CRYSTAL_R: 3.2,         // 水晶核心碰撞/模型半径
  BLUE_FOUNTAIN: { x: -82, z: -82 },
  RED_FOUNTAIN:  { x: 82,  z: 82 },
  FOUNTAIN_R: 6,

  // 三条兵线（红方反向行走）
  // 修复(阶段3)：上/下路在边角塔位增设路径点，使兵线经过每座塔
  // 修复(p3)：上/下路再补红方拐角塔位路径点 [-58,80] / [80,-58]
  // （红方塔按河道镜像布置后，拐角塔距原路径 8.68 > 小兵索敌 8，永远够不到）
  LANES: {
    mid: [ [-70,-70], [0,0], [70,70] ],
    top: [ [-70,-70], [-80,-40], [-80,58], [-58,80], [40,80], [70,70] ],
    bot: [ [-70,-70], [-40,-80], [58,-80], [80,-58], [80,40], [70,70] ],
  },

  // 蓝方防御塔位：按攻击方遭遇顺序排列 = T1(外塔，离己方基地最远) → T3(高地塔)
  // 红方塔位由 map.js 沿河道(z=-x)镜像生成：(x,z)→(-z,-x)
  // 修复(p3)：原 top/bot 数组按"离基地近→远"排列导致 tier 颠倒，
  //   攻击方首遇的外塔被标 tier3 永久无敌；且红方中心对称取负会把上/下路塔
  //   映射到对方兵线，lane 标签与物理位置错乱
  TOWERS_BLUE: {
    mid: [ [-46,-46], [-28,-28], [-12,-12] ],
    top: [ [-80,58],  [-80,28],  [-80,-10] ],
    bot: [ [58,-80],  [28,-80],  [-10,-80] ],
  },
  TOWER_R: 2.4,           // 塔碰撞半径

  // 龙坑（河道对角线上）
  TYRANT_PIT:   { x: -22, z: 22 },   // 暴君坑（左上侧）
  OVERLORD_PIT: { x: 22,  z: -22 },  // 主宰坑（右下侧）
  PIT_R: 8,

  // 野区营地（蓝方视角，红方中心对称）
  JUNGLE_BLUE: {
    redBuff:  { x: -30, z: 10 },
    blueBuff: { x: 10,  z: -30 },
    small: [ { x: -58, z: -16 }, { x: -16, z: -58 } ],
  },
  CAMP_R: 5,

  // 草丛 14 片（2x4 规格，rot 为朝向弧度）
  BRUSHES: [
    { x: -36, z: 26,  rot: Math.PI / 4 }, { x: 36, z: -26, rot: Math.PI / 4 },
    { x: -16, z: 26,  rot: Math.PI / 4 }, { x: 16, z: -26, rot: Math.PI / 4 },
    { x: -4,  z: 14,  rot: Math.PI / 4 }, { x: 4,  z: -14, rot: Math.PI / 4 },
    { x: -80, z: 10,  rot: 0 },           { x: 80, z: -10, rot: 0 },
    { x: -55, z: 80,  rot: Math.PI / 2 }, { x: 55, z: -80, rot: Math.PI / 2 },
    { x: -10, z: -80, rot: 0 },           { x: 10, z: 80,  rot: 0 },
    { x: -80, z: -55, rot: Math.PI / 2 }, { x: 80, z: 55,  rot: Math.PI / 2 },
  ],
  BRUSH_W: 2, BRUSH_L: 4, BRUSH_H: 1.7,

  TREE_COUNT: 120,
  ROCK_COUNT: 46,
};

// ---------------- 基地围墙出入口（p5：英雄 AI 路点导航） ----------------
// 与 map.js 围墙缺口同一几何：兵线攻入本基地的最后一段与围墙圆的首次交点方向
function _baseGates(basePos) {
  const isBlue = basePos === MAP.BLUE_BASE;
  const gates = [];
  for (const key of ['mid', 'top', 'bot']) {
    const pts = MAP.LANES[key];
    const n = pts.length;
    // 进攻方抵达本基地前的最后一段（蓝方基地受攻方向=兵线反向 pts[1]→pts[0]；红方反之）
    const [ax, az] = isBlue ? pts[1] : pts[n - 2];
    const [cx, cz] = isBlue ? pts[0] : pts[n - 1];
    const dx = cx - ax, dz = cz - az;
    const fx = ax - basePos.x, fz = az - basePos.z;
    const A = dx * dx + dz * dz;
    const B = 2 * (fx * dx + fz * dz);
    const C = fx * fx + fz * fz - MAP.WALL_R * MAP.WALL_R;
    const disc = B * B - 4 * A * C;
    let ang;
    if (A < 1e-6 || disc <= 0) {
      ang = Math.atan2(az - basePos.z, ax - basePos.x);
    } else {
      const sq = Math.sqrt(disc);
      let t = (-B - sq) / (2 * A);
      if (t < 0 || t > 1) t = (-B + sq) / (2 * A);
      if (t < 0 || t > 1) t = 0;
      ang = Math.atan2(az + dz * t - basePos.z, ax + dx * t - basePos.x);
    }
    gates.push({ x: basePos.x + Math.cos(ang) * MAP.WALL_R, z: basePos.z + Math.sin(ang) * MAP.WALL_R });
  }
  return gates;
}
export const BASE_GATES = { blue: _baseGates(MAP.BLUE_BASE), red: _baseGates(MAP.RED_BASE) };

// ---------------- 相机 ----------------
export const CAMERA = {
  FOV: 45,
  NEAR: 1,
  FAR: 400,
  PITCH: 55 * Math.PI / 180,  // 俯仰角
  DIST: 30,                    // 与目标的距离
  YAW_DIR: { x: 1, z: 1 },     // 固定偏航：镜头由蓝方看向红方
  LERP: 6,                     // 平滑跟随速率（越大越紧）
  LOOK_AHEAD: 2.5,             // 朝移动方向的预瞄量
};

// ---------------- 英雄 ----------------
export const HERO = {
  RADIUS: 0.9,          // 碰撞半径
  MOVE_SPEED: 6.8,      // 基础移速 u/s（设计 6.4~7.2 区间）
  TURN_SPEED: 12,       // 转身速率 rad/s
  // 亚瑟 1 级面板（后续阶段扩展成长/技能）
  ARTHUR: { name: '亚瑟', hp: 3400, mp: 0, ad: 165, armor: 98, range: 2.6 },
};

// 玩家出生点（蓝方泉水）
export const SPAWN = { x: MAP.BLUE_FOUNTAIN.x, z: MAP.BLUE_FOUNTAIN.z };

// ---------------- 逻辑循环 ----------------
export const LOOP = {
  STEP: 1 / 30,   // 固定步长逻辑 30Hz
  MAX_SUB: 5,     // 单帧最大补步数
};

// demo 模式（?demo=1）：英雄沿中路自动行走时长
export const DEMO = { WALK_TIME: 20 };

// ============================================================
// 阶段2：战斗核心数值
// ============================================================

// ---------------- 5 名英雄（1 级面板 + 每级成长） ----------------
export const HEROES = {
  arthur: {
    name: '亚瑟', role: '战士', melee: true,
    hp: 3400, mp: 0, ad: 165, ap: 0, armor: 98, mres: 55,
    range: 2.6, speed: 6.8, aspeed: 0.95,
    growth: { hp: 280, mp: 0, ad: 13, ap: 0, armor: 15, mres: 8 },
  },
  houyi: {
    name: '后羿', role: '射手', melee: false,
    hp: 2950, mp: 480, ad: 178, ap: 0, armor: 80, mres: 50,
    range: 9.5, speed: 6.6, aspeed: 1.05,
    growth: { hp: 210, mp: 24, ad: 16, ap: 0, armor: 11, mres: 8 },
  },
  daji: {
    name: '妲己', role: '法师', melee: false,
    hp: 2850, mp: 560, ad: 120, ap: 40, armor: 78, mres: 50,
    range: 9, speed: 6.6, aspeed: 0.9,
    growth: { hp: 200, mp: 30, ad: 8, ap: 16, armor: 10, mres: 8 },
  },
  niumo: {
    name: '牛魔', role: '坦克', melee: true,
    hp: 3900, mp: 450, ad: 155, ap: 0, armor: 115, mres: 60,
    range: 2.6, speed: 6.9, aspeed: 0.85,
    growth: { hp: 330, mp: 22, ad: 11, ap: 0, armor: 17, mres: 10 },
  },
  lanlingwang: {
    name: '兰陵王', role: '刺客', melee: true,
    hp: 3050, mp: 500, ad: 188, ap: 0, armor: 85, mres: 50,
    range: 2.6, speed: 7.2, aspeed: 1.0,
    growth: { hp: 230, mp: 26, ad: 15, ap: 0, armor: 12, mres: 8 },
  },
};

// 自动加点顺序（等级 2..15 对应下标 0..13；1 级自带 S1，4/8/12 点大招）
export const SKILL_ORDER = ['s2', 's1', 'ult', 's2', 's1', 's2', 'ult', 's1', 's2', 's1', 'ult', 's2', 's1', 's2'];

// 技能公共数值
export const SKILL_COMMON = {
  S1_CD: 8, S2_CD: 10, ULT_CD: 40,
  ULT_CD_PER_LEVEL: 3,      // 大招每级 -3s CD
  DMG_PER_LEVEL: 0.12,      // 技能基础伤害每级 +12%
  S1_MANA: 50, S2_MANA: 60, ULT_MANA: 100,
};

// ---------------- 小兵 ----------------
export const MINIONS = {
  melee:  { name: '刀兵', hp: 1300, ad: 42,  armor: 40, mres: 20, range: 2,  aspeed: 0.9, speed: 5.2, radius: 0.7, gold: 42, exp: 45 },
  mage:   { name: '法师兵', hp: 850, ad: 65,  armor: 25, mres: 20, range: 9,  aspeed: 0.8, speed: 5.2, radius: 0.7, gold: 36, exp: 40 },
  cannon: { name: '炮车', hp: 2600, ad: 130, armor: 60, mres: 30, range: 10, aspeed: 0.7, speed: 5.0, radius: 1.2, gold: 90, exp: 60,
            towerMult: 2 },   // 对塔 2 倍伤害
  // 终局(p4)：一路 3 塔全破后该路每波附加 1 个超级兵（HP/AD ≈ 炮车 1.5 倍，优先打塔和水晶）
  super:  { name: '超级兵', hp: 3900, ad: 195, armor: 90, mres: 45, range: 2.4, aspeed: 0.85, speed: 5.4, radius: 1.0, gold: 120, exp: 80,
            towerMult: 2 },
};
// 终局(p4)：12:00 后小兵属性随时间成长（每分钟 +8% HP/AD，按出生时刻连续计算）
export const MINION_GROWTH = { START: 720, RATE: 0.08 };
// 终局(p4)：小兵路径走完直取水晶时的自卫半径（内有敌人先反击，否则锁定水晶）
export const MINION_SELF_DEFEND_R = 3.5;
export const MINION_AGGRO_R = 8;    // 小兵发现敌人范围
export const MINION_LEASH_R = 14;   // 小兵放弃追击范围
// 修复(p3)：路径点到达判定放宽（原 2.2 < 塔碰撞推出半径 3.4，
//   与塔位重合的路径点永远不可达 → 拐角集体卡死）
export const MINION_WP_ARRIVE_R = 7;    // 路径点到达判定半径
export const MINION_LANE_OFFSET = 2.5;  // 小兵沿兵线横向随机偏移上限（出生固定，避免同轨叠罗汉）
export const MINION_STUCK_T = 3;        // 卡死检测窗口(s)：窗口内位移 <阈值 视为卡住
export const MINION_STUCK_DIST = 1;     // 卡死位移阈值

// 出兵波次
export const WAVE = {
  FIRST: 10,          // 首波 t=10s
  INTERVAL: 30,       // 之后每 30s
  MELEE: 3, MAGE: 2,  // 每波 3 近战 + 2 法师
  CANNON_EVERY: 3,    // 每第 3 波加 1 炮车
};

// ---------------- 防御塔 / 水晶 / 泉水 ----------------
export const TOWER_CFG = {
  HP: 6500, AD: 320, RANGE: 12, ASPEED: 0.8, ARMOR: 120, MRES: 120,
  RADIUS: 2.4,
  COMBO_STEP: 0.3, COMBO_MAX: 3,   // 对同一英雄连击 +30%/次，最多 +90%
  HERO_AGGRO_T: 2.5,               // 敌英雄攻击我方英雄后转火窗口(s)
};
export const CRYSTAL_CFG = {
  HP: 10000, ARMOR: 100, MRES: 100, REGEN: 0.01, RADIUS: 4.2,
  // 终局(p5)：残存防御塔为水晶供能——每座存活塔使水晶受伤 -8%（下限 30%）
  // （替代 p4 固定 ×0.1：与"≤3 塔或一路全破即解防"规则冲突——解防后伤害仍被
  //   回血 1%/s 抵消，实测比赛无法终结；梯度减伤保留塔先水晶后的终局顺序，
  //   同时保证多人集火/超级兵可以在解防后实际推掉水晶）
  TOWER_GUARD_PER: 0.08, TOWER_GUARD_MIN: 0.3,
};
export const FOUNTAIN = {
  R: 6, HEAL_PCT: 0.2,      // 每秒回 20% 血蓝
  ENEMY_DPS: 2000,          // 敌方进泉水每秒 2000 真实伤害
};

// ---------------- 经济 / 经验 ----------------
export const ECON = {
  PASSIVE_GOLD_START: 120,  // 被动金币从 2:00 起
  PASSIVE_GOLD_RATE: 2,     // 2 金/s
  HERO_KILL_GOLD: 200,
  ASSIST_GOLD: 80,
  TOWER_TEAM_GOLD: 200,     // 破塔全队 +200
  EXP_SHARE_R: 14,          // 经验共享半径
  HERO_KILL_EXP: 200,
};

// 升级经验表：EXP_TABLE[lvl] = lvl → lvl+1 所需经验（1..14）
export const EXP_TABLE = [100, 160, 220, 280, 340, 400, 460, 520, 580, 640, 700, 760, 820, 880];
export const MAX_LEVEL = 15;

// 重生 / 回城
export const RESPAWN = { BASE: 8, PER_LEVEL: 2 };   // 8s + 2s×等级
export const RECALL_CFG = { CHANNEL: 8, CD: 8 };    // 引导 8s，被打断 CD 8s

// ============================================================
// 阶段3：英雄 AI / 野区 / 草丛 / 召唤师技能 / 播报
// ============================================================

// ---------------- 野怪 ----------------
export const MONSTERS = {
  redBuff:  { name: '猩红石像', hp: 4000, ad: 90,  armor: 60, mres: 40, range: 2.2, aspeed: 0.8, speed: 4.5, radius: 1.3, gold: 90,  exp: 90 },
  blueBuff: { name: '蔚蓝石像', hp: 4000, ad: 90,  armor: 60, mres: 40, range: 2.2, aspeed: 0.8, speed: 4.5, radius: 1.3, gold: 90,  exp: 90 },
  small:    { name: '野怪',     hp: 2200, ad: 60,  armor: 30, mres: 20, range: 2.0, aspeed: 0.9, speed: 4.8, radius: 0.9, gold: 55,  exp: 60 },
  tyrant:   { name: '暴君',     hp: 9000, ad: 150, armor: 90, mres: 70, range: 3.0, aspeed: 0.7, speed: 3.6, radius: 2.2, gold: 150, exp: 120 },
  overlord: { name: '主宰',     hp: 13000, ad: 200, armor: 110, mres: 90, range: 3.2, aspeed: 0.65, speed: 3.4, radius: 2.6, gold: 200, exp: 160 },
};
export const JUNGLE = {
  RESPAWN: 60,          // 普通营地清空后 60s 刷新
  LEASH_R: 9,           // 离营超过此距离脱战回营
  AGGRO_R: 3.5,         // 野怪只主动攻击 3.5 范围内敌人
  CAMP_ARRIVE_R: 2.0,   // 回营判定半径（到达即满血）
  TYRANT_FIRST: 480,    // 暴君 8:00 首刷
  TYRANT_RESPAWN: 180,  // 暴君击杀后 3:00 刷新
  OVERLORD_FIRST: 600,  // 主宰 10:00 首刷
  OVERLORD_RESPAWN: 240,// 主宰击杀后 4:00 刷新（设计文档未给，取常规值）
  TYRANT_TEAM_GOLD: 150,// 暴君全队 +150 金
  TYRANT_TEAM_EXP: 100, // 暴君全队经验
  OVERLORD_WAVE_MULT: 1.8, // 主宰先锋：下波兵属性 ×1.8
};

// ---------------- BUFF 效果 ----------------
export const BUFFS = {
  RED_DUR: 60,            // 红BUFF：灼烧+减速 60s
  RED_SLOW: 0.15,         // 减速 15%
  RED_SLOW_DUR: 1.2,      // 单次减速时长
  RED_BURN_BASE: 40,      // 灼烧 40+0.2AD（2s 内跳完）
  RED_BURN_AD: 0.2,
  RED_BURN_TICKS: 2,
  BLUE_DUR: 60,           // 蓝BUFF：CDR+回蓝 60s
  BLUE_CDR: 0.2,          // 冷却缩减 20%
  BLUE_MP_REGEN: 0.03,    // 每秒回蓝 3% 最大蓝量
};

// ---------------- 召唤师技能：恢复 / 闪现 ----------------
export const HEAL_CFG = { CD: 90, DUR: 8, PCT: 0.25 };   // 8s 回 25% 血，CD 90s
export const FLASH_CFG = { CD: 120, DIST: 8 };           // 闪现：位移 8，CD 120s（仅玩家）

// ---------------- 草丛隐身 ----------------
export const BRUSH_CFG = {
  MARGIN: 0.6,        // 草丛判定外扩（贴合单位半径）
  REVEAL_ON_ATTACK: 1,  // 攻击行为破隐 1s
  SAME_BRUSH_R: 4.2,    // 同草丛内敌方互相显形半径
};

// ---------------- 英雄 AI 参数 ----------------
export const AI_CFG = {
  RETREAT_HP: 0.30,       // HP<30% 撤退
  RETURN_HP: 0.85,        // 泉水恢复到 85% 返回战线
  DEFEND_RANGE: 40,       // 己方塔/水晶被攻且自己在 40 内回防
  DEFEND_RECENT: 4,       // 塔最近 4s 内被攻击视为"被攻"
  FIGHT_R: 15,            // 团战判定半径
  FIGHT_HEROES: 3,        // ≥3 英雄交战
  FIGHT_RECENT: 4,        // 4s 内发生过战斗
  FIGHT_JOIN_R: 55,       // 距离团战 55 内前往支援
  HERO_WEIGHT: 1.5,       // 英雄优先权重（等效距离 ÷1.5）
  ENGAGE_R: 12,           // 对线索敌半径（额外叠加普攻射程）
  TOWER_COVER_R: 10,      // 己方小兵进塔判定（塔周围 10 内有己方小兵）
  RECALL_SAFE_R: 14,      // 此半径内无敌人才引导回城
  HEAL_HP: 0.5,           // HP<50% 使用恢复
  OBJECTIVE_HP: 0.55,     // 打龙要求自身血量高于 55%
  OBJECTIVE_SAFE_R: 14,   // 龙坑此半径内无敌方英雄才开龙
  BUY_MAX_PER_VISIT: 6,   // 每次回泉水最多尝试购买件数
  // 终局(p4)：GROUP_PUSH——敌方水晶已解防且己方存活英雄 ≥此数 → 集合推水晶
  // (p5：3→2，终局减员常态下仍能组织推进，避免双方互相清线无限僵持)
  GROUP_PUSH_MIN: 2,
};

// ---------------- 击杀播报 ----------------
export const ANNOUNCE = {
  MULTI_WINDOW: 8,        // 多杀窗口：8s 内连续击杀
  ACE_WINDOW: 10,         // 团灭判定：10s 内对方 5 人全部阵亡
  ACE_CD: 20,             // 团灭播报冷却
  NAMES: {
    firstBlood: '第一滴血', doubleKill: '双杀', tripleKill: '三连决胜',
    quadraKill: '四连超凡', pentaKill: '五连绝世', ace: '团灭', godlike: '超神',
  },
};

// 分路角色（每方 1 上 1 中 2 下 1 野）
export const ROLES = ['top', 'mid', 'bot', 'bot2', 'jungle'];
// 打野位英雄优先级（适合打野的排前面）
export const JUNGLE_PREF = ['lanlingwang', 'niumo', 'arthur', 'houyi', 'daji'];

// 暴击（默认 200% 伤害）
export const CRIT = { MULT: 2 };
