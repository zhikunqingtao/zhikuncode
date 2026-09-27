/** 与 MCP、记忆入口保持一致的紧凑徽章。 */
export function SkillIcon({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" className={className} viewBox="0 0 52 28" fill="none" xmlns="http://www.w3.org/2000/svg">
      <rect x="1" y="1" width="50" height="26" rx="5" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.5" />
      <text x="26" y="19" fill="currentColor" fontFamily="ui-sans-serif, system-ui, sans-serif" fontSize="11" fontWeight="800" letterSpacing="0.5" textAnchor="middle">
        SKILL
      </text>
    </svg>
  );
}
