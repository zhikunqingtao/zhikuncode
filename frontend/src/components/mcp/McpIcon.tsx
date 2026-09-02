interface McpIconProps {
  className?: string;
}

/** A compact MCP badge that stays recognizable at toolbar-icon size. */
export function McpIcon({ className }: McpIconProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 32 20"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect x="1" y="2" width="30" height="16" rx="4" stroke="currentColor" strokeWidth="1.75" />
      <path d="M5 0.75V2M27 0.75V2M5 18V19.25M27 18V19.25" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" />
      <text
        x="16"
        y="13.25"
        fill="currentColor"
        fontFamily="ui-sans-serif, system-ui, sans-serif"
        fontSize="8.5"
        fontWeight="800"
        letterSpacing="0.45"
        textAnchor="middle"
      >
        MCP
      </text>
    </svg>
  );
}
