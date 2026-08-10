// ============================================================
// 商店系统（阶段3：装备数据 + 购买逻辑 + 推荐出装；商店 UI 在第4阶段）
// 12 件装备，6 格装备栏；AI 回泉水自动按推荐购买；玩家经 UI 一键购买（阶段4）
// ============================================================

/** 装备数据表（价格/属性按设计文档） */
export const ITEMS = {
  ironSword:   { name: '铁剑',       price: 300,  ad: 25 },
  dagger:      { name: '攻速匕',     price: 400,  aspeedPct: 0.25 },
  tome:        { name: '法典',       price: 500,  ap: 40 },
  clothArmor:  { name: '布甲',       price: 300,  armor: 25 },
  mantle:      { name: '抗魔斗篷',   price: 300,  mres: 25 },
  boots:       { name: '神速之靴',   price: 300,  speed: 0.8 },
  pojun:       { name: '破军',       price: 2200, ad: 180 },
  wujin:       { name: '无尽战刃',   price: 2100, ad: 120, crit: 0.25 },
  sageWrath:   { name: '博学者之怒', price: 2300, ap: 240 },
  honglian:    { name: '红莲斗篷',   price: 1800, armor: 120, hp: 800 },
  monv:        { name: '魔女斗篷',   price: 1800, mres: 120, hp: 800 },
  bazhe:       { name: '霸者重装',   price: 2000, hp: 1500, regenPct: 0.05 },
};

/** 推荐出装（每英雄预设 6 件，按购买顺序） */
export const RECOMMENDED = {
  arthur:      ['boots', 'ironSword', 'honglian', 'pojun', 'monv', 'bazhe'],
  houyi:       ['boots', 'dagger', 'wujin', 'pojun', 'ironSword', 'honglian'],
  daji:        ['boots', 'tome', 'sageWrath', 'monv', 'tome', 'bazhe'],
  niumo:       ['boots', 'clothArmor', 'honglian', 'monv', 'bazhe', 'mantle'],
  lanlingwang: ['boots', 'ironSword', 'wujin', 'pojun', 'dagger', 'monv'],
};

/** 装备 UI 风格（图标字 + 配色，阶段4 HUD 装备栏/商店面板用） */
export const ITEM_STYLE = {
  ironSword:   { color: '#b07a3a', icon: '剑' },
  dagger:      { color: '#b0a040', icon: '匕' },
  tome:        { color: '#7a5ac8', icon: '典' },
  clothArmor:  { color: '#8a8a5a', icon: '甲' },
  mantle:      { color: '#5a8ac8', icon: '篷' },
  boots:       { color: '#5aa86a', icon: '靴' },
  pojun:       { color: '#d84a3a', icon: '破' },
  wujin:       { color: '#e0a020', icon: '尽' },
  sageWrath:   { color: '#a050e0', icon: '怒' },
  honglian:    { color: '#d86030', icon: '莲' },
  monv:        { color: '#8040c0', icon: '魔' },
  bazhe:       { color: '#c03050', icon: '霸' },
};

/** 装备属性一句话描述（商店面板用） */
export function itemAttrText(item) {
  const parts = [];
  if (item.ad) parts.push(`+${item.ad} 攻击`);
  if (item.ap) parts.push(`+${item.ap} 法强`);
  if (item.armor) parts.push(`+${item.armor} 护甲`);
  if (item.mres) parts.push(`+${item.mres} 魔抗`);
  if (item.hp) parts.push(`+${item.hp} 生命`);
  if (item.speed) parts.push(`+${item.speed} 移速`);
  if (item.aspeedPct) parts.push(`+${Math.round(item.aspeedPct * 100)}% 攻速`);
  if (item.crit) parts.push(`+${Math.round(item.crit * 100)}% 暴击`);
  if (item.regenPct) parts.push(`+${Math.round(item.regenPct * 100)}%/s 回血`);
  return parts.join('  ');
}

export class Shop {
  /** @param {Unit} hero */
  constructor(hero) {
    this.hero = hero;
    this.slots = new Array(6).fill(null);   // 6 格装备栏（存 itemId）
  }

  /** 已有装备数 */
  get count() { return this.slots.filter(Boolean).length; }

  /** 是否已拥有某装备 */
  has(itemId) { return this.slots.includes(itemId); }

  /**
   * 购买装备
   * @param {string} itemId
   * @returns {boolean} 是否成功
   */
  buy(itemId) {
    const item = ITEMS[itemId];
    const hero = this.hero;
    if (!item || !hero || !hero.alive) return false;
    const slot = this.slots.indexOf(null);
    if (slot < 0) return false;                    // 装备栏已满
    if (hero.gold < item.price) return false;      // 金币不足
    hero.gold -= item.price;
    this.slots[slot] = itemId;
    this._apply(item);
    hero._state && hero._state.events.emit('itemBought', { unit: hero, itemId, item });
    return true;
  }

  /** 按推荐出装购买下一件 @returns {boolean} 是否买到 */
  buyRecommended() {
    const rec = RECOMMENDED[this.hero.heroId] || RECOMMENDED.arthur;
    for (const itemId of rec) {
      if (this.has(itemId)) continue;
      return this.buy(itemId);
    }
    return false;   // 推荐已买完
  }

  /** 应用装备属性到英雄面板 */
  _apply(item) {
    const h = this.hero;
    if (item.ad) h.ad += item.ad;
    if (item.ap) h.ap += item.ap;
    if (item.armor) h.armor += item.armor;
    if (item.mres) h.mres += item.mres;
    if (item.hp) { h.maxHp += item.hp; h.hp += item.hp; }
    if (item.speed) h.baseSpeed += item.speed;
    if (item.aspeedPct) h.aspeed *= (1 + item.aspeedPct);
    if (item.crit) h.crit = (h.crit || 0) + item.crit;
    if (item.regenPct) h.itemRegenPct = (h.itemRegenPct || 0) + item.regenPct;
  }
}
