import type { ReactNode } from "react";

/** What Penny is doing with her hands while a bank tool runs. */
export type ToolPose = "lookup" | "balance" | "verify" | "document" | "phone" | "transfer" | "flag";
export type OutcomePose = "won" | "blocked" | "refused";
export type Pose = "idle" | "thinking" | ToolPose | OutcomePose;

// Things Penny holds up in her right hand, drawn around the hand's centre.
const HELD: Record<ToolPose, ReactNode> = {
  lookup: (
    <g>
      <rect x="-24" y="-46" width="48" height="32" rx="3" fill="#f6f1e4" stroke="#8a6a24" strokeWidth="2" />
      <line x1="-16" y1="-36" x2="14" y2="-36" stroke="#8a6a24" strokeWidth="2" />
      <line x1="-16" y1="-28" x2="8" y2="-28" stroke="#c9b98f" strokeWidth="2" />
      <line x1="-16" y1="-21" x2="12" y2="-21" stroke="#c9b98f" strokeWidth="2" />
    </g>
  ),
  balance: (
    <g>
      <rect x="-24" y="-50" width="48" height="36" rx="3" fill="#2f5f4a" stroke="#c9a24a" strokeWidth="2" />
      <line x1="0" y1="-50" x2="0" y2="-14" stroke="#c9a24a" strokeWidth="2" />
      <line x1="-18" y1="-40" x2="-6" y2="-40" stroke="#dfe9d6" strokeWidth="2" />
      <line x1="-18" y1="-32" x2="-6" y2="-32" stroke="#dfe9d6" strokeWidth="2" />
      <line x1="6" y1="-40" x2="18" y2="-40" stroke="#dfe9d6" strokeWidth="2" />
    </g>
  ),
  verify: (
    <g>
      <rect x="-26" y="-46" width="52" height="34" rx="4" fill="#e9e1cf" stroke="#2f5f8c" strokeWidth="2" />
      <rect x="-21" y="-41" width="15" height="20" rx="2" fill="#2f5f8c" />
      <line x1="-2" y1="-36" x2="20" y2="-36" stroke="#2f5f8c" strokeWidth="2" />
      <line x1="-2" y1="-28" x2="14" y2="-28" stroke="#8f8775" strokeWidth="2" />
    </g>
  ),
  document: (
    <g>
      <path d="M-20 -62 h30 l10 10 v44 h-40 Z" fill="#f6f1e4" stroke="#8f8775" strokeWidth="2" />
      <path d="M10 -62 v10 h10" fill="none" stroke="#8f8775" strokeWidth="2" />
      <line x1="-13" y1="-44" x2="12" y2="-44" stroke="#8f8775" strokeWidth="2" />
      <line x1="-13" y1="-36" x2="12" y2="-36" stroke="#8f8775" strokeWidth="2" />
      <line x1="-13" y1="-28" x2="4" y2="-28" stroke="#8f8775" strokeWidth="2" />
    </g>
  ),
  phone: (
    <g>
      <rect x="-9" y="-62" width="18" height="50" rx="7" fill="#1b2b26" stroke="#c9a24a" strokeWidth="2" />
      <rect x="-13" y="-64" width="26" height="12" rx="5" fill="#1b2b26" />
      <rect x="-13" y="-22" width="26" height="12" rx="5" fill="#1b2b26" />
    </g>
  ),
  transfer: (
    <g className="stamp">
      <rect x="-7" y="-60" width="14" height="24" rx="5" fill="#6b4a2a" />
      <rect x="-18" y="-38" width="36" height="12" rx="2" fill="#3a2a10" />
      <rect x="-18" y="-26" width="36" height="5" fill="#b33a2a" />
    </g>
  ),
  flag: (
    <g>
      <rect x="-2" y="-78" width="4" height="70" fill="#8a6a24" />
      <path d="M2 -78 h34 l-9 10 l9 10 h-34 Z" fill="#d9534f" />
    </g>
  ),
};

const MOUTHS: Partial<Record<Pose, ReactNode>> = {
  thinking: <circle cx="300" cy="337" r="5" fill="none" stroke="#163a35" strokeWidth="4" />,
  won: <path d="M282 330 Q300 354 318 330 Z" fill="#163a35" />,
  blocked: (
    <path
      d="M285 339 Q292 333 300 339 Q308 345 315 339"
      fill="none"
      stroke="#163a35"
      strokeWidth="4"
      strokeLinecap="round"
    />
  ),
  refused: (
    <path d="M290 334 Q300 341 310 334" fill="none" stroke="#163a35" strokeWidth="4" strokeLinecap="round" />
  ),
};

