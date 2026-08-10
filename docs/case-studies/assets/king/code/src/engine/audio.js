// ============================================================
// 音频引擎（阶段4）
// WebAudio 程序化合成音效（无任何音频文件）：
//   hit 普攻命中(短促噪声+正弦) / skill 技能施放(扫频) / kill 击杀(上扬琶音)
//   multikill 多杀(更华丽琶音) / tower 塔毁(低频轰鸣) / recall 回城(上行音阶)
//   victory 胜利 / defeat 失败(旋律片段) / flash 闪现 / heal 恢复 / buy 购买
// TTS 播报：speechSynthesis zh-CN，失败/不可用静默降级，绝不报错
// 统一 AudioContext，首次用户手势后 unlock() resume
// ============================================================

export class AudioEngine {
  constructor() {
    this.ctx = null;          // AudioContext 延迟创建（需用户手势）
    this.master = null;       // 总增益
    this.enabled = true;      // 静音开关
    this._noiseBuf = null;
    this._unlocked = false;
  }

  /** 用户手势后调用，解锁 AudioContext（可多次调用，安全） */
  unlock() {
    if (this._unlocked && this.ctx && this.ctx.state === 'running') return;
    try {
      if (!this.ctx) {
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        this.ctx = new AC();
        this.master = this.ctx.createGain();
        this.master.gain.value = 0.5;
        this.master.connect(this.ctx.destination);
        // 白噪声缓冲（命中/塔毁等用）
        const len = this.ctx.sampleRate * 0.5;
        this._noiseBuf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
        const d = this._noiseBuf.getChannelData(0);
        for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
      }
      if (this.ctx.state === 'suspended') this.ctx.resume();
      this._unlocked = true;
    } catch (e) { /* 静默降级 */ }
  }

  /** 静音开关 @returns {boolean} 当前是否启用 */
  toggle() {
    this.enabled = !this.enabled;
    if (!this.enabled && window.speechSynthesis) {
      try { speechSynthesis.cancel(); } catch (e) { /* ignore */ }
    }
    return this.enabled;
  }

