import { useEffect, useRef, type ReactNode } from "react";

/**
 * A modal sheet on the native <dialog>: the browser traps focus, closes it on Escape, makes the
 * page behind it inert and returns focus to the button that opened it. Its content is only
 * rendered while it is open, so each opening fetches fresh data.
 */
export function Sheet({
  id,
  title,
  open,
  onClose,
  children,
}: {
  id: string;
  title: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog ref={dialog} id={id} className="sheet" aria-labelledby={`${id}-title`} onClose={onClose}>
      <div className="sheet-header">
        <h2 id={`${id}-title`}>{title}</h2>
        <button type="button" className="sheet-close" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="sheet-body">{open && children}</div>
    </dialog>
  );
}
