// ============================================================
// 程序化角色模型工厂（p5 视觉打磨重做）
// createHumanoid(opts)：分段人形骨架——骨盆/胸/头/大腿/小腿/上臂/前臂
//   - 走跑四肢摆动+膝盖弯曲，待机呼吸浮动
//   - userData.playAttack() 挥砍/拉弓/前刺前摇动作（main.js 订阅 basicAttack 触发）
//   - userData.playCast()   双手抬起施法动作（main.js 订阅 skillCast 触发）
//   - 死亡倒地淡出在 vfx.dissolve（rotation.x 倒下+材质淡出）
// createHeroModel(heroId, team)：5 英雄专属配色/武器/头饰，剪影可辨
// createMinionModel / createMonsterModel：小兵 3 种 / 野怪 5 种
// 单位脚下有队伍色圆环
// ============================================================
import * as THREE from 'three';
import { TEAM_COLOR } from '../config.js';
import { TAU } from '../utils.js';

// 共享几何体缓存（单位尺寸，批量复用；个体差异走 mesh.scale）
const GEO = {
  // 身体分段
  pelvis: new THREE.BoxGeometry(0.54, 0.28, 0.38),
  chest: new THREE.BoxGeometry(0.72, 0.6, 0.44),
  chestPlate: new THREE.BoxGeometry(0.78, 0.34, 0.5),
  shoulderPad: new THREE.SphereGeometry(0.19, 8, 6),
  thigh: new THREE.BoxGeometry(0.21, 0.52, 0.23),
  shin: new THREE.BoxGeometry(0.17, 0.48, 0.19),
  foot: new THREE.BoxGeometry(0.2, 0.12, 0.34),
  upperArm: new THREE.BoxGeometry(0.17, 0.44, 0.19),
  forearm: new THREE.BoxGeometry(0.15, 0.42, 0.17),
  hand: new THREE.SphereGeometry(0.11, 6, 5),
  head: new THREE.SphereGeometry(0.27, 14, 11),
  neck: new THREE.CylinderGeometry(0.09, 0.11, 0.18, 6),
  belt: new THREE.BoxGeometry(0.6, 0.12, 0.42),
  // 头饰
  helm: new THREE.SphereGeometry(0.32, 12, 8, 0, TAU, 0, Math.PI * 0.55),
  crest: new THREE.BoxGeometry(0.07, 0.3, 0.5),
  circlet: new THREE.TorusGeometry(0.26, 0.045, 6, 16),
  gem: new THREE.OctahedronGeometry(0.07),
  foxEar: new THREE.ConeGeometry(0.13, 0.44, 5),
  hairCap: new THREE.SphereGeometry(0.3, 12, 8, 0, TAU, 0, Math.PI * 0.62),
  horn: new THREE.ConeGeometry(0.13, 0.66, 6),
  hood: new THREE.ConeGeometry(0.36, 0.66, 8),
  mask: new THREE.BoxGeometry(0.4, 0.13, 0.1),
  // 服饰
  skirt: new THREE.ConeGeometry(0.54, 0.95, 10, 1, true),
  cape: new THREE.PlaneGeometry(0.78, 1.15),
  tailSeg: new THREE.SphereGeometry(1, 8, 6),
  // 武器
  gsBlade: new THREE.BoxGeometry(0.09, 1.32, 0.2),     // 金色大剑剑身（p5b 收窄：0.36 像门板）
  gsTip: new THREE.ConeGeometry(0.16, 0.4, 4),         // 大剑剑尖
  gsGuard: new THREE.BoxGeometry(0.16, 0.13, 0.66),
  grip: new THREE.CylinderGeometry(0.05, 0.05, 0.44, 6),
  bowLimb: new THREE.TorusGeometry(0.62, 0.05, 6, 18, Math.PI),
  bowTip: new THREE.SphereGeometry(0.07, 6, 5),
  bowString: new THREE.BoxGeometry(0.018, 1.2, 0.018),
  quiver: new THREE.CylinderGeometry(0.1, 0.12, 0.62, 6),
  arrow: new THREE.CylinderGeometry(0.02, 0.02, 0.4, 4),
  orb: new THREE.IcosahedronGeometry(0.23, 0),
  orbRing: new THREE.TorusGeometry(0.32, 0.03, 6, 18),
  axeShaft: new THREE.CylinderGeometry(0.06, 0.075, 2.0, 6),
  axeBlade: new THREE.BoxGeometry(0.62, 0.52, 0.09),
  axeSpike: new THREE.ConeGeometry(0.09, 0.34, 4),
  daggerBlade: new THREE.BoxGeometry(0.07, 0.66, 0.17),
  daggerTip: new THREE.ConeGeometry(0.085, 0.2, 4),
  swordBlade: new THREE.BoxGeometry(0.09, 1.05, 0.24),
  swordTip: new THREE.ConeGeometry(0.13, 0.26, 4),
  swordGuard: new THREE.BoxGeometry(0.13, 0.1, 0.46),
  staffShaft: new THREE.CylinderGeometry(0.045, 0.045, 1.5, 6),
  staffOrb: new THREE.IcosahedronGeometry(0.17, 0),
  shield: new THREE.CylinderGeometry(0.46, 0.46, 0.09, 16),
  shieldBoss: new THREE.SphereGeometry(0.15, 8, 6),
  // 脚下队伍环
  ring: new THREE.RingGeometry(0.95, 1.2, 32),
  ringInner: new THREE.CircleGeometry(0.95, 32),
};

