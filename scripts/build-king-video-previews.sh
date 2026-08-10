#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="/Users/guoqingtao/Desktop/dev/project/过程录屏"
output_dir="$repo_root/docs/case-studies/assets/king/videos/previews"
mkdir -p "$output_dir"

encode() {
  local input="$1"
  local output="$2"
  local speed="$3"
  local audio_mode="$4"
  local temp="$output_dir/.${output}.tmp.mp4"
  local video_filter="scale=960:540:force_original_aspect_ratio=decrease,pad=960:540:(ow-iw)/2:(oh-ih)/2,setpts=PTS/${speed},fps=30"
  local args=(-y -i "$source_dir/$input" -map_metadata -1 -vf "$video_filter" -c:v libx264 -preset medium -crf 27 -pix_fmt yuv420p -fps_mode cfr -movflags +faststart)

  if [[ "$audio_mode" == "none" ]]; then
    args+=(-an)
  elif [[ "$speed" == "4" ]]; then
    args+=(-af "atempo=2.0,atempo=2.0" -c:a aac -b:a 96k)
  elif [[ "$speed" == "2" ]]; then
    args+=(-af "atempo=2.0" -c:a aac -b:a 96k)
  else
    args+=(-c:a aac -b:a 96k)
  fi

  ffmpeg "${args[@]}" "$temp"
  mv "$temp" "$output_dir/$output"
  local size
  size=$(stat -f %z "$output_dir/$output")
  if (( size >= 47185920 )); then
    echo "Preview exceeds 45 MiB: $output ($size bytes)" >&2
    exit 1
  fi
  echo "$output | $size bytes"
}

encode '钉钉录屏_2026-08-09 013111.mp4' '01-开发过程-4x.mp4' 4 none
encode '钉钉录屏_2026-08-09 054310.mp4' '02-玩法开发-2x.mp4' 2 audio
encode '钉钉录屏_2026-08-09 060909.mp4' '03-视觉修复-1x.mp4' 1 audio
encode '钉钉录屏_2026-08-09 092155（最终运行版）.mp4' '04-最终运行-4x.mp4' 4 none
encode '阿里云在线试玩录屏.mp4' '05-阿里云在线试玩-2x.mp4' 2 audio
