import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Vitest runs without globals, so Testing Library cannot register its own cleanup.
afterEach(() => cleanup());

// jsdom has <dialog> but not its modal methods. (Node-environment test files have no DOM.)
const dialog = (globalThis.HTMLDialogElement?.prototype ?? {}) as Partial<HTMLDialogElement>;
if (globalThis.HTMLDialogElement && !dialog.showModal) {
  dialog.showModal = function (this: HTMLDialogElement) {
    this.open = true;
  };
  dialog.close = function (this: HTMLDialogElement) {
    this.open = false;
    this.dispatchEvent(new Event("close"));
  };
}
