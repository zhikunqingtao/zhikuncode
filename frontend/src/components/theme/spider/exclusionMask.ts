import * as THREE from 'three';

/** One viewport texture protects every control, independent of shader uniform limits. */
export class SpiderExclusionMask {
    readonly texture: THREE.CanvasTexture;
    private readonly canvas = document.createElement('canvas');
    private readonly context: CanvasRenderingContext2D;
    private readonly coordinates: number[] = [];
    private width = 0;
    private height = 0;

    constructor() {
        const context = this.canvas.getContext('2d');
        if (!context) throw new Error('Spider exclusion mask unavailable');
        this.context = context;
        this.texture = new THREE.CanvasTexture(this.canvas);
        this.texture.minFilter = THREE.LinearFilter;
        this.texture.magFilter = THREE.LinearFilter;
        this.texture.generateMipmaps = false;
    }

    update(width: number, height: number, rects: readonly DOMRect[]): void {
        width = Math.max(1, Math.ceil(width));
        height = Math.max(1, Math.ceil(height));
        let changed = width !== this.width || height !== this.height || this.coordinates.length !== rects.length * 4;
        for (let i = 0; i < rects.length; i++) {
            const rect = rects[i], offset = i * 4;
            if (this.coordinates[offset] !== rect.left || this.coordinates[offset + 1] !== rect.top
                || this.coordinates[offset + 2] !== rect.width || this.coordinates[offset + 3] !== rect.height) changed = true;
            this.coordinates[offset] = rect.left; this.coordinates[offset + 1] = rect.top;
            this.coordinates[offset + 2] = rect.width; this.coordinates[offset + 3] = rect.height;
        }
        if (!changed) return;
        this.coordinates.length = rects.length * 4;
        this.width = width; this.height = height;
        // CSS pixels match DOM Range coordinates at every browser zoom / device pixel ratio.
        if (this.canvas.width !== width) this.canvas.width = width;
        if (this.canvas.height !== height) this.canvas.height = height;
        const ctx = this.context;
        ctx.clearRect(0, 0, width, height);
        ctx.fillStyle = '#fff';
        ctx.shadowColor = '#fff';
        ctx.shadowBlur = 12;
        // Opaque inside and slightly outside the control, with a soft outer boundary.
        // Source-over naturally unions overlapping controls and selection fragments.
        for (const rect of rects) {
            ctx.fillRect(rect.left - 8, rect.top - 8, rect.width + 16, rect.height + 16);
        }
        this.texture.needsUpdate = true;
    }

    dispose(): void {
        this.texture.dispose();
        this.canvas.width = this.canvas.height = 1;
        this.coordinates.length = 0;
        this.width = this.height = 0;
    }
}
