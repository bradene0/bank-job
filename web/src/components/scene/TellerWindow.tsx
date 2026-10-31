import type { Control, ControlKey } from "../../content/types";
import { Penny, type Pose } from "./Penny";

interface TellerWindowProps {
  level: number;
  controls: Control[];
  pose: Pose;
  /** The bank tool Penny is calling right now, if any, and the policy engine's decision on it. */
  tool?: string;
  decision?: "allow" | "deny";
  /** The Level 5 guardrail agent is reviewing the current call. */
  reviewing?: boolean;
}

/**
 * Penny's window, drawn in a 600 x 560 box: the brass arch with its fan grille, Penny behind the
 * glass, and one prop per control the level enforces (content/levels.yaml), which light up when
 * the matching tool call runs. Decorative detail only; the HUD says the same things in words.
 */
export function TellerWindow({ level, controls, pose, tool, decision, reviewing = false }: TellerWindowProps) {
  const has = (key: ControlKey) => controls.some((c) => c.key === key);
  const limit = /\$[\d,]+/.exec(controls.find((c) => c.key === "transfer_limit_cents")?.text ?? "")?.[0];
  const transferring = tool === "transfer_funds";
  return (
    <svg
      className="teller-window"
      viewBox="0 0 600 560"
      role="img"
      aria-label="Penny, Brassvale's AI teller, behind her brass window"
      data-tool={tool}
    >
      <path d="M35 560 L35 250 A265 200 0 0 1 565 250 L565 560 Z" fill="url(#bj-brass)" />
      <path d="M65 560 L65 255 A235 172 0 0 1 535 255 L535 560 Z" fill="#0d1613" />

      {level === 5 ? <VaultDoor /> : <Bookshelf />}

      <g>
        <rect x="426" y="200" width="88" height="40" rx="4" fill="#1b2b26" stroke="#8a6a24" strokeWidth="2" />
        <text x="470" y="216" textAnchor="middle" className="art-mono" fontSize="10" fill="#c9a24a" letterSpacing="1">
          NOW SERVING
        </text>
        <text x="470" y="233" textAnchor="middle" className="art-mono" fontSize="15" fill="#f3d98c">
          {String(level).padStart(2, "0")}
        </text>
      </g>

      {limit && (
        <g className="prop prop-limit" data-active={transferring || undefined}>
          <rect x="86" y="200" width="88" height="40" rx="4" fill="#1b2b26" stroke="url(#bj-brass)" strokeWidth="3" />
          <text x="130" y="216" textAnchor="middle" className="art-mono" fontSize="9" fill="#c9a24a" letterSpacing="1">
            TELLER LIMIT
          </text>
          <text x="130" y="233" textAnchor="middle" className="art-mono" fontSize="14" fill="#f3d98c">
            {limit}
          </text>
        </g>
      )}

      {has("anomaly_rules") && (
        <g className="prop prop-gauge" data-active={transferring || undefined}>
          <circle cx="170" cy="150" r="22" fill="#efe6d2" stroke="url(#bj-brass)" strokeWidth="4" />
          <path d="M154 156 A16 16 0 0 1 186 156" fill="none" stroke="#d9534f" strokeWidth="3" strokeDasharray="10 4" />
          <line className="needle" x1="170" y1="156" x2="160" y2="142" stroke="#1b2b26" strokeWidth="3" strokeLinecap="round" />
          <circle cx="170" cy="156" r="3" fill="#1b2b26" />
        </g>
      )}

      {has("guardrail_agent_review") && (
        <g className="prop prop-guard" data-active={reviewing || undefined}>
          <rect x="440" y="330" width="60" height="54" rx="18" fill="url(#bj-steel)" />
          <path d="M434 334 Q470 304 506 334 L506 342 L434 342 Z" fill="#20314a" />
          <rect x="462" y="318" width="16" height="10" rx="2" fill="url(#bj-brass)" />
          <rect className="guard-eye" x="452" y="352" width="12" height="7" rx="3" />
          <rect className="guard-eye" x="476" y="352" width="12" height="7" rx="3" />
        </g>
      )}

      <Penny pose={pose} decision={decision} />

      {has("system_prompt_only") && (
        <g className="prop prop-note" transform="rotate(-7 114 484)">
          <rect x="84" y="462" width="62" height="46" fill="#f3e3a0" />
          <line x1="92" y1="476" x2="136" y2="476" stroke="#8a6a24" strokeWidth="2" />
          <line x1="92" y1="486" x2="132" y2="486" stroke="#8a6a24" strokeWidth="2" />
          <line x1="92" y1="496" x2="120" y2="496" stroke="#8a6a24" strokeWidth="2" />
        </g>
      )}

      {has("require_out_of_band_confirmation") && (
        <g
          className="prop prop-phone"
          data-active={transferring || (tool === "update_contact_info" && has("protect_contact_changes")) || undefined}
        >
          <rect x="86" y="470" width="66" height="44" rx="6" fill="#26332f" stroke="#8a6a24" strokeWidth="2" />
          <rect className="phone-screen" x="94" y="478" width="50" height="16" rx="2" />
          <text x="119" y="507" textAnchor="middle" className="art-mono" fontSize="8" fill="#c9a24a">
            CONFIRM
          </text>
          {has("protect_contact_changes") && (
            <g transform="translate(140 462)">
              <path d="M-6 0 v-5 a6 6 0 0 1 12 0 v5" fill="none" stroke="#c9a24a" strokeWidth="3" />
              <rect x="-9" y="0" width="18" height="13" rx="2" fill="url(#bj-brass)" />
            </g>
          )}
        </g>
      )}

      {has("require_verified_owner") && (
        <g className="prop prop-scanner" data-active={tool === "verify_identity" || undefined}>
          <rect x="448" y="468" width="70" height="52" rx="6" fill="#26332f" stroke="#8a6a24" strokeWidth="2" />
          <rect className="scanner-screen" x="458" y="478" width="50" height="22" rx="3" />
          <text x="483" y="514" textAnchor="middle" className="art-mono" fontSize="8" fill="#c9a24a">
            ID CHECK
          </text>
        </g>
      )}

      <path d="M65 560 L65 255 A235 172 0 0 1 535 255 L535 560 Z" fill="url(#bj-glass)" />
      <g stroke="url(#bj-brass)" strokeWidth="5" fill="none">
        <path d="M65 255 A235 172 0 0 1 535 255" />
        <line x1="300" y1="255" x2="300" y2="84" />
        <line x1="300" y1="255" x2="180" y2="110" />
        <line x1="300" y1="255" x2="420" y2="110" />
        <line x1="300" y1="255" x2="100" y2="175" />
        <line x1="300" y1="255" x2="500" y2="175" />
        <line x1="65" y1="255" x2="535" y2="255" />
      </g>
      <path d="M100 560 L220 300 L250 300 L130 560 Z" fill="#ffffff" opacity="0.05" />
      <path d="M380 560 L480 330 L495 330 L395 560 Z" fill="#ffffff" opacity="0.04" />
      <rect x="10" y="520" width="580" height="40" fill="url(#bj-brass)" />
      <rect x="230" y="530" width="140" height="12" rx="6" fill="#3a2a10" />
      <rect x="100" y="22" width="400" height="56" rx="6" fill="#0d1613" stroke="url(#bj-brass)" strokeWidth="3" />
      <text x="300" y="56" textAnchor="middle" className="art-display" fontSize="22" fill="#e4c46f" letterSpacing="2">
        BRASSVALE SAVINGS &amp; TRUST
      </text>
      <text x="300" y="71" textAnchor="middle" fontSize="10" fill="#c9b98f">
        Trusted since 1911. Automated since Tuesday.
      </text>
    </svg>
  );
}

