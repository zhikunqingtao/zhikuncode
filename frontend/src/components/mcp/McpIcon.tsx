interface McpIconProps {
  className?: string;
}

/** A compact MCP badge that stays recognizable at toolbar-icon size. */
export function McpIcon({ className }: McpIconProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 40 28"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect x="1" y="1" width="38" height="26" rx="5" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.5" />
      <text
        x="20"
        y="19"
        fill="currentColor"
        fontFamily="ui-sans-serif, system-ui, sans-serif"
        fontSize="11"
        fontWeight="800"
        letterSpacing="0.5"
        textAnchor="middle"
      >
        MCP
      </text>
    </svg>
  );
}
