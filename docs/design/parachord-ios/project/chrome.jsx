// chrome.jsx — Top bar, Tab bar (with FAB), Mini player, Dynamic Island, Sidebar drawer
// Exports to window: TopBar, TabBar, MiniPlayer, DynamicIslandLive, Sidebar

const { useState, useEffect, useRef } = React;

function TopBar({ title, onMenu, dark, large = true, sticky = true }) {
  return (
    <div style={{ paddingTop: 54 }}>
      <div className="ios-navRow">
        <button className="ios-glass icon" onClick={onMenu} aria-label="Menu">
          <PC_ICONS.menu width="18" height="18" />
        </button>
        <button className="ios-glass icon" aria-label="More">
          <PC_ICONS.more width="20" height="20" />
        </button>
      </div>
      {large && <div className="ios-largeTitle">{title}</div>}
    </div>
  );
}

function TabBar({ active, onChange, onCenter }) {
  const items = [
    { id: 'home', label: 'Home', Icon: PC_ICONS.home },
    { id: 'search', label: 'Search', Icon: PC_ICONS.search },
    { id: 'collection', label: 'Collection', Icon: PC_ICONS.inventory },
    { id: 'playlists', label: 'Playlists', Icon: PC_ICONS.list },
  ];
  return (
    <nav className="ios-tabbar">
      <div className="ios-tabbar__pill">
        {items.map(({ id, label, Icon }) => (
          <button key={id} className={`ios-tabbar__item ${active === id ? 'active' : ''}`} onClick={() => onChange(id)}>
            <Icon width="22" height="22" />
            <span>{label}</span>
          </button>
        ))}
      </div>
      <button className="ios-tabbar__fab" onClick={onCenter} aria-label="Shuffleupagus">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5v14M5 12h14"/></svg>
      </button>
    </nav>
  );
}

function MiniPlayer({ track, playing, onToggle, onExpand, progress = 0.32, artRef }) {
  if (!track) return null;
  return (
    <div className="ios-mini" onClick={onExpand}>
      <div className="ios-mini__row">
        <div className="ios-mini__art" ref={artRef}>
          <ArtPlaceholder name={track.title} size={44} radius={8} />
        </div>
        <div className="ios-mini__meta">
          <div className="ios-mini__title">{track.title}</div>
          <div className="ios-mini__artist">{track.artist}</div>
        </div>
        <button className="ios-mini__btn heart" onClick={(e) => e.stopPropagation()} aria-label="Favorite">
          <PC_ICONS.heart />
        </button>
        <button className="ios-mini__btn" onClick={(e) => { e.stopPropagation(); onToggle(); }} aria-label="Play/Pause">
          {playing ? <PC_ICONS.pause /> : <PC_ICONS.play />}
        </button>
      </div>
      <div className="ios-mini__bar"><div style={{ width: `${progress * 100}%` }} /></div>
    </div>
  );
}

function DynamicIslandLive({ track, playing }) {
  const [expanded, setExpanded] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setExpanded(false), 4500);
    setExpanded(true);
    return () => clearTimeout(t);
  }, [track?.title]);
  if (!track) return null;
  return (
    <div className={`di-live ${expanded ? 'expanded' : ''}`} onClick={() => setExpanded(e => !e)}>
      <div className="di-live__art">
        <ArtPlaceholder name={track.title} size={24} radius={6} />
      </div>
      {expanded && <div className="di-live__title">{track.title}</div>}
      {playing && (
        <div className="di-live__eq"><span/><span/><span/></div>
      )}
    </div>
  );
}

