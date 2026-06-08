// interactions.jsx — iOS motion & gesture primitives
// Exports: ScreenScaffold, useSheetDrag, useEdgeSwipe, useSwipeRow, MorphArt, rectOf

const { useState: useStateI, useRef: useRefI, useLayoutEffect, useEffect: useEffectI, useCallback } = React;

function rectOf(el) {
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { left: r.left, top: r.top, width: r.width, height: r.height };
}

// ─── ScreenScaffold ────────────────────────────────────────────────────
// Sticky glass nav that materializes on scroll; large title collapses into
// a centered inline title. Apple Music / Mail behavior.
function ScreenScaffold({ title, onMenu, leftIcon = 'menu', onLeft, rightIcon = 'more', onRight, trailing, children, dark, scrollRef }) {
  const [y, setY] = useStateI(0);
  const innerRef = useRefI(null);
  const ref = scrollRef || innerRef;
  const onScroll = (e) => setY(e.target.scrollTop);

  // 0 → fully large title; ramps as you scroll past the title band
  const t = Math.max(0, Math.min(1, (y - 8) / 36));
  const largeOpacity = 1 - Math.min(1, y / 30);
  const largeScale = 1 - 0.06 * Math.min(1, y / 40);
  const Left = PC_ICONS[leftIcon];
  const Right = PC_ICONS[rightIcon];

  return (
    <>
      <div className="ios-fixedbar">
        <div className="ios-fixedbar__bg" style={{ opacity: t }} />
        <div className="ios-fixedbar__hairline" style={{ opacity: t }} />
        <div className="ios-navRow" style={{ position: 'relative' }}>
          <button className="ios-glass icon" onClick={onLeft || onMenu} aria-label="Nav">
            <Left width="18" height="18" />
          </button>
          <div className="ios-inlineTitle" style={{ opacity: t, transform: `translateY(${(1 - t) * 6}px)` }}>{title}</div>
          {trailing ? trailing : (
            <button className="ios-glass icon" onClick={onRight} aria-label="More">
              <Right width="20" height="20" />
            </button>
          )}
        </div>
      </div>
      <div className="pc-scroll ios-scaffold-scroll" ref={ref} onScroll={onScroll}>
        <div className="ios-largeTitle" style={{ opacity: largeOpacity, transform: `scale(${largeScale})`, transformOrigin: 'left center' }}>{title}</div>
        {children}
      </div>
    </>
  );
}

