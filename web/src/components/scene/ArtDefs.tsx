/**
 * Gradients shared by every SVG in the scene, defined once so their ids stay unique in the page.
 * Kept out of the layout (not display: none, which would stop some browsers painting them).
 */
export function ArtDefs() {
  return (
    <svg className="art-defs" width="0" height="0" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="bj-brass" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#e4c46f" />
          <stop offset="0.5" stopColor="#c9a24a" />
          <stop offset="1" stopColor="#8a6a24" />
        </linearGradient>
        <linearGradient id="bj-enamel" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#357a71" />
          <stop offset="1" stopColor="#1f524c" />
        </linearGradient>
        <linearGradient id="bj-glass" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#7fe0d0" stopOpacity="0.1" />
          <stop offset="0.5" stopColor="#7fe0d0" stopOpacity="0.02" />
          <stop offset="1" stopColor="#7fe0d0" stopOpacity="0.08" />
        </linearGradient>
        <linearGradient id="bj-steel" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#8d9a96" />
          <stop offset="1" stopColor="#4c5855" />
        </linearGradient>
        <radialGradient id="bj-lamp" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.9" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
        </radialGradient>
      </defs>
    </svg>
  );
}
