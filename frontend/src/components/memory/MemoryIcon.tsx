interface MemoryIconProps {
  className?: string;
}

/**
 * Memory 入口徽章 — 与 McpIcon 同构的 badge 风格：
 * 圆角描边框内嵌 Brain glyph + "记忆"汉字，一体成型。
 * 全部使用 currentColor，随按钮文字色自动适配 light/dark/glass 皮肤。
 */
export function MemoryIcon({ className }: MemoryIconProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 58 28"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect x="1" y="1" width="56" height="26" rx="5" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.5" />
      {/* Brain glyph（lucide brain 路径，24 网格缩放至 ~18px，居左；strokeWidth 按缩放比补偿） */}
      <g
        transform="translate(7,5) scale(0.75)"
        stroke="currentColor"
        strokeWidth="2.3"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z" />
        <path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z" />
        <path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4" />
        <path d="M17.599 6.5a3 3 0 0 0 .399-1.375" />
        <path d="M6.003 5.125A3 3 0 0 0 6.401 6.5" />
        <path d="M3.477 10.896a4 4 0 0 1 .585-.396" />
        <path d="M19.938 10.5a4 4 0 0 1 .585.396" />
        <path d="M6 18a4 4 0 0 1-1.967-.516" />
        <path d="M19.967 17.484A4 4 0 0 1 18 18" />
      </g>
      <text
        x="39"
        y="19"
        fill="currentColor"
        fontFamily="ui-sans-serif, system-ui, sans-serif"
        fontSize="11"
        fontWeight="700"
        letterSpacing="0.5"
        textAnchor="middle"
      >
        记忆
      </text>
    </svg>
  );
}
