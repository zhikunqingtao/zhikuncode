#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const releasePath = join(assetRoot, 'release-assets.json');
const outputRoot = join(assetRoot, 'videos/storyboard-frames');

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

if (!existsSync(releasePath)) throw new Error(`Missing ${releasePath}`);
const release = JSON.parse(readFileSync(releasePath, 'utf8'));
mkdirSync(outputRoot, { recursive: true });

const fractions = [0.15, 0.38, 0.62, 0.85];
const storyboards = [];
for (let videoIndex = 0; videoIndex < release.assets.length; videoIndex += 1) {
  const asset = release.assets[videoIndex];
  if (!asset.previewPath || !asset.previewMedia?.durationSeconds) throw new Error(`Preview metadata incomplete: ${asset.original}`);
  const source = join(assetRoot, asset.previewPath);
  const frames = [];
  for (let frameIndex = 0; frameIndex < fractions.length; frameIndex += 1) {
    const previewSeconds = Number((asset.previewMedia.durationSeconds * fractions[frameIndex]).toFixed(3));
    const originalSeconds = Number((previewSeconds * asset.speed).toFixed(3));
    const name = `${String(videoIndex + 1).padStart(2, '0')}-${String(frameIndex + 1).padStart(2, '0')}.jpg`;
    const target = join(outputRoot, name);
    execFileSync('ffmpeg', [
      '-hide_banner', '-loglevel', 'error', '-y', '-ss', String(previewSeconds), '-i', source,
      '-frames:v', '1', '-vf', 'scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2:black',
      '-q:v', '3', target,
    ]);
    frames.push({
      path: `videos/storyboard-frames/${name}`,
      previewSeconds,
      originalApproxSeconds: originalSeconds,
      extractionFraction: fractions[frameIndex],
      sha256: sha256(target),
      sizeBytes: readFileSync(target).length,
      width: 640,
      height: 360,
    });
  }
  storyboards.push({
    original: asset.original,
    originalSha256: asset.originalSha256,
    preview: asset.preview,
    previewSha256: asset.previewSha256,
    classification: asset.classification,
    speed: asset.speed,
    frameTimeBasis: 'DERIVED_FROM_PREVIEW_TIMECODE_ORIGINAL_APPROX_EQUALS_PREVIEW_SECONDS_TIMES_SPEED',
    ffmpegCommandTemplate: 'ffmpeg -ss <previewSeconds> -i <preview> -frames:v 1 -vf scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2:black -q:v 3 <target.jpg>',
    frames,
  });
}

const output = {
  schemaVersion: 1,
  classification: 'DERIVED_VIDEO_STORYBOARD_FRAMES_NOT_INDEPENDENT_CAPTURE_EVIDENCE',
  fixedFractions: fractions,
  videos: storyboards,
};
writeFileSync(join(assetRoot, 'video-storyboards.json'), `${JSON.stringify(output, null, 2)}\n`);
console.log(JSON.stringify({ videos: storyboards.length, frames: storyboards.flatMap((item) => item.frames).length }, null, 2));
