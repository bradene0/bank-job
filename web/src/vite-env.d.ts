/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** game-api's public URL, e.g. https://bankjob-live.bradenevans.dev. */
  readonly VITE_API_URL?: string;
  /** The wake endpoint that starts the sleeping backend (/api/wake in production). */
  readonly VITE_WAKE_URL?: string;
}