function Sidebar({ open, onClose, dark, onNav, onOpenFriend }) {
  const items1 = [
    { id: 'playlists', label: 'Playlists', Icon: PC_ICONS.list, color: 'var(--accent-primary)' },
    { id: 'collection', label: 'Collection', Icon: PC_ICONS.inventory, color: 'var(--accent-primary)' },
    { id: 'history', label: 'History', Icon: PC_ICONS.history, color: '#3b82f6' },
  ];
  const items2 = [
    { id: 'fresh', label: 'Fresh Drops', color: '#10b981', Icon: (props = {}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M12 3s6 7 6 11a6 6 0 11-12 0c0-4 6-11 6-11z"/></svg> },
    { id: 'recommendations', label: 'Recommendations', Icon: PC_ICONS.star, color: '#f59e0b' },
    { id: 'pop', label: 'Pop of the Tops', Icon: PC_ICONS.trending, color: '#ea580c' },
    { id: 'critical', label: 'Critical Darlings', Icon: PC_ICONS.trophy, color: '#ef4444' },
    { id: 'concerts', label: 'Concerts', color: '#10c9b4', Icon: (props = {}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4 7a2 2 0 002-2h12a2 2 0 002 2 2 2 0 000 4 2 2 0 00-2 2H6a2 2 0 00-2-2 2 2 0 000-4z"/><path d="M10 5v12"/></svg> },
  ];
  return (
    <>
      <div className={`ios-scrim ${open ? 'open' : ''}`} onClick={onClose} />
      <aside className={`ios-drawer ${open ? 'open' : ''}`}>
        <div className="ios-drawer__brand">
          {dark
            ? <img src="assets/logo_wordmark_white.png" alt="Parachord" />
            : <img className="light" src="assets/logo_wordmark_white.png" alt="Parachord" />}
        </div>
        <div className="ios-drawer__group-h">Your Music</div>
        {items1.map(({ id, label, Icon, color }) => (
          <div key={id} className="ios-drawer__item" onClick={() => { onNav(id); onClose(); }}>
            <Icon style={{ color }} /> <span>{label}</span>
          </div>
        ))}
        <div className="ios-drawer__group-h">Discover</div>
        {items2.map(({ id, label, Icon, color }) => (
          <div key={id} className="ios-drawer__item" onClick={() => { onNav(id); onClose(); }}>
            <Icon style={{ color }} /> <span>{label}</span>
          </div>
        ))}
        <div className="ios-drawer__group-h" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingRight: 24 }}>
          <span>Friends</span>
          <PC_ICONS.add width="18" height="18" style={{ color: 'var(--fg2)' }} />
        </div>
        {PC_DATA.FRIENDS.map((f, i) => (
          <div key={i} className="ios-drawer__friend" style={{ cursor: 'pointer' }} onClick={() => { onOpenFriend && onOpenFriend(f); onClose(); }}>
            <div className="ios-drawer__avatar" style={{ background: f.color }}>
              {f.initial}
              {f.dot && <span className="ios-drawer__dot" />}
            </div>
            <div className="ios-drawer__friendMeta">
              <div className="ios-drawer__friendName">{f.name}</div>
              {f.now && (
                <div className="ios-drawer__friendNow">
                  <span className="pc-dot" />
                  <span>{f.now}</span>
                </div>
              )}
            </div>
          </div>
        ))}
        <div className="ios-drawer__settings" onClick={() => { onNav('settings'); onClose(); }}>
          <PC_ICONS.settings />
          <span style={{ font: '500 13px/1 var(--font-sans)', letterSpacing: '0.14em', textTransform: 'uppercase', color: 'var(--fg2)' }}>Settings</span>
        </div>
      </aside>
    </>
  );
}

Object.assign(window, { TopBar, TabBar, MiniPlayer, DynamicIslandLive, Sidebar, AddActionSheet });

function AddActionSheet({ open, onClose, onShuffleupagus, onAddFriend }) {
  const actions = [
    { id: 'dj',     label: 'Ask Shuffleupagus', sub: 'Let the AI DJ build a queue', icon: PC_ICONS.mammoth, brand: true, onClick: onShuffleupagus },
    { id: 'pl',     label: 'New Playlist',        sub: 'Start an empty playlist',     icon: PC_ICONS.list },
    { id: 'hosted', label: 'Import Playlist',     sub: 'Mirror an XSPF / remote feed', icon: PC_ICONS.globe },
    { id: 'friend', label: 'Add Friend',          sub: 'Follow a ListenBrainz / Last.fm user', icon: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="8" r="4"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M18 8v6M21 11h-6"/></svg>, onClick: onAddFriend },
  ];
  return (
    <>
      <div className={`ios-sheet-scrim ${open ? 'open' : ''}`} onClick={onClose} />
      <div className={`ios-sheet ${open ? 'open' : ''}`}>
        <div className="ios-sheet__group">
          <div className="ios-sheet__title">Add to Parachord</div>
          {actions.map(a => (
            <div key={a.id} className="ios-sheet__item" onClick={() => { onClose(); a.onClick && a.onClick(); }}>
              <span className="ios-sheet__icon"><a.icon /></span>
              <div className="ios-sheet__meta">
                {a.label}
                <small>{a.sub}</small>
              </div>
            </div>
          ))}
        </div>
        <button className="ios-sheet__cancel" onClick={onClose}>Cancel</button>
      </div>
    </>
  );
}