function Eyes({ pose }: { pose: Pose }) {
  if (pose === "won") {
    return (
      <g fill="none" stroke="#163a35" strokeWidth="5" strokeLinecap="round">
        <path d="M268 310 Q279 294 290 310" />
        <path d="M310 310 Q321 294 332 310" />
      </g>
    );
  }
  if (pose === "refused") {
    return (
      <g fill="none" stroke="#163a35" strokeWidth="5" strokeLinecap="round">
        <path d="M268 304 Q279 314 290 304" />
        <path d="M310 304 Q321 314 332 304" />
      </g>
    );
  }
  return (
    <g className="eyes">
      <circle cx="279" cy="306" r="12" fill="#163a35" />
      <circle cx="321" cy="306" r="12" fill="#163a35" />
      <g className="pupils">
        <circle cx="279" cy="306" r="5" fill="#7fe0d0" />
        <circle cx="321" cy="306" r="5" fill="#7fe0d0" />
      </g>
      {pose === "blocked" && (
        <g stroke="#163a35" strokeWidth="4" strokeLinecap="round">
          <line x1="266" y1="284" x2="288" y2="290" />
          <line x1="334" y1="284" x2="312" y2="290" />
        </g>
      )}
    </g>
  );
}

const TOOL_POSES = new Set<Pose>(["lookup", "balance", "verify", "document", "phone", "transfer", "flag"]);

/**
 * Penny, a brass-and-enamel automaton, in the coordinates of the teller window. The pose picks
 * her face and what she holds; CSS (penny.css) moves her arms and runs the idle animations,
 * keyed off data-pose and data-decision, so prefers-reduced-motion can switch all motion off.
 */
export function Penny({ pose, decision }: { pose: Pose; decision?: "allow" | "deny" }) {
  const held = TOOL_POSES.has(pose) ? HELD[pose as ToolPose] : null;
  return (
    <g className="penny" data-pose={pose} data-decision={decision}>
      <g className="penny-body">
        <rect x="192" y="380" width="216" height="180" rx="40" fill="url(#bj-enamel)" />
        <path d="M270 382 L300 450 L330 382 Z" fill="#f3ecd9" />
        <path d="M284 390 L300 398 L316 390 L316 408 L300 400 L284 408 Z" fill="url(#bj-brass)" />
        <rect x="342" y="430" width="48" height="18" rx="3" fill="#f3ecd9" />
        <text x="366" y="443" textAnchor="middle" className="art-mono" fontSize="10" fill="#163a35">
          PENNY
        </text>
        <circle cx="216" cy="420" r="4" fill="#c9a24a" />
        <circle cx="384" cy="470" r="4" fill="#c9a24a" />

        <g className="arm arm-left">
          <rect x="158" y="400" width="40" height="130" rx="20" fill="url(#bj-enamel)" />
          <circle cx="178" cy="528" r="18" fill="url(#bj-brass)" />
        </g>
        <g className="arm arm-right">
          <rect x="402" y="400" width="40" height="130" rx="20" fill="url(#bj-enamel)" />
          <circle cx="422" cy="528" r="18" fill="url(#bj-brass)" />
          {/* Counter-rotated against the raised arm, so the item stays upright. */}
          {held && (
            <g transform="translate(422 528) rotate(140)">
              <g className="held">{held}</g>
            </g>
          )}
        </g>

        <g className="penny-head">
          <rect x="288" y="360" width="24" height="26" fill="#8a6a24" />
          <line x1="300" y1="250" x2="300" y2="226" stroke="#8a6a24" strokeWidth="5" />
          <circle className="lamp-glow" cx="300" cy="220" r="20" fill="url(#bj-lamp)" />
          <circle className="lamp" cx="300" cy="220" r="9" />
          <rect x="232" y="246" width="136" height="122" rx="38" fill="url(#bj-brass)" />
          <rect x="248" y="266" width="104" height="90" rx="28" fill="#f3ecd9" />
          <path d="M226 270 Q300 238 374 270 L374 282 Q300 256 226 282 Z" fill="#2f8f6f" opacity="0.85" />
          <Eyes pose={pose} />
          <circle cx="264" cy="330" r="7" fill="#e88a7a" opacity={pose === "won" ? 0.75 : 0.4} />
          <circle cx="336" cy="330" r="7" fill="#e88a7a" opacity={pose === "won" ? 0.75 : 0.4} />
          {MOUTHS[pose] ?? (
            <path d="M285 332 Q300 345 315 332" fill="none" stroke="#163a35" strokeWidth="4" strokeLinecap="round" />
          )}
        </g>
      </g>

      {pose === "thinking" && (
        <g className="thought" fill="#f6f1e4">
          <circle cx="388" cy="248" r="5" />
          <circle cx="404" cy="234" r="7" />
          <circle cx="424" cy="216" r="9" />
        </g>
      )}
      {pose === "won" && (
        <g className="coins" fill="url(#bj-brass)" stroke="#8a6a24" strokeWidth="2">
          <circle cx="270" cy="520" r="9" />
          <circle cx="300" cy="520" r="9" />
          <circle cx="330" cy="520" r="9" />
        </g>
      )}
    </g>
  );
}