  // ---------------- 合成原语 ----------------
  /** 单音 @param o {freq, freqEnd, type, t0, dur, vol, slideType} */
  _tone(o) {
    if (!this.ctx || !this.enabled) return;
    try {
      const t0 = this.ctx.currentTime + (o.t0 || 0);
      const osc = this.ctx.createOscillator();
      const g = this.ctx.createGain();
      osc.type = o.type || 'sine';
      osc.frequency.setValueAtTime(o.freq, t0);
      if (o.freqEnd) osc.frequency.exponentialRampToValueAtTime(Math.max(20, o.freqEnd), t0 + o.dur);
      const vol = o.vol || 0.25;
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(vol, t0 + (o.attack || 0.008));
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + o.dur);
      osc.connect(g); g.connect(this.master);
      osc.start(t0); osc.stop(t0 + o.dur + 0.02);
    } catch (e) { /* 静默 */ }
  }

  /** 噪声 @param o {t0, dur, vol, freq(低通), q} */
  _noise(o) {
    if (!this.ctx || !this.enabled || !this._noiseBuf) return;
    try {
      const t0 = this.ctx.currentTime + (o.t0 || 0);
      const src = this.ctx.createBufferSource();
      src.buffer = this._noiseBuf;
      src.loop = true;
      const f = this.ctx.createBiquadFilter();
      f.type = o.hp ? 'highpass' : 'lowpass';
      f.frequency.value = o.freq || 1200;
      const g = this.ctx.createGain();
      const vol = o.vol || 0.2;
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(vol, t0 + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + o.dur);
      src.connect(f); f.connect(g); g.connect(this.master);
      src.start(t0); src.stop(t0 + o.dur + 0.02);
    } catch (e) { /* 静默 */ }
  }

  /** 琶音 @param frets 频率数组 @param step 间隔 @param o {type, vol, dur, t0} */
  _arp(frets, step, o = {}) {
    const base = o.t0 || 0;
    frets.forEach((f, i) => this._tone({
      freq: f, t0: base + i * step, dur: o.dur || step * 1.8,
      type: o.type || 'triangle', vol: o.vol || 0.22,
    }));
  }

  // ---------------- 具名音效 ----------------
  /** 播放合成音效 @param {string} name */
  play(name) {
    if (!this.enabled) return;
    if (!this.ctx || this.ctx.state !== 'running') return;
    try {
      switch (name) {
        case 'hit':   // 普攻命中：短促噪声+正弦
          this._noise({ dur: 0.07, vol: 0.16, freq: 2600 });
          this._tone({ freq: 190, freqEnd: 120, type: 'sine', dur: 0.08, vol: 0.2 });
          break;
        case 'skill': // 技能施放：扫频
          this._tone({ freq: 280, freqEnd: 980, type: 'sawtooth', dur: 0.2, vol: 0.14 });
          this._tone({ freq: 560, freqEnd: 1400, type: 'sine', dur: 0.18, vol: 0.1 });
          break;
        case 'kill':  // 击杀：上扬琶音
          this._arp([523, 659, 784], 0.09, { type: 'triangle', vol: 0.26 });
          break;
        case 'multikill': // 多杀：更华丽琶音
          this._arp([523, 659, 784, 1047, 1319], 0.08, { type: 'triangle', vol: 0.28 });
          this._tone({ freq: 2093, t0: 0.42, dur: 0.4, type: 'sine', vol: 0.16 });
          break;
        case 'tower': // 塔毁：低频轰鸣
          this._tone({ freq: 90, freqEnd: 38, type: 'sine', dur: 0.9, vol: 0.4 });
          this._noise({ dur: 0.7, vol: 0.22, freq: 320 });
          break;
        case 'recall': // 回城：上行音阶
          this._arp([392, 494, 587, 784], 0.11, { type: 'sine', vol: 0.18, dur: 0.24 });
          break;
        case 'flash': // 闪现：快速滑音
          this._tone({ freq: 1400, freqEnd: 300, type: 'square', dur: 0.12, vol: 0.1 });
          this._noise({ dur: 0.09, vol: 0.08, freq: 3600, hp: true });
          break;
        case 'heal':  // 恢复：柔和上行
          this._arp([440, 554, 659], 0.1, { type: 'sine', vol: 0.14, dur: 0.26 });
          break;
        case 'buy':   // 购买：金币脆响
          this._tone({ freq: 1568, dur: 0.06, type: 'square', vol: 0.1 });
          this._tone({ freq: 2093, t0: 0.06, dur: 0.12, type: 'square', vol: 0.09 });
          break;
        case 'victory': // 胜利旋律
          this._arp([523, 659, 784, 1047], 0.16, { type: 'triangle', vol: 0.3, dur: 0.34 });
          this._tone({ freq: 1319, t0: 0.66, dur: 0.9, type: 'triangle', vol: 0.26 });
          break;
        case 'defeat':  // 失败旋律（下行小调）
          this._arp([440, 415, 349, 262], 0.22, { type: 'triangle', vol: 0.24, dur: 0.4 });
          break;
        case 'objective': // 暴君/主宰：低沉鼓点+上行
          this._tone({ freq: 120, freqEnd: 60, type: 'sine', dur: 0.3, vol: 0.3 });
          this._arp([330, 494, 659], 0.1, { type: 'triangle', vol: 0.18, t0: 0.1 });
          break;
      }
    } catch (e) { /* 静默 */ }
  }

  /** TTS 播报 @param {string} text zh-CN 文本（不可用静默跳过，绝不报错） */
  speak(text) {
    if (!this.enabled || !text) return;
    try {
      if (!('speechSynthesis' in window)) return;
      const u = new SpeechSynthesisUtterance(text);
      u.lang = 'zh-CN';
      u.rate = 1.1;
      u.volume = 0.8;
      speechSynthesis.cancel();   // 避免播报积压，最新优先
      speechSynthesis.speak(u);
    } catch (e) { /* 静默降级 */ }
  }
}