const ATK_DUR = 0.4;    // 攻击动作时长（前摇 40% + 挥出 60%）
const CAST_DUR = 0.5;   // 施法抬手时长

/**
 * 通用人形模型
 * @param {object} opts
 *   team: 'blue'|'red'
 *   body/trim/skin/accent: 主色/饰边色/肤色/发光点缀色
 *   bulk: {torso, limb, head} 体格比例（牛魔壮硕/妲己纤细）
 *   weapon: 'greatsword'|'bow'|'orb'|'axe'|'daggers'|'sword'|'dagger'|'staff'|null
 *   shield: bool  headgear: 'helm'|'circlet'|'foxEars'|'horns'|'hoodMask'|null
 *   cape/skirt/tail: 颜色|null   quiver: bool
 *   attackStyle: 'slash'|'shoot'|'thrust'   scale: 整体缩放
 * @returns {THREE.Group} userData.update(dt, moving) / playAttack() / playCast()
 */
export function createHumanoid(opts = {}) {
  const {
    team = 'blue',
    body = 0x3a5f9e,
    trim = 0xd8b04a,
    skin = 0xe8c39a,
    accent = 0xffe080,
    weapon = 'sword',
    shield = false,
    headgear = null,
    cape = null,
    skirt = null,
    tail = null,
    quiver = false,
    attackStyle = 'slash',
    scale = 1,
    shadow = true,
  } = opts;
  const bulk = Object.assign({ torso: 1, limb: 1, head: 1 }, opts.bulk);

  const g = new THREE.Group();
  const matBody = new THREE.MeshLambertMaterial({ color: body });
  const matTrim = new THREE.MeshLambertMaterial({ color: trim });
  const matSkin = new THREE.MeshLambertMaterial({ color: skin });
  const matGlow = new THREE.MeshLambertMaterial({ color: accent, emissive: accent, emissiveIntensity: 0.55 });
  const matMetal = new THREE.MeshLambertMaterial({ color: 0xcfd8e4, emissive: 0x2a2f38 });

  const mesh = (geo, mat, x, y, z, parent = g) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(x, y, z);
    m.castShadow = shadow;   // p5b：小兵不投影（数量多，阴影 pass draw calls 减半）
    parent.add(m);
    return m;
  };

  // 脚下队伍色圆环
  const tc = TEAM_COLOR[team];
  const ring = new THREE.Mesh(GEO.ring,
    new THREE.MeshBasicMaterial({ color: tc.ring, transparent: true, opacity: 0.9, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.04;
  g.add(ring);
  const ringIn = new THREE.Mesh(GEO.ringInner,
    new THREE.MeshBasicMaterial({ color: tc.ring, transparent: true, opacity: 0.15, depthWrite: false }));
  ringIn.rotation.x = -Math.PI / 2;
  ringIn.position.y = 0.04;
  g.add(ringIn);

  // 身体容器（呼吸/跑动起伏/攻击拧身都作用在它身上）
  const bodyG = new THREE.Group();
  g.add(bodyG);

  const limbS = bulk.limb;
  // ---- 腿（髋 pivot → 大腿 / 膝 pivot → 小腿+脚） ----
  const mkLeg = (sx) => {
    const hip = new THREE.Group();
    hip.position.set(sx * 0.17 * bulk.torso, 1.02, 0);
    bodyG.add(hip);
    const th = mesh(GEO.thigh, matBody, 0, -0.25, 0, hip);
    th.scale.set(limbS, 1, limbS);
    const knee = new THREE.Group();
    knee.position.set(0, -0.5, 0);
    hip.add(knee);
    const sh = mesh(GEO.shin, matBody, 0, -0.22, 0, knee);
    sh.scale.set(limbS, 1, limbS);
    mesh(GEO.foot, matTrim, 0, -0.44, 0.06, knee);
    return { hip, knee };
  };
  const legL = mkLeg(-1), legR = mkLeg(1);

  // ---- 躯干 ----
  const torsoS = bulk.torso;
  const pelvis = mesh(GEO.pelvis, matBody, 0, 1.12, 0, bodyG);
  pelvis.scale.set(torsoS, 1, torsoS);
  const chest = mesh(GEO.chest, matBody, 0, 1.55, 0, bodyG);
  chest.scale.set(torsoS, 1, torsoS);
  const plate = mesh(GEO.chestPlate, matTrim, 0, 1.62, 0.02, bodyG);   // 胸前护甲
  plate.scale.set(torsoS, 1, torsoS);
  const belt = mesh(GEO.belt, matTrim, 0, 1.02, 0, bodyG);
  belt.scale.set(torsoS, 1, torsoS);

  // 长裙（妲己）：从腰部罩下的锥形裙
  let skirtM = null;
  if (skirt !== null) {
    skirtM = new THREE.Mesh(GEO.skirt, new THREE.MeshLambertMaterial({
      color: skirt, side: THREE.DoubleSide,
    }));
    skirtM.position.set(0, 0.62, 0);
    skirtM.castShadow = true;
    bodyG.add(skirtM);
  }

  // ---- 手臂（肩 pivot → 上臂 / 肘 pivot → 前臂+手+武器挂点） ----
  const armX = 0.37 * torsoS + 0.11;
  const mkArm = (sx) => {
    const shoulder = new THREE.Group();
    shoulder.position.set(sx * armX, 1.78, 0);
    bodyG.add(shoulder);
    const pad = mesh(GEO.shoulderPad, matTrim, sx * 0.03, 0.06, 0, shoulder);
    pad.scale.set(limbS * 1.1, 0.75, limbS * 1.1);
    const up = mesh(GEO.upperArm, matBody, 0, -0.2, 0, shoulder);
    up.scale.set(limbS, 1, limbS);
    const elbow = new THREE.Group();
    elbow.position.set(0, -0.42, 0);
    shoulder.add(elbow);
    const fo = mesh(GEO.forearm, matBody, 0, -0.18, 0, elbow);
    fo.scale.set(limbS, 1, limbS);
    mesh(GEO.hand, matSkin, 0, -0.42, 0, elbow);
    const mount = new THREE.Group();      // 武器挂点（手心）
    mount.position.set(0, -0.46, 0.06);
    elbow.add(mount);
    return { shoulder, elbow, mount };
  };
  const armL = mkArm(-1), armR = mkArm(1);

  // ---- 头 ----
  const headG = new THREE.Group();
  headG.position.set(0, 1.98, 0);
  headG.scale.setScalar(bulk.head);
  bodyG.add(headG);
  mesh(GEO.neck, matSkin, 0, -0.06, 0, headG);
  const head = mesh(GEO.head, matSkin, 0, 0.2, 0, headG);

  // ---- 头饰 ----
  if (headgear === 'helm') {           // 亚瑟：金盔+冠饰
    mesh(GEO.helm, matTrim, 0, 0.24, 0, headG);
    mesh(GEO.crest, matGlow, 0, 0.52, -0.02, headG);
  } else if (headgear === 'circlet') { // 后羿：金环额饰+宝石
    const c = mesh(GEO.circlet, matTrim, 0, 0.3, 0, headG);
    c.rotation.x = Math.PI / 2 - 0.25;
    mesh(GEO.gem, matGlow, 0, 0.3, 0.26, headG);
  } else if (headgear === 'foxEars') { // 妲己：长发+狐耳
    const hair = mesh(GEO.hairCap, matBody, 0, 0.24, -0.03, headG);
    hair.scale.set(1.05, 1, 1.05);
    for (const s of [-1, 1]) {
      const e = mesh(GEO.foxEar, matTrim, s * 0.17, 0.55, 0, headG);
      e.rotation.z = -s * 0.22;
    }
  } else if (headgear === 'horns') {   // 牛魔：双巨角
    mesh(GEO.helm, matTrim, 0, 0.22, 0, headG);
    for (const s of [-1, 1]) {
      const h = mesh(GEO.horn, matGlow, s * 0.3, 0.34, 0, headG);
      h.rotation.z = -s * 1.15;
      h.rotation.y = s * 0.3;
    }
  } else if (headgear === 'hoodMask') {// 兰陵王：兜帽+发光面罩
    mesh(GEO.hood, matBody, 0, 0.42, -0.02, headG);
    mesh(GEO.mask, matGlow, 0, 0.18, 0.24, headG);
  }

  // ---- 武器 ----
  const buildWeapon = (type, mount) => {
    const w = new THREE.Group();
    mount.add(w);
    if (type === 'greatsword') {           // 亚瑟·金色大剑
      mesh(GEO.grip, matTrim, 0, 0.1, 0, w);
      mesh(GEO.gsGuard, matTrim, 0, 0.34, 0, w);
      mesh(GEO.gsBlade, matGlow, 0, 1.05, 0, w);
      const tip = mesh(GEO.gsTip, matGlow, 0, 1.8, 0, w);
      tip.rotation.y = Math.PI / 4;        // 四棱锥转 45° 对齐剑身截面
      tip.scale.set(0.4, 1, 0.62);
      w.rotation.x = -0.55;                // 持剑前倾
    } else if (type === 'bow') {           // 后羿·白金长弓（左手持）
      const limb = mesh(GEO.bowLimb, matTrim, 0, 0, 0, w);
      limb.rotation.z = -Math.PI / 2;      // 弓梢上下竖直，弓腹朝 +x
      for (const sy of [-1, 1]) mesh(GEO.bowTip, matGlow, 0, sy * 0.62, 0, w);
      mesh(GEO.bowString, matGlow, 0, 0, 0, w);   // 竖直弓弦（发光）
      w.rotation.y = Math.PI / 2;          // 弓腹转向身后，弦朝前
    } else if (type === 'orb') {           // 妲己·悬浮法球
      const o = mesh(GEO.orb, matGlow, 0, 0.35, 0.05, w);
      const halo = mesh(GEO.orbRing, matGlow, 0, 0.35, 0.05, w);
      halo.rotation.x = Math.PI / 2.4;
      w.userData.orb = o; w.userData.halo = halo;
    } else if (type === 'axe') {           // 牛魔·巨斧
      mesh(GEO.axeShaft, matTrim, 0, 0.75, 0, w);
      for (const sz of [-1, 1]) {
        const b = mesh(GEO.axeBlade, matMetal, 0, 1.55, sz * 0.3, w);
        b.rotation.y = 0;
      }
      mesh(GEO.axeSpike, matMetal, 0, 1.95, 0, w);
      mesh(GEO.gsGuard, matTrim, 0, 1.28, 0, w);
      w.rotation.x = -0.45;
    } else if (type === 'daggers') {       // 兰陵王·双短刃（暗紫+青芒）
      mesh(GEO.grip, matTrim, 0, 0.05, 0, w).scale.setScalar(0.8);
      mesh(GEO.daggerBlade, matMetal, 0, 0.42, 0, w);
      const edge = mesh(GEO.daggerTip, matGlow, 0, 0.83, 0, w);
      edge.rotation.y = Math.PI / 4; edge.scale.set(0.5, 1, 1.1);
      w.rotation.x = -0.8;
    } else if (type === 'sword') {         // 通用长剑（超级兵）
      mesh(GEO.grip, matTrim, 0, 0.08, 0, w);
      mesh(GEO.swordGuard, matTrim, 0, 0.28, 0, w);
      mesh(GEO.swordBlade, matMetal, 0, 0.85, 0, w);
      const tip = mesh(GEO.swordTip, matMetal, 0, 1.5, 0, w);
      tip.rotation.y = Math.PI / 4; tip.scale.set(0.42, 1, 1.05);
      w.rotation.x = -0.5;
    } else if (type === 'dagger') {        // 短刀（近战兵）
      mesh(GEO.grip, matTrim, 0, 0.04, 0, w).scale.setScalar(0.8);
      mesh(GEO.daggerBlade, matMetal, 0, 0.4, 0, w);
      w.rotation.x = -0.7;
    } else if (type === 'staff') {         // 法杖（法师兵）
      mesh(GEO.staffShaft, matTrim, 0, 0.55, 0, w);
      mesh(GEO.staffOrb, matGlow, 0, 1.4, 0, w);
      w.rotation.x = -0.3;
    }
    return w;
  };
  // 弓拿在左手（射手拉弓姿态），法球悬浮右手，其余右手
  const weaponMount = weapon === 'bow' ? armL.mount : armR.mount;
  const weaponG = weapon ? buildWeapon(weapon, weaponMount) : null;
  if (weapon === 'daggers') buildWeapon('daggers', armL.mount);   // 双持

  // 盾（左手）
  if (shield) {
    const sh = mesh(GEO.shield, matTrim, -0.06, -0.5, 0.1, armL.elbow);
    sh.rotation.z = Math.PI / 2;
    sh.rotation.x = 0.15;
    mesh(GEO.shieldBoss, matGlow, -0.06, -0.5, 0.16, armL.elbow);
  }

  // 披风（亚瑟）
  let capeM = null;
  if (cape !== null) {
    capeM = new THREE.Mesh(GEO.cape, new THREE.MeshLambertMaterial({
      color: cape, side: THREE.DoubleSide,
    }));
    capeM.position.set(0, -0.5, -0.03);   // 挂点在背部上端
    const capePin = new THREE.Group();
    capePin.position.set(0, 1.82, -0.26 * torsoS);
    bodyG.add(capePin);
    capePin.add(capeM);
    capeM.castShadow = shadow;
    capeM.userData.pin = capePin;
  }

  // 箭袋（后羿）
  if (quiver) {
    const q = mesh(GEO.quiver, matTrim, -0.12, 1.62, -0.3 * torsoS, bodyG);
    q.rotation.z = 0.25; q.rotation.x = -0.2;
    for (let i = 0; i < 3; i++) {
      const a = mesh(GEO.arrow, matGlow, -0.12 + i * 0.06 - 0.06, 1.98, -0.33 * torsoS, bodyG);
      a.rotation.z = 0.25;
    }
  }

  // 狐尾（妲己）：三节点绒球尾，随身体摆动
  let tailG = null;
  if (tail !== null) {
    const matTail = new THREE.MeshLambertMaterial({ color: tail });
    tailG = new THREE.Group();
    tailG.position.set(0, 0.95, -0.28);
    bodyG.add(tailG);
    const sizes = [0.16, 0.24, 0.34];
    let parent = tailG, py = 0, pz = 0;
    const segs = [];
    for (let i = 0; i < 3; i++) {
      const j = new THREE.Group();
      j.position.set(0, py, pz);
      parent.add(j);
      const s = mesh(GEO.tailSeg, i === 2 ? matTrim : matTail, 0, 0, -sizes[i] * 0.8, j);
      s.scale.set(sizes[i], sizes[i] * 1.15, sizes[i] * 1.5);
      segs.push(j);
      parent = j; py = sizes[i] * 0.7; pz = -sizes[i] * 1.1;
    }
    tailG.rotation.x = -0.7;   // 尾巴向后上方翘起
    tailG.userData.segs = segs;
  }

  g.scale.setScalar(scale);

  // ---------------- 动画 ----------------
  const st = {
    t: Math.random() * 10,   // 相位随机，避免全场同步呼吸
    phase: 0,
    atk: 0, cast: 0,
    // 当前值（平滑趋近目标值，避免姿态跳变）
    cur: { legL: 0, legR: 0, kneeL: 0, kneeR: 0, armL: 0, armR: 0, armLz: 0.07, armRz: -0.07, twist: 0, lean: 0 },
  };
  const lerpTo = (cur, tgt, dt, rate = 16) => cur + (tgt - cur) * Math.min(1, rate * dt);

  g.userData.playAttack = () => { st.atk = ATK_DUR; };
  g.userData.playCast = () => { st.cast = CAST_DUR; };

  g.userData.update = (dt, moving) => {
    st.t += dt;
    const t = st.t;
    const c = st.cur;
    // ---- 基础姿态目标：走跑 / 待机 ----
    let legLx = 0, legRx = 0, kneeLx = 0, kneeRx = 0, aL = 0, aR = 0, aLz = 0.07, aRz = -0.07, lean = 0, bob;
    if (moving) {
      st.phase += dt * 9.5;
      const s = Math.sin(st.phase);
      legLx = s * 0.62; legRx = -s * 0.62;
      kneeLx = Math.max(0, -Math.cos(st.phase)) * 0.8;  // 摆动期膝盖弯曲
      kneeRx = Math.max(0, Math.cos(st.phase)) * 0.8;
      aL = -s * 0.5; aR = s * 0.5;
      lean = 0.07;
      bob = Math.abs(Math.cos(st.phase)) * 0.06;
    } else {
      bob = Math.sin(t * 2.1) * 0.03;                   // 呼吸浮动
      aLz = 0.07 + Math.sin(t * 2.1) * 0.025;
      aRz = -0.07 - Math.sin(t * 2.1) * 0.025;
    }
    let twist = 0;

    // ---- 攻击动作（前摇→挥出，覆盖手臂目标值） ----
    if (st.atk > 0) {
      st.atk -= dt;
      const p = Math.min(1, 1 - st.atk / ATK_DUR);
      if (attackStyle === 'slash') {
        aR = p < 0.4 ? -2.2 * (p / 0.4) : -2.2 + 2.9 * ((p - 0.4) / 0.6);
        aRz = -0.2;
        twist = Math.sin(p * Math.PI) * -0.42;          // 拧身挥砍
        lean += 0.12 * Math.sin(p * Math.PI);
      } else if (attackStyle === 'shoot') {
        aL = -1.5; aLz = 0;                             // 持弓臂前推
        aR = p < 0.5 ? -1.2 - 0.5 * (p / 0.5) : -1.7 + 0.8 * ((p - 0.5) / 0.5); // 拉弦→释放
        aRz = 0.25;
        twist = -0.18;
      } else {                                          // thrust（法球/法杖前刺）
        aR = p < 0.4 ? -0.5 * (p / 0.4) : -0.5 - 1.3 * ((p - 0.4) / 0.6);
        aRz = 0;
        twist = Math.sin(p * Math.PI) * -0.2;
      }
    }
    // ---- 施法动作（双手抬起） ----
    if (st.cast > 0) {
      st.cast -= dt;
      const p = Math.min(1, 1 - st.cast / CAST_DUR);
      const raise = Math.sin(Math.min(1, p * 1.12) * Math.PI);
      aL = aL * (1 - raise) + (-2.35) * raise;
      aR = aR * (1 - raise) + (-2.35) * raise;
      aLz = 0.3 * raise + aLz * (1 - raise);
      aRz = -0.3 * raise + aRz * (1 - raise);
    }

    // ---- 平滑写回 ----
    c.legL = lerpTo(c.legL, legLx, dt); c.legR = lerpTo(c.legR, legRx, dt);
    c.kneeL = lerpTo(c.kneeL, kneeLx, dt); c.kneeR = lerpTo(c.kneeR, kneeRx, dt);
    c.armL = lerpTo(c.armL, aL, dt, 20); c.armR = lerpTo(c.armR, aR, dt, 20);
    c.armLz = lerpTo(c.armLz, aLz, dt); c.armRz = lerpTo(c.armRz, aRz, dt);
    c.twist = lerpTo(c.twist, twist, dt, 14);
    c.lean = lerpTo(c.lean, lean, dt);
    legL.hip.rotation.x = c.legL; legR.hip.rotation.x = c.legR;
    legL.knee.rotation.x = c.kneeL; legR.knee.rotation.x = c.kneeR;
    armL.shoulder.rotation.x = c.armL; armR.shoulder.rotation.x = c.armR;
    armL.shoulder.rotation.z = c.armLz; armR.shoulder.rotation.z = c.armRz;
    bodyG.rotation.y = c.twist;
    bodyG.rotation.x = c.lean;
    bodyG.position.y = bob;
    ring.rotation.z = t * 0.5;

    // ---- 饰件随动 ----
    if (capeM) capeM.rotation.x = 0.08 + Math.sin(t * 2.2) * 0.05 + (moving ? 0.22 : 0);
    if (tailG) {
      tailG.rotation.y = Math.sin(t * 2.4) * 0.35;
      for (let i = 0; i < tailG.userData.segs.length; i++) {
        tailG.userData.segs[i].rotation.x = Math.sin(t * 2.4 - i * 0.8) * 0.18;
      }
    }
    if (weaponG && weaponG.userData.orb) {
      weaponG.userData.orb.position.y = 0.35 + Math.sin(t * 2.6) * 0.07;   // 法球悬浮起伏
      weaponG.userData.halo.rotation.z = t * 1.6;
    }
  };

  g.userData.parts = { legL, legR, armL, armR, chest, head, weaponG, bodyG };
  return g;
}

// 英雄外观表（5 名英雄：专属配色 + 标志武器 + 头饰/服饰，剪影可辨）
const HERO_STYLE = {
  // 亚瑟·战士：蓝金盔甲 + 金色大剑 + 盾牌 + 披风
  arthur: {
    body: 0x2b4f9e, trim: 0xe8c05a, skin: 0xeac49c, accent: 0xffd860,
    weapon: 'greatsword', shield: true, headgear: 'helm', cape: 0x1e3a7a,
    attackStyle: 'slash', scale: 1.06, bulk: { torso: 1.08, limb: 1.06 },
  },
  // 后羿·射手：白金轻甲 + 白金长弓 + 箭袋
  houyi: {
    body: 0xe6e0d2, trim: 0xd8a83a, skin: 0xe8c39a, accent: 0xffa040,
    weapon: 'bow', quiver: true, headgear: 'circlet',
    attackStyle: 'shoot', scale: 1.0, bulk: { torso: 0.94, limb: 0.92 },
  },
  // 妲己·法师：粉紫长裙 + 狐耳 + 大尾巴 + 悬浮法球
  daji: {
    body: 0xb45ac8, trim: 0xf0b8e0, skin: 0xf2d8c4, accent: 0xff7ad0,
    weapon: 'orb', headgear: 'foxEars', skirt: 0x9a48b0, tail: 0xe8a8d8,
    attackStyle: 'thrust', scale: 0.97, bulk: { torso: 0.86, limb: 0.84 },
  },
  // 牛魔·坦克：棕红壮硕 + 双巨角 + 双刃巨斧
  niumo: {
    body: 0x7a3a28, trim: 0x4a4a52, skin: 0xb08458, accent: 0xff5030,
    weapon: 'axe', headgear: 'horns',
    attackStyle: 'slash', scale: 1.18, bulk: { torso: 1.34, limb: 1.38, head: 1.12 },
  },
  // 兰陵王·刺客：暗紫紧身 + 兜帽面罩 + 双短刃
  lanlingwang: {
    body: 0x3a2a5a, trim: 0x2ad8c8, skin: 0xd8c0a0, accent: 0x40e0d0,
    weapon: 'daggers', headgear: 'hoodMask',
    attackStyle: 'slash', scale: 1.0, bulk: { torso: 0.9, limb: 0.88 },
  },
};

/**
 * 英雄模型成品
 * @param {string} heroId 如 'arthur'
 * @param {'blue'|'red'} team
 */
export function createHeroModel(heroId, team) {
  const style = HERO_STYLE[heroId] || HERO_STYLE.arthur;
  const model = createHumanoid({ ...style, team });
  model.userData.heroId = heroId;
  return model;
}

// 小兵模型工厂（近战刀兵/法师兵/炮车/超级兵，低模+双方配色）
export function createMinionModel(type, team) {
  const tc = TEAM_COLOR[team];
  if (type === 'melee') {
    // 近战刀兵：小号人形 + 短刀
    return createHumanoid({
      team, body: tc.main, trim: tc.dark, skin: 0xd8c0a0, accent: tc.bright,
      weapon: 'dagger', shield: false, headgear: 'helm', scale: 0.6,
      bulk: { torso: 0.95, limb: 0.9 }, shadow: false,
    });
  }
  if (type === 'mage') {
    // 法师兵：小号人形 + 法杖（队伍亮色）
    return createHumanoid({
      team, body: tc.dark, trim: tc.bright, skin: 0xe0c8b0, accent: tc.bright,
      weapon: 'staff', shield: false, headgear: 'hoodMask', scale: 0.6,
      attackStyle: 'thrust', bulk: { torso: 0.9, limb: 0.85 }, shadow: false,
    });
  }
  if (type === 'super') {
    // 终局(p4) 超级兵：大号精锐武士（剑+盾，队伍亮色配金边，体型明显大于普通兵）
    return createHumanoid({
      team, body: tc.bright, trim: 0xd8b04a, skin: 0xc8b090, accent: 0xffe080,
      weapon: 'sword', shield: true, headgear: 'helm', cape: tc.dark, scale: 1.04,
      bulk: { torso: 1.1, limb: 1.05 }, shadow: false,
    });
  }
  // 炮车：车体 + 四轮 + 炮管 + 队伍旗帜
  const g = new THREE.Group();
  const matBody = new THREE.MeshLambertMaterial({ color: tc.main });
  const matDark = new THREE.MeshLambertMaterial({ color: tc.dark });
  const matMetal = new THREE.MeshLambertMaterial({ color: 0x8a8f98 });
  const body = new THREE.Mesh(new THREE.BoxGeometry(1.7, 0.8, 2.4), matBody);
  body.position.y = 0.85;
  g.add(body);
  const cabin = new THREE.Mesh(new THREE.BoxGeometry(1.2, 0.6, 1.1), matDark);
  cabin.position.set(0, 1.5, -0.5);
  g.add(cabin);
  // 炮管（朝前 +z）
  const barrel = new THREE.Mesh(new THREE.CylinderGeometry(0.2, 0.26, 1.8, 8), matMetal);
  barrel.rotation.x = Math.PI / 2 - 0.18;
  barrel.position.set(0, 1.55, 0.9);
  g.add(barrel);
  // 轮子
  const wheelGeo = new THREE.CylinderGeometry(0.42, 0.42, 0.3, 10);
  const wheels = [];
  for (const [wx, wz] of [[-0.95, 0.75], [0.95, 0.75], [-0.95, -0.75], [0.95, -0.75]]) {
    const w = new THREE.Mesh(wheelGeo, matDark);
    w.rotation.z = Math.PI / 2;
    w.position.set(wx, 0.42, wz);
    g.add(w);
    wheels.push(w);
  }
  // 队伍色旗帜
  const flag = new THREE.Mesh(new THREE.PlaneGeometry(0.7, 0.45),
    new THREE.MeshLambertMaterial({ color: tc.bright, side: THREE.DoubleSide }));
  flag.position.set(0, 2.35, -0.9);
  g.add(flag);
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.04, 1.0, 5), matMetal);
  pole.position.set(0, 2.0, -0.9);
  g.add(pole);
  // 队伍色圆环（与其他单位一致）
  const ring = new THREE.Mesh(GEO.ring,
    new THREE.MeshBasicMaterial({ color: tc.ring, transparent: true, opacity: 0.9, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.04;
  ring.scale.setScalar(1.35);
  g.add(ring);

  const st = { t: 0 };
  g.userData.update = (dt, moving) => {
    st.t += dt;
    if (moving) for (const w of wheels) w.rotation.x += dt * 6;
    flag.rotation.y = Math.sin(st.t * 3) * 0.2;
  };
  return g;
}

// ============================================================
// 野怪模型工厂（红BUFF/蓝BUFF 石像魔像、小野蜥蜴、暴君/主宰巨龙）
// 中立单位：无队伍色圆环，用土褐色中立环
// ============================================================
const MONSTER_GEO = {
  golemBody: new THREE.BoxGeometry(1.9, 1.7, 1.3),
  golemHead: new THREE.BoxGeometry(0.9, 0.8, 0.8),
  golemArm: new THREE.BoxGeometry(0.5, 1.5, 0.5),
  golemCore: new THREE.IcosahedronGeometry(0.34, 0),
  lizBody: new THREE.BoxGeometry(1.3, 0.7, 0.8),
  lizHead: new THREE.BoxGeometry(0.55, 0.5, 0.5),
  lizTail: new THREE.ConeGeometry(0.22, 1.1, 6),
  dragonBody: new THREE.SphereGeometry(1.6, 12, 10),
  dragonHead: new THREE.BoxGeometry(1.1, 0.9, 1.4),
  dragonWing: new THREE.PlaneGeometry(3.2, 1.6),
  dragonHorn: new THREE.ConeGeometry(0.16, 0.8, 6),
  dragonTail: new THREE.ConeGeometry(0.4, 2.6, 8),
  neutralRing: new THREE.RingGeometry(1.1, 1.32, 28),
};

function _neutralRing(g, scale = 1) {
  const ring = new THREE.Mesh(MONSTER_GEO.neutralRing,
    new THREE.MeshBasicMaterial({ color: 0xc0a060, transparent: true, opacity: 0.65, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.04;
  ring.scale.setScalar(scale);
  g.add(ring);
  return ring;
}

/** 石像魔像（红/蓝BUFF） */
function _createGolem(bodyColor, coreColor) {
  const g = new THREE.Group();
  const matBody = new THREE.MeshLambertMaterial({ color: bodyColor });
  const matCore = new THREE.MeshLambertMaterial({ color: coreColor, emissive: coreColor, emissiveIntensity: 0.8 });
  const mesh = (geo, mat, x, y, z, parent = g) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(x, y, z);
    m.castShadow = true;
    parent.add(m);
    return m;
  };
  const bodyG = new THREE.Group();
  g.add(bodyG);
  mesh(MONSTER_GEO.golemBody, matBody, 0, 1.5, 0, bodyG);
  mesh(MONSTER_GEO.golemHead, matBody, 0, 2.7, 0.25, bodyG);
  const armL = new THREE.Group(); armL.position.set(-1.25, 2.2, 0); bodyG.add(armL);
  const armR = new THREE.Group(); armR.position.set(1.25, 2.2, 0); bodyG.add(armR);
  mesh(MONSTER_GEO.golemArm, matBody, 0, -0.7, 0, armL);
  mesh(MONSTER_GEO.golemArm, matBody, 0, -0.7, 0, armR);
  mesh(MONSTER_GEO.golemCore, matCore, 0, 1.6, 0.68, bodyG);   // 胸前能量核
  const ring = _neutralRing(g, 1.25);
  const st = { t: 0 };
  g.userData.update = (dt, moving) => {
    st.t += dt;
    bodyG.position.y = Math.sin(st.t * 1.8) * 0.06;
    const s = moving ? Math.sin(st.t * 7) : Math.sin(st.t * 1.8) * 0.2;
    armL.rotation.x = s * 0.4;
    armR.rotation.x = -s * 0.4;
    ring.rotation.z = st.t * 0.4;
  };
  return g;
}

/** 小蜥蜴 */
function _createLizard() {
  const g = new THREE.Group();
  const matBody = new THREE.MeshLambertMaterial({ color: 0x7a9a4a });
  const matBelly = new THREE.MeshLambertMaterial({ color: 0xc8c090 });
  const mesh = (geo, mat, x, y, z, parent = g) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(x, y, z);
    m.castShadow = true;
    parent.add(m);
    return m;
  };
  const bodyG = new THREE.Group();
  g.add(bodyG);
  mesh(MONSTER_GEO.lizBody, matBody, 0, 0.62, 0, bodyG);
  mesh(MONSTER_GEO.lizHead, matBelly, 0, 0.72, 0.72, bodyG);
  const tail = mesh(MONSTER_GEO.lizTail, matBody, 0, 0.62, -0.9, bodyG);
  tail.rotation.x = -Math.PI / 2 + 0.25;
  const ring = _neutralRing(g, 0.85);
  const st = { t: 0 };
  g.userData.update = (dt, moving) => {
    st.t += dt;
    bodyG.position.y = moving ? Math.abs(Math.sin(st.t * 9)) * 0.08 : Math.sin(st.t * 2.2) * 0.03;
    tail.rotation.y = Math.sin(st.t * 3.5) * 0.3;
    ring.rotation.z = st.t * 0.4;
  };
  return g;
}

/** 巨龙（暴君/主宰） */
function _createDragon(bodyColor, wingColor, scale) {
  const g = new THREE.Group();
  const matBody = new THREE.MeshLambertMaterial({ color: bodyColor });
  const matWing = new THREE.MeshLambertMaterial({ color: wingColor, side: THREE.DoubleSide, transparent: true, opacity: 0.92 });
  const mesh = (geo, mat, x, y, z, parent = g) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(x, y, z);
    m.castShadow = true;
    parent.add(m);
    return m;
  };
  const bodyG = new THREE.Group();
  g.add(bodyG);
  const body = mesh(MONSTER_GEO.dragonBody, matBody, 0, 2.0, 0, bodyG);
  body.scale.set(1, 0.9, 1.25);
  mesh(MONSTER_GEO.dragonHead, matBody, 0, 3.3, 1.5, bodyG);
  for (const s of [-1, 1]) {
    const horn = mesh(MONSTER_GEO.dragonHorn, matWing, s * 0.35, 3.9, 1.3, bodyG);
    horn.rotation.x = -0.4;
  }
  const wingL = mesh(MONSTER_GEO.dragonWing, matWing, -1.9, 2.9, -0.2, bodyG);
  const wingR = mesh(MONSTER_GEO.dragonWing, matWing, 1.9, 2.9, -0.2, bodyG);
  wingL.rotation.z = 0.5; wingR.rotation.z = -0.5;
  const tail = mesh(MONSTER_GEO.dragonTail, matBody, 0, 1.6, -2.2, bodyG);
  tail.rotation.x = -Math.PI / 2 + 0.35;
  const ring = _neutralRing(g, 2.0);
  g.scale.setScalar(scale);
  const st = { t: 0 };
  g.userData.update = (dt, moving) => {
    st.t += dt;
    bodyG.position.y = Math.sin(st.t * 1.6) * 0.12;
    const flap = Math.sin(st.t * (moving ? 6 : 2.4));
    wingL.rotation.z = 0.5 + flap * 0.25;
    wingR.rotation.z = -0.5 - flap * 0.25;
    tail.rotation.y = Math.sin(st.t * 2) * 0.2;
    ring.rotation.z = st.t * 0.3;
  };
  return g;
}

/**
 * 野怪模型成品
 * @param {string} type 'redBuff'|'blueBuff'|'small'|'tyrant'|'overlord'
 */
export function createMonsterModel(type) {
  switch (type) {
    case 'redBuff':  return _createGolem(0x8a3a2e, 0xff5030);
    case 'blueBuff': return _createGolem(0x2e4a8a, 0x40a0ff);
    case 'tyrant':   return _createDragon(0x6a4a9a, 0xc090ff, 1.0);
    case 'overlord': return _createDragon(0x5a4a30, 0xffd060, 1.35);
    case 'small':
    default:         return _createLizard();
  }
}