function Bookshelf() {
  return (
    <g>
      <rect x="100" y="300" width="70" height="200" fill="#162420" />
      <rect x="106" y="320" width="58" height="10" fill="#6b4a2a" />
      <rect x="106" y="360" width="58" height="10" fill="#6b4a2a" />
      <rect x="110" y="336" width="10" height="24" fill="#8c3b2e" />
      <rect x="122" y="338" width="8" height="22" fill="#2f5f8c" />
      <rect x="132" y="334" width="11" height="26" fill="#a68a3c" />
      <rect x="110" y="376" width="12" height="26" fill="#2f5f4a" />
      <rect x="124" y="380" width="9" height="22" fill="#a68a3c" />
    </g>
  );
}

function VaultDoor() {
  return (
    <g>
      <circle cx="126" cy="372" r="52" fill="url(#bj-steel)" stroke="#2b3533" strokeWidth="6" />
      <circle cx="126" cy="372" r="36" fill="none" stroke="#2b3533" strokeWidth="3" />
      <g stroke="#c9a24a" strokeWidth="5" strokeLinecap="round">
        <line x1="126" y1="348" x2="126" y2="396" />
        <line x1="102" y1="372" x2="150" y2="372" />
        <line x1="109" y1="355" x2="143" y2="389" />
        <line x1="143" y1="355" x2="109" y2="389" />
      </g>
      <circle cx="126" cy="372" r="8" fill="url(#bj-brass)" />
    </g>
  );
}
