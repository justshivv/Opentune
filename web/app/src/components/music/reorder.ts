import { useCallback, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react';

/**
 * Reordering a list of equal-height rows (Up Next): by a drag handle with the
 * mouse or a finger, or from the keyboard (the handle takes focus; the arrow
 * keys move its row). While dragging, the row follows the pointer and the
 * rows it passes slide out of its way, all with transforms, so nothing is
 * laid out again until the drop.
 */

/** [list] with the item at [from] taken out and put back at [to]. */
export function moved<T>(list: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) return [...list];
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item!);
  return next;
}

/** Where a row picked up at [from] and dragged [dy] px lands, among [count] rows [rowHeight] high. */
export function dropIndex(from: number, dy: number, rowHeight: number, count: number): number {
  return Math.max(0, Math.min(count - 1, from + Math.round(dy / rowHeight)));
}

/** How far row [i] is shifted while row [from] is held over [to]: one row up or down, or not at all. */
export function shiftFor(i: number, from: number, to: number, rowHeight: number): number {
  if (from < to && i > from && i <= to) return -rowHeight;
  if (to < from && i >= to && i < from) return rowHeight;
  return 0;
}

interface Drag {
  from: number;
  startY: number;
  dy: number;
  pointerId: number;
}

export interface Reorder {
  /** Props for row [i]: its transform while a drag is on. */
  rowStyle(i: number): CSSProperties | undefined;
  /** Whether row [i] is the one being dragged. */
  isDragging(i: number): boolean;
  /** Props for row [i]'s handle. */
  handleProps(i: number, label: string): {
    'aria-label': string;
    'data-reorder-handle': number;
    onPointerDown: (e: PointerEvent<HTMLElement>) => void;
    onPointerMove: (e: PointerEvent<HTMLElement>) => void;
    onPointerUp: (e: PointerEvent<HTMLElement>) => void;
    onPointerCancel: () => void;
    onKeyDown: (e: KeyboardEvent<HTMLElement>) => void;
  };
}

export function useReorder({ count, rowHeight, onMove }: { count: number; rowHeight: number; onMove: (from: number, to: number) => void }): Reorder {
  const [drag, setDrag] = useState<Drag | null>(null);
  const live = useRef<Drag | null>(null);
  live.current = drag;

  const finish = useCallback(
    (commit: boolean) => {
      const d = live.current;
      setDrag(null);
      if (!d || !commit) return;
      const to = dropIndex(d.from, d.dy, rowHeight, count);
      if (to !== d.from) onMove(d.from, to);
    },
    [count, rowHeight, onMove],
  );

  const target = drag ? dropIndex(drag.from, drag.dy, rowHeight, count) : -1;

  return {
    rowStyle(i) {
      if (!drag) return undefined;
      if (i === drag.from) {
        const dy = Math.max(-drag.from * rowHeight, Math.min((count - 1 - drag.from) * rowHeight, drag.dy));
        return { transform: `translateY(${dy}px)`, zIndex: 2, position: 'relative', transition: 'none' };
      }
      const shift = shiftFor(i, drag.from, target, rowHeight);
      return { transform: shift ? `translateY(${shift}px)` : undefined, transition: 'transform 160ms cubic-bezier(.2,0,0,1)' };
    },
    isDragging: (i) => drag?.from === i,
    handleProps(i, label) {
      return {
        'aria-label': label,
        'data-reorder-handle': i,
        onPointerDown(e) {
          if (e.button !== 0) return;
          e.preventDefault();
          e.currentTarget.setPointerCapture?.(e.pointerId);
          setDrag({ from: i, startY: e.clientY, dy: 0, pointerId: e.pointerId });
        },
        onPointerMove(e) {
          const d = live.current;
          if (!d || d.pointerId !== e.pointerId) return;
          setDrag({ ...d, dy: e.clientY - d.startY });
        },
        onPointerUp(e) {
          if (live.current?.pointerId === e.pointerId) finish(true);
        },
        onPointerCancel() {
          finish(false);
        },
        onKeyDown(e) {
          const to = e.key === 'ArrowUp' ? i - 1 : e.key === 'ArrowDown' ? i + 1 : null;
          if (e.key === 'Escape' && live.current) return finish(false);
          if (to === null) return;
          e.preventDefault();
          if (to < 0 || to >= count) return;
          onMove(i, to);
          // The handle follows its row, so the next arrow press moves it again.
          const list = e.currentTarget.closest('ol,ul');
          requestAnimationFrame(() => list?.querySelector<HTMLElement>(`[data-reorder-handle="${to}"]`)?.focus());
        },
      };
    },
  };
}
