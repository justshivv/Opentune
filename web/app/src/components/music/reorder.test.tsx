// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dropIndex, moved, shiftFor, useReorder } from './reorder';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('reorder arithmetic', () => {
  it('moves an item up and down, and leaves bad moves alone', () => {
    expect(moved(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd']);
    expect(moved(['a', 'b', 'c', 'd'], 3, 1)).toEqual(['a', 'd', 'b', 'c']);
    expect(moved(['a', 'b'], 0, 5)).toEqual(['a', 'b']);
  });

  it('lands where the row was dragged to, within the list', () => {
    expect(dropIndex(1, 0, 55, 5)).toBe(1);
    expect(dropIndex(1, 30, 55, 5)).toBe(2);
    expect(dropIndex(1, -200, 55, 5)).toBe(0);
    expect(dropIndex(1, 1000, 55, 5)).toBe(4);
  });

  it('slides only the rows between where the row was and where it is held', () => {
    // Row 1 held over row 3: rows 2 and 3 move up one; 0 and 4 stay.
    expect([0, 1, 2, 3, 4].map((i) => shiftFor(i, 1, 3, 55))).toEqual([0, 0, -55, -55, 0]);
    // Row 3 held over row 1: rows 1 and 2 move down one.
    expect([0, 1, 2, 3, 4].map((i) => shiftFor(i, 3, 1, 55))).toEqual([0, 55, 55, 0, 0]);
  });
});

function List({ items, onMove }: { items: string[]; onMove: (from: number, to: number) => void }) {
  const reorder = useReorder({ count: items.length, rowHeight: 55, onMove });
  return (
    <ol>
      {items.map((item, i) => (
        <li key={item} style={reorder.rowStyle(i)} data-dragging={reorder.isDragging(i) || undefined}>
          <button {...reorder.handleProps(i, `Reorder ${item}`)} />
        </li>
      ))}
    </ol>
  );
}

let root: ReturnType<typeof createRoot> | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

function render(items: string[], onMove: (from: number, to: number) => void) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(<List items={items} onMove={onMove} />));
}
const handle = (label: string) => document.querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)!;

describe('useReorder', () => {
  it('moves a row with the arrow keys', () => {
    const onMove = vi.fn();
    render(['a', 'b', 'c'], onMove);
    act(() => handle('Reorder b').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true })));
    act(() => handle('Reorder a').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true })));
    expect(onMove.mock.calls).toEqual([[1, 2]]);
  });

  it('follows the pointer with transforms, then drops where it was let go', () => {
    const onMove = vi.fn();
    render(['a', 'b', 'c', 'd'], onMove);
    const h = handle('Reorder a');
    const pointer = (type: string, clientY: number) =>
      act(() => h.dispatchEvent(new PointerEvent(type, { bubbles: true, button: 0, pointerId: 7, clientY })));
    pointer('pointerdown', 100);
    pointer('pointermove', 100 + 2 * 55 + 10);
    const rows = [...document.querySelectorAll('li')];
    expect(rows[0]!.style.transform).toBe('translateY(120px)');
    expect(rows[1]!.style.transform).toBe('translateY(-55px)');
    expect(rows[2]!.style.transform).toBe('translateY(-55px)');
    expect(rows[3]!.style.transform).toBe('');
    expect(onMove).not.toHaveBeenCalled();
    pointer('pointerup', 100 + 2 * 55 + 10);
    expect(onMove.mock.calls).toEqual([[0, 2]]);
    expect(document.querySelector('li')!.style.transform).toBe('');
  });

  it('drops nothing when the drag is cancelled', () => {
    const onMove = vi.fn();
    render(['a', 'b'], onMove);
    const h = handle('Reorder a');
    act(() => h.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, pointerId: 1, clientY: 0 })));
    act(() => h.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1, clientY: 80 })));
    act(() => h.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerId: 1 })));
    expect(onMove).not.toHaveBeenCalled();
  });
});
