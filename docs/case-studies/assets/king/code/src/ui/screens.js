// ============================================================
// 界面系统（阶段4）
//   选英雄界面：5 张英雄卡（程序化 canvas 头像/名称/定位/3 技能一句话简介），
//     点击选中高亮，"锁定"进入游戏
//   中央大横幅：第一滴血/双杀/三连决胜/四连超凡/五连绝世/超神/团灭/
//     防御塔摧毁/暴君主宰提示（缩放淡入→停留→淡出），配 TTS 播报 + 合成音效
//   右上击杀播报条：谁杀了谁（头像色块+名字，4s 消失，最多 4 条）
//   商店面板：装备列表/金币/点击购买/一键推荐出装（面板打开游戏继续）
//   结算界面：胜利/失败大标题、比分、KDA/补刀/时长、双方英雄 KDA 简表、再来一局
// ============================================================
import { HEROES, TEAM } from '../config.js';
import { SKILLS } from '../game/skills.js';
import { ITEMS, ITEM_STYLE, RECOMMENDED, itemAttrText } from '../game/shop.js';

// ---------------- 英雄卡片数据（头像配色 + 技能一句话简介） ----------------
const HERO_CARD_META = {
  arthur:      { c1: '#2a4a8a', c2: '#e8c860', tag: '近战 / 强化普攻',
                 desc: { s1: '加速并强化下次普攻附加沉默', s2: '旋剑持续伤害周围敌人', ult: '跃向目标造成斩杀伤害' } },
  houyi:       { c1: '#8a2e1e', c2: '#ffb040', tag: '远程 / 持续输出',
                 desc: { s1: '4 秒内普攻三连发', s2: '区域箭雨伤害并减速', ult: '超远狙击箭命中眩晕' } },
  daji:        { c1: '#5a2a7a', c2: '#ff7ad0', tag: '远程 / 控制爆发',
                 desc: { s1: '灵魂法球直线伤害', s2: '爱心命中眩晕 1.2 秒', ult: '五团狐火自动追踪敌人' } },
  niumo:       { c1: '#5a3a22', c2: '#d0a060', tag: '近战 / 团控护盾',
                 desc: { s1: '横扫伤害并减速', s2: '冲锋击退路径敌人', ult: '范围击飞并获得护盾' } },
  lanlingwang: { c1: '#143a3a', c2: '#66d0c8', tag: '近战 / 隐身刺杀',
                 desc: { s1: '召唤分身协同攻击', s2: '掷匕标记二次爆发', ult: '突进至目标身后重击' } },
};

