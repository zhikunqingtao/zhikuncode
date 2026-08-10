// ============================================================
// 入口：boot 流程 / 模块装配 / 主循环
// 固定步长逻辑 30Hz + rAF 渲染
// 调试钩子：boot 成功 document.title='KING_OK'，失败 'KING_ERR: '+msg
// URL 参数：?hero=arthur|houyi|daji|niumo|lanlingwang（跳过选将直接进）
//          ?demo=1 自动演示（跳过选将；跟随中路兵线/自动普攻/技能/撤退）
//          ?speed=N（N=2/4/8 加速测试：逻辑步长不变，每帧跑 N 倍步数）
// 无参数：先出选英雄界面，锁定后开始游戏
// ============================================================
import { EngineRenderer } from './engine/renderer.js';
import { Input } from './engine/input.js';
import { VFX } from './engine/vfx.js';
import { AudioEngine } from './engine/audio.js';
import { buildMap } from './world/map.js';
import { GameState } from './game/state.js';
import { HUD } from './ui/hud.js';
import { Minimap } from './ui/minimap.js';
import { Screens } from './ui/screens.js';
import { LOOP } from './config.js';

async function boot() {
  const params = new URLSearchParams(location.search);

  // ---- 模块装配（选将前：3D 场景作为背景） ----
  const engine = new EngineRenderer(document.getElementById('app'));
  const mapData = buildMap(engine.scene);
  const vfx = new VFX(engine.scene);
  const hud = new HUD(document.getElementById('ui'));
  const input = new Input({ base: hud.joyBase, knob: hud.joyKnob });
  const minimap = new Minimap(document.getElementById('ui'), mapData);
  const screens = new Screens(document.getElementById('ui'));
  const audio = new AudioEngine();

  hud.setVisible(false);          // 选将界面期间隐藏 HUD
  hud.onMute = () => audio.toggle();

  // 首次用户手势解锁 AudioContext
  const unlock = () => audio.unlock();
  window.addEventListener('pointerdown', unlock, { once: true });
  window.addEventListener('keydown', unlock, { once: true });

  // ---- 对局状态（选将锁定后创建） ----
  let state = null;
  let player = null;

  // 调试钩子（先于 startGame 创建，startGame 会回填 state/player）
  window.__game = { engine, mapData, hud, input, state: null, player: null, vfx, minimap, screens, audio };

  function startGame(heroId) {
    state = new GameState({ scene: engine.scene, mapData, vfx });
    player = state.spawnPlayer(heroId);
    state.setupAI(heroId);          // 装配 9 个 AI 英雄（蓝4+红5）
    hud.bindState(state);
    hud.setVisible(true);
    minimap.setVisible(true);
    minimap.bind(engine, () => player);
    engine.setFollowTarget(player.model);
    screens.bindGame(state, audio); // 播报/横幅/击杀条/结算

    // ---- p5 视觉钩子（只读事件/状态，不改玩法逻辑） ----
    vfx.bindState(state);           // 眩晕星星/红蓝BUFF光环/回城光柱/泉水绿光轮询
    // 攻击挥砍前摇 / 施法抬手动作
    state.events.on('basicAttack', ({ attacker }) => {
      const ud = attacker.model && attacker.model.userData;
      if (ud && ud.playAttack) ud.playAttack();
    });
    state.events.on('skillCast', ({ unit }) => {
      const ud = unit.model && unit.model.userData;
      if (ud && ud.playCast) ud.playCast();
    });
    // 英雄重生/出场：复位死亡倒地的 rotation.x（vfx.dissolve 的倒地动画）
    state.events.on('unitSpawned', (u) => {
      if (u.model) u.model.rotation.x = 0;
    });
    // 暴君/主宰击杀：全队金色光柱
    state.events.on('monsterKilled', ({ type, byTeam }) => {
      if ((type === 'tyrant' || type === 'overlord') && byTeam) vfx.teamGold(byTeam);
    });

    // HUD 操作回调
    hud.onSkill = (slot) => state.queueAction(slot);
    hud.onSkillDir = (slot, dir) => state.queueAction({ skill: slot, dir });
    hud.onRecall = () => state.queueAction('recall');
    hud.onFlash = () => state.queueAction('flash');
    hud.onHeal = () => state.queueAction('heal');
    hud.onShop = () => screens.toggleShop();

    // demo 模式：?demo=1 脚本控制玩家（无头验证/截图用）
    if (params.get('demo') === '1') state.enableDemo();

    window.__game.state = state;
    window.__game.player = player;
  }

  // 选将流程：?demo=1 或带 ?hero= 时跳过选将直接进（保持测试入口可用）
  const urlHero = params.get('hero');
  if (params.get('demo') === '1' || urlHero) {
    startGame(urlHero || 'arthur');
  } else {
    screens.showHeroSelect((heroId) => {
      audio.unlock();
      audio.speak('欢迎来到王者峡谷');
      startGame(heroId);
    });
  }

  // 加速测试模式：?speed=2/4/8（逻辑步长不变，每帧跑 N 倍步数）
  let speed = parseInt(params.get('speed') || '1', 10);
  if (![1, 2, 4, 8].includes(speed)) speed = 1;
  const maxSub = LOOP.MAX_SUB * speed;

  // ---- 主循环 ----
  let last = performance.now();
  let acc = 0;
  let elapsed = 0;

  function frame(now) {
    requestAnimationFrame(frame);
    let dt = (now - last) / 1000;
    last = now;
    if (dt > 0.25) dt = 0.25;   // 掉帧保护
    elapsed += dt;

    if (state) {
      // 输入动作 → 状态
      for (const a of input.consumeActions()) state.queueAction(a);
      state.attackHeld = input.attackHeld || hud.attackHeld;
      state.speedMode = speed;

      // 固定步长逻辑（加速模式下时间累积 ×N，子步上限同步放大）
      acc += dt * speed;
      let sub = 0;
      while (acc >= LOOP.STEP && sub < maxSub) {
        state.update(LOOP.STEP, input.getMoveVector());
        acc -= LOOP.STEP;
        sub++;
      }
      if (sub === maxSub) acc = 0;

      state.animate(dt);
      minimap.update(state, dt);
    } else {
      input.consumeActions();   // 选将期间丢弃输入动作，避免进入对局时误触发
    }

    // 渲染
    vfx.update(dt);
    mapData.update(dt, elapsed);
    engine.update(dt);
    engine.render();
    hud.update(engine.camera);
  }
  requestAnimationFrame(frame);

  window.addEventListener('resize', () => engine.resize());
  window.addEventListener('orientationchange', () => setTimeout(() => engine.resize(), 100));

  // ---- 调试钩子 ----
  // 手动步进（自动化验证用）：推进 logic 秒数并渲染一帧
  window.__game.step = (seconds) => {
    if (!state) return;
    const n = Math.round(seconds / LOOP.STEP);
    for (let i = 0; i < n; i++) state.update(LOOP.STEP, { x: 0, z: 0 });
    state.animate(seconds);
    vfx.update(Math.min(seconds, 2));
    mapData.update(seconds, elapsed);
    engine.update(seconds);
    engine.render();
    hud.update(engine.camera);
    minimap.update(state, seconds);
  };
  Object.defineProperty(window.__game, 'skills', { get: () => state && state.skills });
  document.getElementById('loading').style.display = 'none';
  document.title = 'KING_OK';
}

boot().catch(err => {
  console.error(err);
  window.__errors.push('boot: ' + (err && err.message || err));
  document.title = 'KING_ERR: ' + (err && err.message || err);
  const loading = document.getElementById('loading');
  if (loading) {
    const tip = loading.querySelector('.tip');
    if (tip) tip.outerHTML = `<div class="err">启动失败：${err && err.message || err}</div>`;
  }
});
