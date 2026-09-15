/** Rounded-lens normal map. Neutral center; refraction is confined to the rim. */
export function lensVector(x: number, y: number, width: number, height: number, radius: number) {
    const r = Math.min(radius, width / 2, height / 2);
    const dx = x - width / 2;
    const dy = y - height / 2;
    const qx = Math.abs(dx) - (width / 2 - r);
    const qy = Math.abs(dy) - (height / 2 - r);
    const ax = Math.max(qx, 0);
    const ay = Math.max(qy, 0);
    const length = Math.hypot(ax, ay);
    const distance = length + Math.min(Math.max(qx, qy), 0) - r;
    const rim = Math.min(18, Math.min(width, height) / 3);
    if (distance > 0 || distance < -rim) return [0, 0];
    const strength = Math.sin(Math.PI * -distance / rim) * .9;
    const nx = length ? ax / length : qx > qy ? 1 : 0;
    const ny = length ? ay / length : qx > qy ? 0 : 1;
    return [nx * Math.sign(dx) * strength, ny * Math.sign(dy) * strength];
}

const maps = new Map<string, string>();
export function lensMap(width: number, height: number, radius: number): string {
    const key = `${width}:${height}:${radius}`;
    const cached = maps.get(key);
    if (cached) return cached;
    const scale = Math.min(1, 768 / Math.max(width, height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(width * scale));
    canvas.height = Math.max(1, Math.round(height * scale));
    const context = canvas.getContext('2d');
    if (!context) return '';
    const pixels = context.createImageData(canvas.width, canvas.height);
    for (let y = 0; y < canvas.height; y++) {
        for (let x = 0; x < canvas.width; x++) {
            const [nx, ny] = lensVector((x + .5) / scale, (y + .5) / scale, width, height, radius);
            const i = (y * canvas.width + x) * 4;
            pixels.data[i] = Math.round(128 + nx * 127);
            pixels.data[i + 1] = Math.round(128 + ny * 127);
            pixels.data[i + 2] = 128;
            pixels.data[i + 3] = 255;
        }
    }
    context.putImageData(pixels, 0, 0);
    const url = canvas.toDataURL();
    if (maps.size >= 32) maps.delete(maps.keys().next().value!);
    maps.set(key, url);
    return url;
}