/** 程序化头像绘制（几何脸谱/武器图标，96×96） */
function drawAvatar(cv, heroId) {
  const g = cv.getContext('2d');
  const m = HERO_CARD_META[heroId];
  const W = cv.width, H = cv.height;
  // 底色渐变 + 边框高光
  const bg = g.createLinearGradient(0, 0, W, H);
  bg.addColorStop(0, m.c1); bg.addColorStop(1, '#0c1018');
  g.fillStyle = bg; g.fillRect(0, 0, W, H);
  g.strokeStyle = m.c2; g.lineWidth = 3;
  g.beginPath(); g.arc(W / 2, H / 2, 40, 0, Math.PI * 2); g.stroke();
  g.save();
  g.translate(W / 2, H / 2);
  g.strokeStyle = m.c2; g.fillStyle = m.c2; g.lineWidth = 4;
  g.lineJoin = g.lineCap = 'round';
  switch (heroId) {
    case 'arthur':   // 剑盾
      g.beginPath(); g.moveTo(0, -26); g.lineTo(18, -14); g.lineTo(14, 16); g.lineTo(0, 26); g.lineTo(-14, 16); g.lineTo(-18, -14); g.closePath(); g.stroke();
      g.beginPath(); g.moveTo(0, -30); g.lineTo(0, 22); g.moveTo(-8, -16); g.lineTo(8, -16); g.stroke();
      break;
    case 'houyi':    // 弓与日
      g.beginPath(); g.arc(0, 0, 12, 0, Math.PI * 2); g.fill();
      g.beginPath(); g.arc(2, 0, 26, -1.2, 1.2); g.stroke();
      g.beginPath(); g.moveTo(-24, 0); g.lineTo(30, 0); g.moveTo(22, -6); g.lineTo(30, 0); g.lineTo(22, 6); g.stroke();
      break;
    case 'daji':     // 狐面
      g.beginPath(); g.moveTo(-20, -8); g.lineTo(-26, -30); g.lineTo(-6, -18); g.closePath(); g.fill();
      g.beginPath(); g.moveTo(20, -8); g.lineTo(26, -30); g.lineTo(6, -18); g.closePath(); g.fill();
      g.beginPath(); g.arc(0, 4, 20, 0, Math.PI * 2); g.stroke();
      g.fillStyle = '#0c1018';
      g.beginPath(); g.arc(-8, 2, 3, 0, Math.PI * 2); g.fill();
      g.beginPath(); g.arc(8, 2, 3, 0, Math.PI * 2); g.fill();
      g.fillStyle = m.c2;
      g.beginPath(); g.moveTo(-5, 14); g.lineTo(5, 14); g.lineTo(0, 20); g.closePath(); g.fill();
      break;
    case 'niumo':    // 牛角
      g.beginPath(); g.arc(0, 6, 20, 0, Math.PI * 2); g.stroke();
      g.beginPath(); g.arc(-22, -10, 12, 1.2, 4.6); g.stroke();
      g.beginPath(); g.arc(22, -10, 12, -1.5, 1.9); g.stroke();
      g.beginPath(); g.arc(0, 12, 6, 0, Math.PI * 2); g.stroke();
      break;
    case 'lanlingwang': // 面具+匕首
      g.beginPath(); g.moveTo(-18, -12); g.lineTo(18, -12); g.lineTo(12, 10); g.lineTo(0, 18); g.lineTo(-12, 10); g.closePath(); g.stroke();
      g.fillStyle = '#0c1018';
      g.beginPath(); g.moveTo(-12, -4); g.lineTo(-2, -4); g.lineTo(-7, 2); g.closePath(); g.fill();
      g.beginPath(); g.moveTo(12, -4); g.lineTo(2, -4); g.lineTo(7, 2); g.closePath(); g.fill();
      g.strokeStyle = m.c2;
      g.beginPath(); g.moveTo(14, 12); g.lineTo(30, 30); g.moveTo(12, 16); g.lineTo(18, 10); g.stroke();
      break;
  }
  g.restore();
}

// ---------------- 播报文案 ----------------
const BANNER_TEXT = {
  firstBlood: '第一滴血！', doubleKill: '双杀！', tripleKill: '三连决胜！',
  quadraKill: '四连超凡！', pentaKill: '五连绝世！', godlike: '超神！', ace: '团灭！',
};

export class Screens {
  /** @param {HTMLElement} root UI 根节点 */
  constructor(root) {
    this.root = root;
    this._state = null;
    this._audio = null;
    this._shopEl = null;
    this._shopRefreshT = null;
    this._kfCount = 0;        // 自检：累计播报条数
    this._bannerCount = 0;    // 自检：累计横幅数

    // ---- 中央大横幅 ----
    this.banner = document.createElement('div');
    this.banner.id = 'bigbanner';
    this.banner.style.display = 'none';
    root.appendChild(this.banner);

    // ---- 右上击杀播报条 ----
    this.killfeed = document.createElement('div');
    this.killfeed.id = 'killfeed';
    root.appendChild(this.killfeed);
  }

