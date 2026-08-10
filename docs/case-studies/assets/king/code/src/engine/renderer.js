// ============================================================
// 渲染器：WebGLRenderer / 灯光 / 雾 / 天空 / 相机 rig
// 相机为锁定跟随视角：俯仰 55°，固定偏航由蓝方看向红方
// ============================================================
import * as THREE from 'three';
import { CAMERA } from '../config.js';
import { damp } from '../utils.js';

const SKY_COLOR = 0x8fb9dd;   // 天空蓝
const FOG_COLOR = 0x9dc0d8;   // 远景雾色（与天空衔接）

export class EngineRenderer {
  constructor(container) {
    // --- 渲染器 ---
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setSize(window.innerWidth, window.innerHeight);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.08;
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    container.appendChild(this.renderer.domElement);

    // p5b：软件渲染环境（headless SwiftShader/llvmpipe）自适应降质——
    // 软渲染填率极低（实测 1280×800 全画质仅 ~12fps），关阴影+降像素比保可玩帧率；
    // 真实 GPU 浏览器不受影响（241 draw calls / 1.8 万三角形，轻松 60fps 全画质）
    let softGL = false;
    try {
      const gl = this.renderer.getContext();
      const dbg = gl.getExtension('WEBGL_debug_renderer_info');
      const rn = dbg ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)) : '';
      softGL = /swiftshader|llvmpipe|softpipe|software/i.test(rn);
    } catch (e) { /* 检测失败按硬件处理 */ }
    this.softGL = softGL;
    if (softGL) {
      this.renderer.setPixelRatio(0.5);
      this.renderer.shadowMap.enabled = false;
    }

    // --- 场景 / 雾 / 天空 ---
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(SKY_COLOR);
    this.scene.fog = new THREE.Fog(FOG_COLOR, 62, 150);   // p5：雾距收紧，地图边缘更有层次

    // --- 相机 ---
    this.camera = new THREE.PerspectiveCamera(
      CAMERA.FOV, window.innerWidth / window.innerHeight, CAMERA.NEAR, CAMERA.FAR);

    // 固定偏航方向（世界 xz 平面，指向红方）
    const yawLen = Math.hypot(CAMERA.YAW_DIR.x, CAMERA.YAW_DIR.z);
    this._yawDir = new THREE.Vector3(CAMERA.YAW_DIR.x / yawLen, 0, CAMERA.YAW_DIR.z / yawLen);
    const horiz = CAMERA.DIST * Math.cos(CAMERA.PITCH);
    const vert = CAMERA.DIST * Math.sin(CAMERA.PITCH);
    this._camOffset = new THREE.Vector3(-this._yawDir.x * horiz, vert, -this._yawDir.z * horiz);

    // 跟随状态
    this._followObj = null;               // THREE.Object3D（跟随其 position）
    this._smoothPos = new THREE.Vector3();// 平滑后的跟随点
    this._lookAhead = new THREE.Vector3();
    this._initialized = false;

    // --- 灯光 ---
    // 半球光（p5 冷色补光：天空冷蓝 / 地面冷灰绿，拉开冷暖对比）
    this.hemi = new THREE.HemisphereLight(0xb8d4ff, 0x4a5c48, 0.85);
    this.scene.add(this.hemi);

    // 方向光（太阳，p5 更暖；阴影只覆盖相机周围 40 单位，跟随更新）
    this.sun = new THREE.DirectionalLight(0xffe2b0, 2.7);
    this._sunOffset = new THREE.Vector3(38, 62, -26);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(1024, 1024);   // p5：1024 足够（阴影范围缩小后精度反而更高）
    const sc = this.sun.shadow.camera;
    sc.left = -40; sc.right = 40; sc.top = 40; sc.bottom = -40;
    sc.near = 10; sc.far = 180;
    this.sun.shadow.bias = -0.0015;
    this.sun.shadow.normalBias = 0.02;
    this.scene.add(this.sun);
    this.scene.add(this.sun.target);

    // 环境补光（提亮暗部，避免死黑）
    this.amb = new THREE.AmbientLight(0x8098b8, 0.3);
    this.scene.add(this.amb);
  }

  /** 当前相机观察中心（小地图视野框用） */
  get viewCenter() { return this._smoothPos; }

  /** 设置相机跟随目标（英雄模型等 Object3D） */
  setFollowTarget(obj) {
    this._followObj = obj;
    if (obj) {
      this._smoothPos.copy(obj.position);
      this._initialized = true;
      this.update(0);
    }
  }

  /** 每帧更新相机与阴影相机位置 */
  update(dt) {
    if (!this._followObj) return;
    const tp = this._followObj.position;

    if (dt > 0) {
      const t = damp(CAMERA.LERP, dt);
      this._smoothPos.x += (tp.x - this._smoothPos.x) * t;
      this._smoothPos.z += (tp.z - this._smoothPos.z) * t;
      this._smoothPos.y += (tp.y - this._smoothPos.y) * t;
    }

    // 相机位于跟随点反偏航方向的斜上方
    this.camera.position.copy(this._smoothPos).add(this._camOffset);
    this.camera.lookAt(
      this._smoothPos.x + this._lookAhead.x,
      this._smoothPos.y + 1,
      this._smoothPos.z + this._lookAhead.z);

    // 阴影相机跟随玩家，避免阴影范围浪费
    this.sun.position.copy(this._smoothPos).add(this._sunOffset);
    this.sun.target.position.copy(this._smoothPos);
    this.sun.target.updateMatrixWorld();
  }

  resize() {
    const w = window.innerWidth, h = window.innerHeight;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h);
  }

  render() {
    this.renderer.render(this.scene, this.camera);
  }
}