// ─── useSheetDrag ──────────────────────────────────────────────────────
// Drag a bottom sheet downward; rubber-band upward; dismiss past threshold.
function useSheetDrag({ onDismiss, threshold = 110, enabled = true }) {
  const [dy, setDy] = useStateI(0);
  const [dragging, setDragging] = useStateI(false);
  const start = useRefI(null);
  const last = useRefI({ y: 0, t: 0, v: 0 });

  const onPointerDown = (e) => {
    if (!enabled) return;
    start.current = e.clientY;
    last.current = { y: e.clientY, t: Date.now(), v: 0 };
    setDragging(true);
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onPointerMove = (e) => {
    if (start.current == null) return;
    let delta = e.clientY - start.current;
    if (delta < 0) delta = -Math.pow(-delta, 0.72); // rubber-band up
    const now = Date.now();
    const dt = now - last.current.t || 1;
    last.current = { y: e.clientY, t: now, v: (e.clientY - last.current.y) / dt };
    setDy(delta);
  };
  const onPointerUp = () => {
    if (start.current == null) return;
    const flung = last.current.v > 0.55;
    const past = dy > threshold;
    start.current = null;
    setDragging(false);
    if (past || flung) {
      setDy(0);
      onDismiss && onDismiss();
    } else {
      setDy(0);
    }
  };
  const handlers = { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: onPointerUp };
  return { dy, dragging, handlers };
}

// ─── useEdgeSwipe ──────────────────────────────────────────────────────
// Swipe from the left edge to pop a pushed screen.
function useEdgeSwipe({ onPop, width = 402, threshold = 0.32, enabled = true }) {
  const [dx, setDx] = useStateI(0);
  const [dragging, setDragging] = useStateI(false);
  const start = useRefI(null);
  const last = useRefI({ x: 0, t: 0, v: 0 });

  const onPointerDown = (e) => {
    if (!enabled) return;
    const localX = e.nativeEvent.offsetX ?? 999;
    // Only start when grabbing near the left edge
    const rect = e.currentTarget.getBoundingClientRect();
    if (e.clientX - rect.left > 28) return;
    start.current = e.clientX;
    last.current = { x: e.clientX, t: Date.now(), v: 0 };
    setDragging(true);
  };
  const onPointerMove = (e) => {
    if (start.current == null) return;
    const delta = Math.max(0, e.clientX - start.current);
    const now = Date.now();
    last.current = { x: e.clientX, t: now, v: (e.clientX - last.current.x) / (now - last.current.t || 1) };
    setDx(delta);
  };
  const onPointerUp = () => {
    if (start.current == null) return;
    const flung = last.current.v > 0.5;
    const past = dx > width * threshold;
    start.current = null;
    setDragging(false);
    if (past || flung) { setDx(0); onPop && onPop(); }
    else setDx(0);
  };
  const handlers = { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: onPointerUp };
  return { dx, dragging, handlers };
}

// ─── useSwipeRow ───────────────────────────────────────────────────────
// Horizontal swipe-left on a list row to reveal trailing actions.
function useSwipeRow({ actionWidth = 148, enabled = true }) {
  const [x, setX] = useStateI(0);          // current translate (negative = open)
  const [open, setOpen] = useStateI(false);
  const start = useRefI(null);
  const axis = useRefI(null);              // 'x' | 'y' | null
  const moved = useRefI(false);

  const onPointerDown = (e) => {
    if (!enabled) return;
    start.current = { x: e.clientX, y: e.clientY, base: open ? -actionWidth : 0 };
    axis.current = null;
    moved.current = false;
  };
  const onPointerMove = (e) => {
    if (!start.current) return;
    const ddx = e.clientX - start.current.x;
    const ddy = e.clientY - start.current.y;
    if (!axis.current) {
      if (Math.abs(ddx) > 8 || Math.abs(ddy) > 8) {
        axis.current = Math.abs(ddx) > Math.abs(ddy) ? 'x' : 'y';
      }
    }
    if (axis.current === 'x') {
      moved.current = true;
      let nx = start.current.base + ddx;
      nx = Math.max(-actionWidth - 24, Math.min(0, nx));
      if (nx < -actionWidth) nx = -actionWidth - Math.pow(-(nx + actionWidth), 0.7);
      setX(nx);
      e.preventDefault?.();
    }
  };
  const onPointerUp = () => {
    if (!start.current) return;
    const shouldOpen = x < -actionWidth / 2;
    setX(shouldOpen ? -actionWidth : 0);
    setOpen(shouldOpen);
    start.current = null;
  };
  const close = () => { setX(0); setOpen(false); };
  const consumedClick = () => moved.current;
  return {
    x, open, close, consumedClick,
    handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: onPointerUp },
  };
}

// ─── MorphArt ──────────────────────────────────────────────────────────
// A fixed-position artwork tile that animates between two rects (FLIP).
// Driven by `from`/`to` rects + `active`. Renders nothing when idle.
function MorphArt({ show, rect, name, radius, shadow, transition }) {
  if (!show || !rect) return null;
  return (
    <div style={{
      position: 'fixed',
      left: rect.left, top: rect.top, width: rect.width, height: rect.height,
      borderRadius: radius, overflow: 'hidden', zIndex: 70, pointerEvents: 'none',
      boxShadow: shadow, transition,
      willChange: 'left, top, width, height, border-radius',
    }}>
      <ArtPlaceholder name={name} size={Math.max(rect.width, rect.height)} radius={radius} />
    </div>
  );
}

Object.assign(window, { ScreenScaffold, useSheetDrag, useEdgeSwipe, useSwipeRow, MorphArt, rectOf });
