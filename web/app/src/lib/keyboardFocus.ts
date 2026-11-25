/**
 * Marks the page while someone moves through it with the keyboard, so the focus ring can show
 * for them and stay away from mouse and touch, where it was asked to go (global.css). Tab and
 * the arrow keys turn it on; any pointer press turns it off again.
 */
const KEYS = new Set(['Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End']);

export function installKeyboardFocus(root: HTMLElement = document.documentElement): () => void {
  const onKey = (event: KeyboardEvent) => {
    if (KEYS.has(event.key) && !event.metaKey && !event.ctrlKey && !event.altKey) root.dataset.keyboard = 'true';
  };
  const onPointer = () => {
    delete root.dataset.keyboard;
  };
  const view = root.ownerDocument.defaultView ?? window;
  view.addEventListener('keydown', onKey, true);
  view.addEventListener('pointerdown', onPointer, true);
  return () => {
    view.removeEventListener('keydown', onKey, true);
    view.removeEventListener('pointerdown', onPointer, true);
    delete root.dataset.keyboard;
  };
}