  // ================= 选英雄界面 =================
  /** @param {Function} onLock 锁定回调(heroId) */
  showHeroSelect(onLock) {
    const wrap = document.createElement('div');
    wrap.id = 'hero-select';
    wrap.setAttribute('data-ui', '1');
    wrap.innerHTML = '<div class="hs-title">选择你的英雄</div><div class="hs-row"></div>' +
      '<div class="hs-lock disabled">锁 定</div>';
    const row = wrap.querySelector('.hs-row');
    const lockBtn = wrap.querySelector('.hs-lock');
    let selected = null;

    for (const heroId of Object.keys(HEROES)) {
      const cfg = HEROES[heroId];
      const meta = HERO_CARD_META[heroId];
      const defs = SKILLS[heroId];
      const card = document.createElement('div');
      card.className = 'hs-card';
      card.dataset.hero = heroId;

      const cv = document.createElement('canvas');
      cv.width = cv.height = 96;
      cv.className = 'hs-avatar';
      drawAvatar(cv, heroId);
      card.appendChild(cv);

      const nm = document.createElement('div');
      nm.className = 'hs-name';
      nm.textContent = cfg.name;
      card.appendChild(nm);

      const role = document.createElement('div');
      role.className = 'hs-role';
      role.textContent = `${cfg.role} · ${meta.tag}`;
      card.appendChild(role);

      const sk = document.createElement('div');
      sk.className = 'hs-skills';
      for (const slot of ['s1', 's2', 'ult']) {
        const d = document.createElement('div');
        d.className = 'hs-skill';
        d.innerHTML = `<b>${defs[slot].name}</b><span>${meta.desc[slot]}</span>`;
        sk.appendChild(d);
      }
      card.appendChild(sk);

      card.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        row.querySelectorAll('.hs-card').forEach(c => c.classList.remove('sel'));
        card.classList.add('sel');
        selected = heroId;
        lockBtn.classList.remove('disabled');
      });
      row.appendChild(card);
    }

    lockBtn.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      if (!selected) return;
      wrap.remove();
      onLock(selected);
    });
    this.root.appendChild(wrap);
  }

  // ================= 对局内绑定（播报/音效/结算） =================
  /** @param {GameState} state @param {AudioEngine} audio */
  bindGame(state, audio) {
    this._state = state;
    this._audio = audio;
    const ev = state.events;
    const myTeam = state.player.team;

    // ---- 击杀/连杀/超神/团灭 播报 ----
    ev.on('announce', ({ type, unit, team }) => {
      const mine = unit ? unit.team === myTeam : (team === myTeam);
      const text = BANNER_TEXT[type] || type;
      this.showBanner(text, mine ? 'good' : 'bad', 2200);
      if (audio) {
        audio.speak(unit ? `${unit.name}${text}` : text);
        audio.play(type === 'firstBlood' ? 'kill' : 'multikill');
      }
    });

    // ---- 防御塔摧毁 ----
    ev.on('towerDown', ({ tower }) => {
      const mine = tower.team === myTeam;
      this.showBanner(mine ? '我方防御塔被摧毁' : '摧毁敌方防御塔！', mine ? 'bad' : 'good', 2000);
      if (audio) {
        audio.speak(mine ? '我方防御塔被摧毁' : '摧毁敌方防御塔');
        audio.play('tower');
      }
    });

    // ---- 暴君/主宰击杀提示 ----
    ev.on('monsterKilled', ({ type, byTeam }) => {
      if (type !== 'tyrant' && type !== 'overlord') return;
      const name = type === 'tyrant' ? '暴君' : '主宰';
      const mine = byTeam === myTeam;
      this.showBanner(mine ? `我方击杀${name}！` : `敌方击杀了${name}`, mine ? 'good' : 'bad', 2000);
      if (audio) {
        audio.speak(mine ? `我方击杀${name}` : `敌方击杀${name}`);
        audio.play('objective');
      }
    });

    // ---- 右上击杀播报条 ----
    ev.on('heroDown', ({ unit, killer }) => {
      this._addKillEntry(killer, unit);
      if (audio && killer && killer.isPlayer) audio.play('kill');
    });

    // ---- 玩家操作音效 ----
    ev.on('basicAttack', ({ attacker }) => { if (attacker.isPlayer && audio) audio.play('hit'); });
    ev.on('skillCast', ({ unit }) => { if (unit.isPlayer && audio) audio.play('skill'); });
    ev.on('recallStart', ({ unit }) => { if (unit.isPlayer && audio) audio.play('recall'); });
    ev.on('flashCast', ({ unit }) => { if (unit.isPlayer && audio) audio.play('flash'); });
    ev.on('summonerHeal', ({ unit }) => { if (unit.isPlayer && audio) audio.play('heal'); });
    ev.on('itemBought', ({ unit }) => { if (unit.isPlayer && audio) audio.play('buy'); });

    // ---- 胜负 ----
    ev.on('victory', ({ winner }) => {
      const win = winner === myTeam;
      this.showBanner(win ? '胜 利' : '失 败', win ? 'good' : 'bad', 2600);
      if (audio) {
        audio.speak(win ? '胜利' : '失败');
        audio.play(win ? 'victory' : 'defeat');
      }
      setTimeout(() => this.showResult(), 2700);
    });
  }

  // ================= 中央大横幅 =================
  /** @param {string} text @param {string} cls good|bad @param {number} duration ms */
  showBanner(text, cls = '', duration = 2000) {
    const b = this.banner;
    this._bannerCount++;
    clearTimeout(this._bbT1); clearTimeout(this._bbT2);
    b.textContent = text;
    b.className = '';
    b.style.display = 'block';
    void b.offsetWidth;               // 重启动画
    b.classList.add('show');
    if (cls) b.classList.add(cls);
    this._bbT1 = setTimeout(() => b.classList.add('out'), Math.max(300, duration - 420));
    this._bbT2 = setTimeout(() => { b.style.display = 'none'; b.className = ''; }, duration);
  }

  // ================= 击杀播报条 =================
  _addKillEntry(killer, victim) {
    this._kfCount++;
    const entry = document.createElement('div');
    entry.className = 'kf-entry';
    const side = (u, fallback) => {
      const s = document.createElement('span');
      s.className = 'kf-side ' + (u ? (u.team === TEAM.BLUE ? 'blue' : u.team === TEAM.RED ? 'red' : 'neutral') : 'neutral');
      s.textContent = u ? (u.name || fallback) : fallback;
      return s;
    };
    entry.appendChild(side(killer, '防御塔'));
    const vs = document.createElement('span');
    vs.className = 'kf-vs';
    vs.textContent = ' 击杀 ';
    entry.appendChild(vs);
    entry.appendChild(side(victim, '英雄'));
    this.killfeed.appendChild(entry);
    // 最多叠 4 条
    while (this.killfeed.children.length > 4) this.killfeed.firstChild.remove();
    setTimeout(() => { if (entry.parentNode) entry.remove(); }, 4000);
  }

  // ================= 商店面板 =================
  toggleShop() {
    if (!this._shopEl) this._buildShop();
    const el = this._shopEl;
    const show = el.style.display !== 'flex';
    el.style.display = show ? 'flex' : 'none';
    if (show) this._renderShop();
    return show;
  }

  _buildShop() {
    const el = document.createElement('div');
    el.id = 'shop-panel';
    el.setAttribute('data-ui', '1');
    el.innerHTML = `
      <div class="sp-head">
        <span class="sp-title">装备商店</span>
        <span class="sp-gold">◈ <b class="sp-gold-v">0</b></span>
        <span class="sp-rec" data-ui="1">推荐出装</span>
        <span class="sp-close" data-ui="1">✕</span>
      </div>
      <div class="sp-tip"></div>
      <div class="sp-list"></div>`;
    el.style.display = 'none';
    el.querySelector('.sp-close').addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      el.style.display = 'none';
    });
    el.querySelector('.sp-rec').addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      const p = this._state && this._state.player;
      if (!p) return;
      const before = p.shop.count;
      const ok = p.shop.buyRecommended();
      if (!ok) this._shopTip(p.shop.count >= 6 ? '装备栏已满' : '金币不足');
      else if (p.shop.count === before) this._shopTip('无法购买');
      this._renderShop();
    });
    this.root.appendChild(el);
    this._shopEl = el;
    // 面板打开时游戏继续：每 0.5s 刷新金币/可购状态
    this._shopRefreshT = setInterval(() => {
      if (this._shopEl.style.display === 'flex') this._renderShop();
    }, 500);
  }

  _shopTip(text) {
    const tip = this._shopEl.querySelector('.sp-tip');
    tip.textContent = text;
    tip.classList.add('show');
    clearTimeout(this._tipT);
    this._tipT = setTimeout(() => tip.classList.remove('show'), 1200);
  }

  _renderShop() {
    const p = this._state && this._state.player;
    if (!p) return;
    const el = this._shopEl;
    el.querySelector('.sp-gold-v').textContent = String(Math.floor(p.gold));
    const list = el.querySelector('.sp-list');
    list.innerHTML = '';
    const full = p.shop.count >= 6;
    for (const itemId of Object.keys(ITEMS)) {
      const item = ITEMS[itemId];
      const st = ITEM_STYLE[itemId];
      const owned = p.shop.slots.filter(s => s === itemId).length;
      const afford = p.gold >= item.price && !full;
      const row = document.createElement('div');
      row.className = 'sp-item' + (afford ? '' : ' disabled');
      row.innerHTML = `
        <i class="sp-ic" style="background:linear-gradient(160deg, ${st.color}, rgba(10,14,22,.9))">${st.icon}</i>
        <div class="sp-mid">
          <div class="sp-name">${item.name}${owned ? `<em>×${owned}</em>` : ''}</div>
          <div class="sp-attr">${itemAttrText(item)}</div>
        </div>
        <div class="sp-price">◈${item.price}</div>`;
      row.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        if (!p.shop.buy(itemId)) this._shopTip(full ? '装备栏已满' : '金币不足');
        this._renderShop();
      });
      list.appendChild(row);
    }
    // 推荐出装下一件提示
    const rec = RECOMMENDED[p.heroId] || [];
    const next = rec.find(id => !p.shop.has(id));
    el.querySelector('.sp-rec').textContent = next ? `推荐出装(${ITEMS[next].name})` : '推荐出装(已齐)';
  }

  // ================= 结算界面 =================
  showResult() {
    const s = this._state;
    if (!s || this._resultEl) return;
    const p = s.player;
    const win = s.winner === p.team;
    const t = Math.floor(s.time);
    const mm = String(Math.floor(t / 60)).padStart(2, '0');
    const ss = String(t % 60).padStart(2, '0');

    const el = document.createElement('div');
    el.id = 'result-screen';
    el.setAttribute('data-ui', '1');

    // 双方英雄 KDA 简表（蓝方在前，玩家置顶）
    const heroes = s.units.filter(u => u.kind === 'hero');
    heroes.sort((a, b) =>
      ((b.isPlayer ? 1 : 0) - (a.isPlayer ? 1 : 0)) ||
      (a.team === TEAM.BLUE ? 0 : 1) - (b.team === TEAM.BLUE ? 0 : 1) ||
      b.kills - a.kills);
    const rows = heroes.map(h => `
      <tr class="${h.isPlayer ? 'me' : ''}">
        <td class="${h.team}">${h.name}${h.isPlayer ? '（你）' : ''}</td>
        <td>${h.kills}/${h.deaths}/${h.assists}</td>
        <td>${h.creeps}</td>
        <td>Lv.${h.level}</td>
      </tr>`).join('');

    el.innerHTML = `
      <div class="rs-box">
        <div class="rs-title ${win ? 'win' : 'lose'}">${win ? '胜 利' : '失 败'}</div>
        <div class="rs-score"><span class="blue">${s.score.blue}</span> : <span class="red">${s.score.red}</span>
          <span class="rs-time">${mm}:${ss}</span></div>
        <div class="rs-me">你的战绩：<b>${p.kills}/${p.deaths}/${p.assists}</b>　补刀 <b>${p.creeps}</b>　等级 <b>${p.level}</b></div>
        <table class="rs-table">
          <thead><tr><th>英雄</th><th>K/D/A</th><th>补刀</th><th>等级</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
        <div class="rs-again" data-ui="1">再来一局</div>
      </div>`;
    el.querySelector('.rs-again').addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      location.reload();
    });
    this.root.appendChild(el);
    this._resultEl = el;
  }
}
