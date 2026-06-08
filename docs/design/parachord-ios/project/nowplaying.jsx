// nowplaying.jsx — Now Playing sheet (with resolver deck), Queue sheet, Context menu
// Exports to window: NowPlaying, QueueSheet, ContextMenu, ResolverDeck

const { useState: useStateNP, useEffect: useEffectNP } = React;

function ResolverDeck({ active, onPick, available }) {
  const [open, setOpen] = useStateNP(false);
  const allKinds = ['spotify', 'apple', 'bandcamp', 'soundcloud', 'youtube', 'local'];
  const order = [...available, ...allKinds.filter(k => !available.includes(k))];
  const r = PC_DATA.RESOLVERS[active] || PC_DATA.RESOLVERS.spotify;
  return (
    <div className="ios-rpick-wrap">
      <div className="ios-rpick__label">Playing From</div>
      <button className="ios-rpick" onClick={() => setOpen(o => !o)}>
        <span className="ios-rpick__sq" style={{ background: r.full }}>{r.initial}</span>
        <span className="ios-rpick__name">{r.name}</span>
        <span className="ios-rpick__hz">320 kbps</span>
        <PC_ICONS.chevronD width="14" height="14" style={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .2s' }} />
      </button>
      {open && (
        <div className="ios-rpick__menu">
          {order.map(kind => {
            const rr = PC_DATA.RESOLVERS[kind];
            const avail = available.includes(kind);
            return (
              <div key={kind} className={`ios-rpick__opt ${avail ? '' : 'dim'}`}
                onClick={() => { if (avail) { onPick(kind); setOpen(false); } }}>
                <span className="ios-rpick__sq" style={{ background: rr.full }}>{rr.initial}</span>
                <span className="nm">{rr.name}</span>
                {kind === active
                  ? <span className="chk"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M3 8.5l3.5 3.5L13 4"/></svg></span>
                  : <span className="st">{avail ? '' : 'Not connected'}</span>}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function NowPlaying({ open, onClose, track, playing, onToggle, onShowQueue, onMore, queueLen, miniArtRef }) {
  const [resolver, setResolver] = useStateNP(track?.resolver || 'spotify');
  const [progress, setProgress] = useStateNP(0.32);
  const [closing, setClosing] = useStateNP(false);
  const [morph, setMorph] = useStateNP(null);   // { rect, radius, anim }
  const [hideArt, setHideArt] = useStateNP(false);
  const npArtRef = React.useRef(null);
  const wasOpen = React.useRef(false);
  const MORPH_MS = 460;
  const MORPH_EASE = '0.46s cubic-bezier(0.16,1,0.3,1)';

  useEffectNP(() => { if (track?.resolver) setResolver(track.resolver); }, [track?.title]);
  useEffectNP(() => {
    if (!playing || !open) return;
    const t = setInterval(() => setProgress(p => (p >= 1 ? 0 : p + 0.005)), 200);
    return () => clearInterval(t);
  }, [playing, open]);

  // Shared-element OPEN morph: mini artwork → sheet artwork
  useEffectNP(() => {
    if (open && !wasOpen.current) {
      const from = rectOf(miniArtRef && miniArtRef.current);
      const to = rectOf(npArtRef.current);
      if (from && to) {
        setHideArt(true);
        setMorph({ rect: from, radius: 8, anim: false });
        setTimeout(() => {
          setMorph({ rect: to, radius: 14, anim: true });
        }, 24);
        const id = setTimeout(() => { setMorph(null); setHideArt(false); }, MORPH_MS);
        return () => clearTimeout(id);
      }
    }
    wasOpen.current = open;
  }, [open]);

  // Shared-element CLOSE morph: sheet artwork → mini artwork
  const requestClose = () => {
    const from = rectOf(npArtRef.current);
    const to = rectOf(miniArtRef && miniArtRef.current);
    if (from && to) {
      setHideArt(true);
      setClosing(true);
      setMorph({ rect: from, radius: 14, anim: false });
      setTimeout(() => {
        setMorph({ rect: to, radius: 8, anim: true });
      }, 24);
      setTimeout(() => {
        setMorph(null); setHideArt(false); setClosing(false);
        wasOpen.current = false;
        onClose();
      }, MORPH_MS);
    } else { onClose(); }
  };

  const drag = useSheetDrag({ onDismiss: requestClose, threshold: 120, enabled: open && !closing });

  // Swipe up (or tap) on the Up Next peek → open the queue
  const peekStart = React.useRef(null);
  const onPeekDown = (e) => { peekStart.current = e.clientY; };
  const onPeekMove = (e) => {
    if (peekStart.current != null && peekStart.current - e.clientY > 36) {
      peekStart.current = null;
      onShowQueue();
    }
  };
  const onPeekUp = () => { peekStart.current = null; };
  const nextUp = PC_DATA.QUEUE[1] || PC_DATA.QUEUE[0];

  if (!track) return null;
  const cur = `${Math.floor(progress * 170 / 60)}:${String(Math.floor(progress * 170) % 60).padStart(2, '0')}`;
  const tot = '2:50';
  const available = PC_DATA.ARTISTS[track.artist]?.resolvers || ['spotify'];
  const shown = open && !closing;
  const backdropOpacity = shown ? Math.max(0, 1 - drag.dy / 520) : 0;

  return (
    <>
      <MorphArt
        show={!!morph} rect={morph && morph.rect} name={track.title}
        radius={morph ? morph.radius : 14}
        shadow="0 20px 48px rgba(0,0,0,0.6)"
        transition={morph && morph.anim ? `left ${MORPH_EASE}, top ${MORPH_EASE}, width ${MORPH_EASE}, height ${MORPH_EASE}, border-radius ${MORPH_EASE}` : 'none'}
      />
      <div className={`ios-np-backdrop ${shown ? 'open' : ''}`} style={{ opacity: backdropOpacity }} onClick={requestClose} />
      <div
        className={`ios-np ${shown ? 'open' : ''}`}
        style={{ opacity: shown ? 1 : 0, transform: `translateY(${drag.dy}px)`, transition: drag.dragging ? 'opacity .4s ease' : 'transform .4s cubic-bezier(0.16,1,0.3,1), opacity .4s ease' }}
      >
        <div className="ios-np__draghandle" {...drag.handlers}>
          <div className="ios-np__grabber" />
          <div className="ios-np__top">
            <div className="ios-np__top-title">Now Playing</div>
            <button className="ios-np__closebtn" onClick={requestClose} aria-label="Close">
              <PC_ICONS.chevronD width="18" height="18" />
            </button>
          </div>
        </div>
        <div className="ios-np__scroll">
          <div className="ios-np__art" ref={npArtRef} style={{ visibility: hideArt ? 'hidden' : 'visible' }}>
            <ArtPlaceholder name={track.title} size={340} radius={14} />
          </div>
          <div className="ios-np__titleRow">
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="ios-np__title">{track.title}</div>
              <div className="ios-np__artist">{track.artist}</div>
              <div className="ios-np__album">From <strong style={{ color: 'rgba(255,255,255,0.75)', fontWeight: 500 }}>Pleased to Meet Me (Expanded)</strong></div>
            </div>
            <button className="heart" aria-label="Favorite">
              <PC_ICONS.heart width="18" height="18" />
            </button>
          </div>
          <div className="ios-np__progress">
            <div className="ios-np__bar" onClick={(e) => {
              const r = e.currentTarget.getBoundingClientRect();
              setProgress(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)));
            }}>
              <div className="ios-np__bar-fill" style={{ width: `${progress * 100}%` }} />
            </div>
            <div className="ios-np__times">
              <span>{cur}</span>
              <span>-{tot}</span>
            </div>
          </div>
          <div className="ios-np__transport">
            <button className="ios-np__tBtn sec" aria-label="Shuffle"><PC_ICONS.shuffle/></button>
            <button className="ios-np__tBtn" aria-label="Previous"><PC_ICONS.prev/></button>
            <button className="ios-np__play" onClick={onToggle} aria-label="Play/Pause">
              {playing ? <PC_ICONS.pause/> : <PC_ICONS.play/>}
            </button>
            <button className="ios-np__tBtn" aria-label="Next"><PC_ICONS.next/></button>
            <button className="ios-np__tBtn sec" aria-label="Repeat"><PC_ICONS.repeat/></button>
          </div>

          <ResolverDeck active={resolver} onPick={setResolver} available={available} />

          <div className="ios-np__bottom">
            <button className="ios-np__bBtn" aria-label="Cast">
              <PC_ICONS.cast/>
            </button>
            <button className="ios-np__bBtn" aria-label="Spinoff">
              <PC_ICONS.spinoff/>
              <span style={{ fontSize: 11 }}>Spinoff</span>
            </button>
            <button className="ios-np__bBtn" onClick={onShowQueue} aria-label="Queue">
              <PC_ICONS.queue/>
              <span style={{ fontSize: 11 }}>Queue</span>
              {queueLen > 0 && <span className="ios-np__qbadge">{queueLen}</span>}
            </button>
            <button className="ios-np__bBtn" aria-label="More" onClick={onMore}>
              <PC_ICONS.more width="22" height="22"/>
            </button>
          </div>
        </div>
        <div className="ios-np__peek" onClick={onShowQueue}
          onPointerDown={onPeekDown} onPointerMove={onPeekMove} onPointerUp={onPeekUp} onPointerCancel={onPeekUp}>
          <span className="ios-np__peek-chev"><PC_ICONS.chevronD width="18" height="18" style={{ transform: 'rotate(180deg)' }} /></span>
          <span className="ios-np__peek-label">Up Next</span>
          <span className="ios-np__peek-next">{nextUp.title} · {nextUp.artist}</span>
        </div>
      </div>
    </>
  );
}

function QueueSheet({ open, onClose, queue, onPick, currentTitle }) {
  const drag = useSheetDrag({ onDismiss: onClose, threshold: 90, enabled: open });
  return (
    <>
      <div className={`ios-np-backdrop ${open ? 'open' : ''}`} style={{ zIndex: 39, opacity: open ? Math.max(0, 1 - drag.dy / 360) : 0 }} onClick={onClose} />
      <div className={`ios-queue ${open ? 'open' : ''}`}
        style={{ transform: open ? `translateY(${drag.dy}px)` : undefined, transition: drag.dragging ? 'none' : undefined }}
      >
        <div className="ios-queue__draghandle" {...drag.handlers}>
          <div className="ios-queue__grabber" />
          <div className="ios-queue__hdr">
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="ios-queue__title">Up Next · {queue.length} tracks</div>
              <div className="ios-queue__sub" style={{ color: 'var(--accent-soft)' }}>Playing from {currentTitle ? 'Pleased to Meet Me (Expanded)' : 'your queue'}</div>
            </div>
            <button className="ios-queue__clear" onClick={onClose}>Clear</button>
          </div>
        </div>
        <div className="ios-queue__list">
          {queue.map((t, i) => (
            <div key={i} className="ios-queue__row" onClick={() => onPick(t)}>
              <div className="ios-queue__num">{t.n}</div>
              <div style={{ width: 38, height: 38, borderRadius: 6, overflow: 'hidden', flexShrink: 0 }}>
                <ArtPlaceholder name={t.title} size={38} radius={6} />
              </div>
              <div className="ios-queue__meta">
                <div className="ios-queue__t" style={{ color: t.title === currentTitle ? 'var(--accent-soft)' : '#f3f4f6' }}>{t.title}</div>
                <div className="ios-queue__a">{t.artist}</div>
              </div>
              <ResolverChip kind={t.resolver} />
              <div style={{ color: 'rgba(255,255,255,0.4)', font: '400 12px/1 var(--font-mono)', minWidth: 32, textAlign: 'right' }}>{t.dur}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

function ContextMenu({ open, onClose, track, menu }) {
  // Two modes: track long-press (track set) or a custom menu ({title, subtitle, art, items}).
  const cfg = menu || (track ? {
    title: track.title, subtitle: track.artist, artName: track.title,
    items: [
      { label: 'Play Next', icon: PC_ICONS.queue },
      { label: 'Add to Queue', icon: PC_ICONS.add },
      { label: 'Add to Playlist…', icon: PC_ICONS.list },
      { label: 'Spinoff', icon: PC_ICONS.spinoff },
      { label: 'Go to Album', icon: PC_ICONS.inventory },
      { label: 'Go to Artist', icon: PC_ICONS.chevronR },
      { label: 'View Sources', icon: PC_ICONS.globe },
      { label: 'Share', icon: PC_ICONS.send },
      { label: 'Remove from Library', icon: PC_ICONS.close, destructive: true },
    ],
  } : null);
  if (!open || !cfg) return null;
  return (
    <div className="ios-ctx-scrim" onClick={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: 14, width: 264 }} onClick={e => e.stopPropagation()}>
        {(cfg.title || cfg.artName) && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 12,
            background: 'rgba(255,255,255,0.08)', backdropFilter: 'blur(20px)',
            WebkitBackdropFilter: 'blur(20px)',
            padding: 12, borderRadius: 14, color: '#fff',
          }}>
            {cfg.artName && <ArtPlaceholder name={cfg.artName} size={48} radius={cfg.artRound ? 999 : 6} />}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ font: '600 14px/1.2 var(--font-sans)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cfg.title}</div>
              {cfg.subtitle && <div style={{ font: '400 12px/1.3 var(--font-sans)', opacity: 0.75 }}>{cfg.subtitle}</div>}
            </div>
          </div>
        )}
        <div className="ios-ctx">
          {cfg.items.map((it, i) => (
            <div key={i} className={`ios-ctx__item ${it.destructive ? 'destructive' : ''}`} onClick={() => { onClose(); it.onClick && it.onClick(); }}>
              <span>{it.label}</span>
              {it.icon && <it.icon />}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { NowPlaying, QueueSheet, ContextMenu, ResolverDeck });
