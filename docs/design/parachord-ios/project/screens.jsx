// screens.jsx — Home, Search, Collection, Playlists, Shuffleupagus, Artist

const { useState: useStateS } = React;

// ─── Track row (swipeable, long-press, tap-to-play) ─────────────────────
function TrackRow({ track, onPlay, onLong, isPlaying, showNum = true, onQueue, onRemove }) {
  const longRef = React.useRef(null);
  const [dragging, setDragging] = React.useState(false);
  const swipe = useSwipeRow({ actionWidth: 148 });

  const fireLong = () => { longRef.current = setTimeout(() => onLong && onLong(track), 400); };
  const clearLong = () => clearTimeout(longRef.current);

  const onPointerDown = (e) => { setDragging(true); fireLong(); swipe.handlers.onPointerDown(e); };
  const onPointerMove = (e) => {
    swipe.handlers.onPointerMove(e);
    clearLong();
  };
  const onPointerUp = (e) => { setDragging(false); clearLong(); swipe.handlers.onPointerUp(e); };
  const onPointerCancel = (e) => { setDragging(false); clearLong(); swipe.handlers.onPointerUp(e); };

  const handleClick = () => {
    if (swipe.consumedClick()) return;
    if (swipe.open) { swipe.close(); return; }
    onPlay && onPlay();
  };

  return (
    <div className="pc-row-wrap">
      <div className="pc-row-actions">
        <button className="act-queue" onClick={() => { swipe.close(); onQueue && onQueue(track); }}>
          <PC_ICONS.queue /><span>Queue</span>
        </button>
        <button className="act-remove" onClick={() => { swipe.close(); onRemove && onRemove(track); }}>
          <PC_ICONS.close /><span>Remove</span>
        </button>
      </div>
      <div
        className={`pc-row pc-row-swipeable ${isPlaying ? 'is-playing' : ''}`}
        style={{ transform: `translateX(${swipe.x}px)`, transition: dragging ? 'none' : 'transform 0.34s cubic-bezier(0.16,1,0.3,1)' }}
        onClick={handleClick}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerCancel}
        onPointerLeave={clearLong}
      >
        {showNum && <div className="pc-row__num">{track.n}</div>}
        <div className="pc-row__meta">
          <div className="pc-row__title">{track.title}</div>
          {track.artist && <div className="pc-row__artist">{track.artist}</div>}
        </div>
        {track.resolvers && (
          <div className="pc-row__rs">
            {track.resolvers.map(r => <ResolverChip key={r} kind={r} />)}
          </div>
        )}
        {track.resolver && <ResolverChip kind={track.resolver} />}
        <div className="pc-row__dur">{track.dur}</div>
      </div>
    </div>
  );
}

// ─── HOME ────────────────────────────────────────────────────────────
const COMPASS_ICON = (props = {}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><polygon points="16 8 13 13 8 16 11 11" fill="currentColor" stroke="none"/></svg>;

function HomeScreen({ onMenu, onPlay, onLong, dark, onArtist, onQueue, onOpenList, onOpenPlaylist, onOpenAlbum, onOpenFriend, onOpenWeekly, onTab, onTabMenu }) {
  const tiles = [
    { title: 'For You',           icon: PC_ICONS.star,     featured: 'The Fratellis',     reason: 'Based on your listeni…', color: 'linear-gradient(135deg,#7c3aed,#6d28d9)', preset: 'foryou' },
    { title: 'Critical Darlings', icon: PC_ICONS.heart,    featured: 'I Built You A Tow…', reason: 'Death Cab for Cutie',    color: 'linear-gradient(135deg,#ea580c,#f59e0b)', preset: 'critical' },
    { title: 'Pop of the Tops',   icon: PC_ICONS.trending, featured: 'ICEMAN',            reason: 'Drake',                  color: 'linear-gradient(135deg,#ec4899,#f59e0b)', preset: 'pop' },
    { title: 'Fresh Drops',       icon: COMPASS_ICON,      featured: 'You Say I Roman…',  reason: 'Sweeping Promises',      color: 'linear-gradient(135deg,#10b981,#0d9488)', preset: 'fresh' },
  ];
  const weeklyJams = [{ label: 'This Week', seed: 'wj1' }, { label: 'Last Week', seed: 'wj2' }];
  const weeklyExpl = [{ label: 'This Week', seed: 'we1' }, { label: 'Last Week', seed: 'we2' }];
  const friendAct = [
    { initial: 'D', name: 'drfeelgood',   now: 'Shoulda Never (feat. USHER) · Kehlani · 11m',         color: 'linear-gradient(135deg,#6d5bf0,#4c1d95)' },
    { initial: 'P', name: 'phredspin',    now: 'Bada Bing · DANGERDOOM, MF DOOM, Danger…',            color: 'linear-gradient(135deg,#7c3aed,#5b21b6)' },
    { initial: 'F', name: 'frankmorello', now: 'A New Way of Living · The Early Years · 18h',          color: 'linear-gradient(135deg,#9333ea,#6d28d9)' },
    { initial: 'V', name: 'vonsnack',     now: 'Crass Shadows (at Walden Pawn) · Ryan Davis…',         color: 'linear-gradient(135deg,#a855f7,#7c3aed)' },
  ];
  const albumSugg = [
    { title: 'Pleased to Meet …', artist: 'The Replacements', year: 1987, type: 'ALBUM' },
    { title: 'Keep It Like a Se…', artist: 'Built to Spill',  year: 1999, type: 'ALBUM' },
    { title: 'Electric Version',  artist: 'The New Pornog…',  year: 2003, type: 'ALBUM' },
  ];
  const artistSugg = ['The Hold Steady', 'Guided By Voices', 'Sharon Van Etten', 'Jason Isbell'];
  const stats = [
    { n: '2,645', l: 'Songs',    icon: <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M9 18V5l10-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="16" cy="16" r="3"/></svg>, tab: 'collection' },
    { n: '99',    l: 'Albums',   icon: <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/></svg>, tab: 'collection' },
    { n: '86',    l: 'Artists',  icon: <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="8" r="4"/><path d="M5 20c0-3.5 3-6 7-6s7 2.5 7 6"/></svg>, tab: 'collection' },
    { n: '150',   l: 'Playlists', icon: <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 7h11M4 12h11M4 17h7M18 9v8M18 17a2 2 0 104 0 2 2 0 00-4 0z"/></svg>, tab: 'playlists' },
  ];
  const recentLoves = [
    { title: 'Chance to Bleed',  artist: 'Kurt Vile',        dur: '4:56', resolvers: ['apple', 'spotify'] },
    { title: 'Starting Line',    artist: 'All Them Witches', dur: '3:51', resolvers: ['apple', 'spotify'] },
    { title: 'East Of Ordinary', artist: 'King Tuff',        dur: '4:08', resolvers: ['apple', 'spotify'] },
  ];

  return (
    <ScreenScaffold title="Home" onMenu={onMenu} dark={dark} onRight={() => onTabMenu && onTabMenu('home')}>
      <div className="pc-section-h">Continue Listening</div>
      <div style={{ padding: '4px 20px 16px' }}>
        <div onClick={() => onPlay({ title: 'Bully for You', artist: 'Hotel Mira', resolver: 'spotify' })} style={{
          display: 'flex', alignItems: 'center', gap: 14,
          background: 'var(--accent-a06)', borderRadius: 14, padding: 12, cursor: 'pointer',
        }}>
          <ArtPlaceholder name="Bully for You" size={56} radius={8} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ font: '600 15px/1.2 var(--font-sans)', color: 'var(--fg1)', letterSpacing: '-0.1px' }}>Bully for You</div>
            <div style={{ font: '400 13px/1.3 var(--font-sans)', color: 'var(--fg2)' }}>Hotel Mira</div>
            <div style={{ font: '500 11px/1.3 var(--font-sans)', color: 'var(--accent-primary)', marginTop: 4 }}>29 more in queue</div>
          </div>
          <button className="ios-mini__btn" style={{ color: 'var(--accent-primary)' }} aria-label="Play">
            <PC_ICONS.play width="26" height="26" />
          </button>
        </div>
      </div>

      <div className="pc-section-h">Recently Added</div>
      <div className="pc-hscroll">
        {PC_DATA.RECENTLY_ADDED.slice(0, 6).map((a, i) => (
          <div key={i} className="pc-hcard" onClick={() => onOpenAlbum({ title: a.title, artist: a.artist, year: a.year, type: a.tracks === 1 ? 'SINGLE' : 'ALBUM' })}>
            <div className="pc-hcard__art"><ArtPlaceholder name={a.title + a.artist} size={148} radius={12} /></div>
            <div className="pc-hcard__title">{a.title}</div>
            <div className="pc-hcard__sub">{a.artist}</div>
          </div>
        ))}
      </div>

      <div className="pc-section-h">Your Playlists</div>
      <div className="pc-hscroll">
        {PC_DATA.PLAYLISTS.map((p, i) => (
          <div key={i} className="pc-hcard" onClick={() => onOpenPlaylist && onOpenPlaylist(p)}>
            <div className="pc-mosaic">
              {p.artists.slice(0, 4).map((a, j) => <ArtPlaceholder key={j} name={a + j} size={74} radius={0} fill />)}
            </div>
            <div className="pc-hcard__title">{p.title}</div>
            <div className="pc-hcard__sub">{p.tracks} tracks</div>
          </div>
        ))}
      </div>

      <div className="pc-section-h">Discover</div>
      <div className="pc-grid-2">
        {tiles.map((t, i) => (
          <div key={i} className="pc-tile" style={{ background: t.color }} onClick={() => onOpenList && onOpenList(t.preset)}>
            <h4><t.icon width="16" height="16" /> {t.title}</h4>
            <div className="pc-tile__art">
              <div className="pc-tile__thumb"><ArtPlaceholder name={t.featured + t.reason} size={44} radius={6} /></div>
              <div className="pc-tile__txt">
                <strong>{t.featured}</strong>
                <span>{t.reason}</span>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="pc-sech-row">
        <div className="pc-section-h">Weekly Jams</div>
        <span className="pc-srcbadge lb">ListenBrainz</span>
      </div>
      <div className="pc-weekly">
        {weeklyJams.map((w, i) => (
          <div key={i} className="pc-weekly-card" onClick={() => onOpenWeekly && onOpenWeekly({ title: `Weekly Jams · ${w.label}`, artists: [w.seed + 'a', w.seed + 'b', w.seed + 'c', w.seed + 'd'] })}>
            <div className="pc-weekly-art">
              {[0, 1, 2, 3].map(j => <ArtPlaceholder key={j} name={w.seed + j} size={90} radius={0} fill />)}
            </div>
            <div className="pc-weekly-title">{w.label}</div>
            <div className="pc-weekly-sub">50 tracks</div>
          </div>
        ))}
      </div>

      <div className="pc-sech-row">
        <div className="pc-section-h">Weekly Exploration</div>
        <span className="pc-srcbadge lb">ListenBrainz</span>
      </div>
      <div className="pc-weekly">
        {weeklyExpl.map((w, i) => (
          <div key={i} className="pc-weekly-card" onClick={() => onOpenWeekly && onOpenWeekly({ title: `Weekly Exploration · ${w.label}`, artists: [w.seed + 'a', w.seed + 'b', w.seed + 'c', w.seed + 'd'] })}>
            <div className="pc-weekly-art">
              {[0, 1, 2, 3].map(j => <ArtPlaceholder key={j} name={w.seed + j} size={90} radius={0} fill />)}
            </div>
            <div className="pc-weekly-title">{w.label}</div>
            <div className="pc-weekly-sub">50 tracks</div>
          </div>
        ))}
      </div>

      <div className="pc-section-h">Friend Activity</div>
      <div>
        {friendAct.map((f, i) => (
          <div key={i} className="pc-friendact" onClick={() => onOpenFriend && onOpenFriend({ name: f.name, initial: f.initial, color: f.color, dot: true, now: f.now })}>
            <div className="pc-hex" style={{ background: f.color }}>{f.initial}</div>
            <div className="pc-friendact__meta">
              <div className="pc-friendact__name">{f.name} <span className="pc-lbbadge">LB</span></div>
              <div className="pc-friendact__now">{f.now}</div>
            </div>
          </div>
        ))}
      </div>

      <div className="pc-sech-row">
        <div className="pc-section-h">Album Suggestions</div>
        <span className="pc-srcbadge shf">Shuffleupagus</span>
        <span className="pc-sech-refresh"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 11-3-6.7M21 4v4h-4"/></svg></span>
      </div>
      <div className="pc-hscroll">
        {albumSugg.map((a, i) => (
          <div key={i} className="pc-hcard" onClick={() => onOpenAlbum({ title: a.title, artist: a.artist, year: a.year, type: a.type })}>
            <div className="pc-hcard__art"><ArtPlaceholder name={a.title + a.artist} size={148} radius={12} /></div>
            <div className="pc-hcard__title">{a.title}</div>
            <div className="pc-hcard__sub">{a.artist}</div>
          </div>
        ))}
      </div>

      <div className="pc-sech-row">
        <div className="pc-section-h">Artist Suggestions</div>
        <span className="pc-srcbadge shf">Shuffleupagus</span>
      </div>
      <div className="pc-hscroll">
        {artistSugg.map((nm, i) => (
          <div key={i} className="pc-hcard" style={{ width: 110 }} onClick={() => onArtist(nm)}>
            <div style={{ width: 110, height: 110, borderRadius: 999, overflow: 'hidden' }}>
              <ArtPlaceholder name={nm + ' artist'} size={110} radius={999} />
            </div>
            <div className="pc-hcard__title" style={{ textAlign: 'center', marginTop: 8 }}>{nm}</div>
          </div>
        ))}
      </div>

      <div className="pc-section-h">Your Collection</div>
      <div className="pc-stats">
        {stats.map((s, i) => (
          <div key={i} className="pc-stat" onClick={() => onTab && onTab(s.tab)}>
            <div className="pc-stat__icon">{s.icon}</div>
            <div className="pc-stat__n">{s.n}</div>
            <div className="pc-stat__l">{s.l}</div>
          </div>
        ))}
      </div>

      <div className="pc-section-h">Recent Loves</div>
      <div style={{ paddingBottom: 20 }}>
        {recentLoves.map((t, i) => (
          <TrackRow key={i} track={t} showNum={false} onPlay={() => onPlay({ ...t, resolver: t.resolvers[0] })} onLong={onLong} onQueue={onQueue} />
        ))}
      </div>
    </ScreenScaffold>
  );
}

// ─── SEARCH ──────────────────────────────────────────────────────────
function SearchScreen({ onMenu, onPlay, onLong, dark, onArtist, onOpenAlbum, onTabMenu }) {
  const [q, setQ] = useStateS('');
  const recents = [
    { q: 'Alabama shakes', type: 'artist', value: 'Alabama Shakes', seed: 'Alabama Shakes' },
    { q: 'biohio',         type: 'artist', value: 'Biohio',         seed: null },
    { q: 'friko',          type: 'artist', value: 'Friko',          seed: 'Friko band' },
    { q: 'twin',           type: 'track',  value: 'Twin Fawn',      seed: 'Twin Fawn' },
    { q: 'twin fawn',      type: 'album',  value: 'Twin Fawn',      seed: 'Twin Fawn album' },
    { q: 'kin',            type: 'track',  value: 'East Of Ordinary', seed: 'King Tuff' },
  ];
  const showResults = q.length > 1;
  const filtered = PC_DATA.RECENTLY_ADDED.filter(a =>
    a.title.toLowerCase().includes(q.toLowerCase()) || a.artist.toLowerCase().includes(q.toLowerCase())
  );
  return (
    <ScreenScaffold title="Search" onMenu={onMenu} dark={dark} onRight={() => onTabMenu && onTabMenu('search')}>
      <div className="ios-searchField">
        <PC_ICONS.search />
        <input value={q} onChange={e => setQ(e.target.value)} placeholder="Search tracks, albums, artists…" />
        {q && <span style={{ cursor: 'pointer' }} onClick={() => setQ('')}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor"><circle cx="8" cy="8" r="8" opacity="0.3"/><path d="M5 5l6 6M11 5l-6 6" stroke="#fff" strokeWidth="1.5" strokeLinecap="round"/></svg>
        </span>}
      </div>
      {!showResults ? (
        <>
          <div className="ios-recents-hdr">
            <span>Recent Searches</span>
            <button className="ios-recents-clear">Clear All</button>
          </div>
          {recents.map((r, i) => (
            <div key={i} className="ios-recent-row" onClick={() => r.type === 'artist' ? onArtist(r.value) : setQ(r.q)}>
              <ArtPlaceholder name={r.seed || r.q} size={48} radius={4} />
              <div className="ios-recent-meta">
                <div className="ios-recent-q">"{r.q}"</div>
                <div className="ios-recent-sub">{r.type}: {r.value}</div>
              </div>
              <button className="ios-recent-x" onClick={(e) => e.stopPropagation()} aria-label="Remove">
                <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M3 3l10 10M13 3L3 13"/></svg>
              </button>
            </div>
          ))}
        </>
      ) : (
        <>
          <div className="pc-section-h">Top Result</div>
          <div onClick={() => onArtist('Quarantine Angst')} style={{
            display: 'flex', alignItems: 'center', gap: 14, padding: '4px 20px 16px', cursor: 'pointer',
          }}>
            <div style={{ width: 88, height: 88, borderRadius: 999, overflow: 'hidden' }}>
              <ArtPlaceholder name="Quarantine Angst Artist" size={88} radius={999} />
            </div>
            <div>
              <div style={{ font: '600 18px/1.2 var(--font-sans)', color: 'var(--fg1)', letterSpacing: '-0.2px' }}>Quarantine Angst</div>
              <div style={{ font: '400 13px/1.3 var(--font-sans)', color: 'var(--fg2)', marginTop: 4 }}>Artist · Indie · Post-punk</div>
              <div style={{ display: 'inline-flex', gap: 4, marginTop: 8 }}>
                <ResolverChip kind="spotify" /><ResolverChip kind="bandcamp" />
              </div>
            </div>
          </div>
          <div className="pc-section-h">Albums</div>
          <div style={{ paddingBottom: 8 }}>
            {filtered.slice(0, 5).map((a, i) => (
              <div key={i} className="pc-row" onClick={() => onOpenAlbum({ title: a.title, artist: a.artist, year: a.year, type: a.tracks === 1 ? 'SINGLE' : 'ALBUM' })}>
                <ArtPlaceholder name={a.title + a.artist} size={48} radius={6} />
                <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                  <div className="pc-row__title">{a.title}</div>
                  <div className="pc-row__artist">{a.year} · {a.artist}</div>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </ScreenScaffold>
  );
}

// ─── COLLECTION ──────────────────────────────────────────────────────
function CollectionScreen({ onMenu, onPlay, onLong, dark, onArtist, onOpenAlbum, onQueue, onOpenFriend }) {
  const [filter, setFilter] = useStateS('Artists');
  const [sort, setSort] = useStateS('A–Z');
  const filters = [
    { id: 'Artists', label: 'Artists', count: '86' },
    { id: 'Albums',  label: 'Albums',  count: '99' },
    { id: 'Songs',   label: 'Songs',   count: '2645' },
    { id: 'Friends', label: 'Friends', count: null },
  ];
  const SORTS = ['A–Z', 'Z–A', 'Recently Added', 'Most Played'];
  const artistNames = Object.keys(PC_DATA.ARTISTS);
  const songs = PC_DATA.QUEUE;
  return (
    <ScreenScaffold title="Collection" onMenu={onMenu} dark={dark}
      trailing={<button className="ios-glass icon" style={{ width: 34, height: 34 }} aria-label="Sync"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 11-3-6.7M21 4v4h-4"/></svg></button>}>
      <div className="ios-counttabs">
        {filters.map(f => (
          <button key={f.id} className={`ios-counttab ${filter === f.id ? 'active' : ''}`} onClick={() => setFilter(f.id)}>
            {f.label}{f.count ? ` (${f.count})` : ''}
          </button>
        ))}
      </div>
      <div className="ios-sortbar">
        <button className="ios-sortbar__sort" onClick={() => setSort(SORTS[(SORTS.indexOf(sort) + 1) % SORTS.length])}>
          {sort} <PC_ICONS.chevronD width="14" height="14" />
        </button>
        <button className="ios-sortbar__search" aria-label="Search collection"><PC_ICONS.search width="17" height="17" /></button>
      </div>

      {filter === 'Albums' && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr) minmax(0,1fr)', gap: 14, padding: '12px 20px 16px', alignItems: 'start' }}>
            {PC_DATA.COLLECTION_ALBUMS.map((a, i) => (
              <div key={i} onClick={() => onOpenAlbum({ title: a.title, artist: a.artist, year: a.year, type: a.tracks === 1 ? 'SINGLE' : 'ALBUM' })} style={{ cursor: 'pointer' }}>
                <div style={{ width: '100%', aspectRatio: '1/1', borderRadius: 8, overflow: 'hidden', boxShadow: '0 2px 6px rgba(0,0,0,0.1)' }}>
                  <ArtPlaceholder name={a.title + a.artist} size={108} radius={8} fill />
                </div>
                <div style={{ font: '600 13px/1.3 var(--font-sans)', color: 'var(--fg1)', marginTop: 8, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', letterSpacing: '-0.1px' }}>{a.title}</div>
                <div style={{ font: '400 12px/1.3 var(--font-sans)', color: 'var(--fg2)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.artist}</div>
                <div style={{ font: '400 11px/1 var(--font-sans)', color: 'var(--fg3)', marginTop: 3 }}>{a.year} · {a.tracks} {a.tracks === 1 ? 'track' : 'tracks'}</div>
              </div>
            ))}
          </div>
        </>
      )}

      {filter === 'Artists' && (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr) minmax(0,1fr)', gap: 16, padding: '10px 20px 16px', alignItems: 'start' }}>
          {artistNames.map((nm, i) => (
            <div key={i} style={{ cursor: 'pointer', textAlign: 'center' }} onClick={() => onArtist(nm)}>
              <div style={{ width: '100%', aspectRatio: '1/1', borderRadius: 999, overflow: 'hidden', boxShadow: '0 4px 12px rgba(0,0,0,0.12)' }}>
                <ArtPlaceholder name={nm + ' artist'} size={104} radius={999} fill />
              </div>
              <div style={{ font: '600 13px/1.25 var(--font-sans)', color: 'var(--fg1)', marginTop: 8, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', letterSpacing: '-0.1px' }}>{nm}</div>
              <div style={{ font: '400 11px/1.3 var(--font-sans)', color: 'var(--fg2)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{PC_DATA.ARTISTS[nm].genre}</div>
            </div>
          ))}
        </div>
      )}

      {filter === 'Songs' && (
        <div style={{ paddingTop: 6 }}>
          {songs.concat(songs.slice(0, 4)).map((t, i) => (
            <TrackRow key={i} track={{ ...t, n: i + 1 }} onPlay={() => onPlay({ ...t })} onLong={onLong} onQueue={onQueue} />
          ))}
        </div>
      )}

      {filter === 'Friends' && (
        <div style={{ paddingTop: 6 }}>
          {PC_DATA.FRIENDS.map((f, i) => (
            <div key={i} className="pc-row" style={{ padding: '8px 20px' }} onClick={() => onOpenFriend && onOpenFriend(f)}>
              <div className="ios-drawer__avatar" style={{ background: f.color, width: 46, height: 46 }}>
                {f.initial}
                {f.dot && <span className="ios-drawer__dot" />}
              </div>
              <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                <div className="pc-row__title">{f.name}</div>
                <div className="pc-row__artist">{f.now ? <span style={{ color: 'var(--success)' }}>♫ {f.now}</span> : 'Offline'}</div>
              </div>
              <PC_ICONS.chevronR width="16" height="16" style={{ color: 'var(--fg3)' }} />
            </div>
          ))}
        </div>
      )}
    </ScreenScaffold>
  );
}

// ─── PLAYLISTS ───────────────────────────────────────────────────────
function PlaylistsScreen({ onMenu, onPlay, onLong, dark, onOpenPlaylist, onTabMenu }) {
  const [sort, setSort] = useStateS('Recently Modified');
  const SORTS = ['Recently Modified', 'Name (A–Z)', 'Track Count', 'Date Added'];
  return (
    <ScreenScaffold title={`Playlists (${PC_DATA.PLAYLISTS.length})`} onMenu={onMenu} dark={dark} onRight={() => onTabMenu && onTabMenu('playlists')}>
      <div style={{ padding: '0 20px 8px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ flex: 1, display: 'inline-flex', alignItems: 'center', gap: 4, font: '500 15px/1 var(--font-sans)', color: 'var(--fg1)', cursor: 'pointer' }}
          onClick={() => setSort(SORTS[(SORTS.indexOf(sort) + 1) % SORTS.length])}>
          {sort} <PC_ICONS.chevronD width="15" height="15" style={{ color: 'var(--fg2)' }} />
        </div>
        <button className="ios-glass icon" style={{ width: 34, height: 34 }} aria-label="Search"><PC_ICONS.search width="17" height="17" /></button>
      </div>
      {PC_DATA.PLAYLISTS.map((p, i) => (
        <div key={i} className="pc-row" style={{ padding: '8px 20px' }} onClick={() => onOpenPlaylist(p)}>
          <div className="pc-mosaic" style={{ width: 48, height: 48, borderRadius: 8 }}>
            {p.artists.slice(0, 4).map((a, j) => <ArtPlaceholder key={j} name={a + j} size={24} radius={0} fill />)}
          </div>
          <div className="pc-row__meta" style={{ marginLeft: 4, gap: 3 }}>
            <div className="pc-row__title">{p.title}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
              <span className="pc-row__artist" style={{ flex: '0 1 auto' }}>
                {p.creator ? `${p.creator} · ${p.tracks.toLocaleString()} tracks` : `${p.tracks.toLocaleString()} tracks`}
              </span>
              {p.hosted && <HostedChip />}
              {p.source && <SourceLabel kind={p.source} />}
            </div>
          </div>
        </div>
      ))}
    </ScreenScaffold>
  );
}

// ─── PLAYLIST DETAIL ─────────────────────────────────────────────────
function PlaylistDetailScreen({ playlist, onClose, onPlay, onLong, onQueue, dark, onEditPlaylist, onPlaylistMenu }) {
  const p = playlist;
  const tracks = PC_DATA.QUEUE.concat(PC_DATA.QUEUE.slice(0, 3));
  const owner = p.creator || 'You';
  const srcName = p.source ? PC_DATA.RESOLVERS[p.source].name : null;
  const rowResolvers = p.source === 'apple' ? ['apple', 'spotify'] : ['apple', 'spotify'];
  return (
    <>
      <div className="ios-pushbar">
        <button className="ios-pushbar__back" onClick={onClose} aria-label="Back"><PC_ICONS.chevronL width="22" height="22" /></button>
        <div className="ios-pushbar__title">{p.title}</div>
        <button className="ios-pushbar__more" onClick={() => onPlaylistMenu && onPlaylistMenu(p)} aria-label="More"><PC_ICONS.more width="20" height="20" /></button>
      </div>

      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <div className="ios-cdetail">
          <div className="ios-cdetail__cover">
            {p.artists.slice(0, 4).map((a, j) => <ArtPlaceholder key={j} name={a + j} size={75} radius={0} fill />)}
          </div>
          <div className="ios-cdetail__title">{p.title}</div>
          <div className="ios-cdetail__by">
            <span>by {owner}{srcName ? ` · ${srcName}` : ''} · {p.tracks.toLocaleString()} tracks</span>
            {p.hosted && <HostedChip />}
          </div>
          <div className="ios-cdetail__upd">Last updated 8 minutes ago</div>
        </div>
        <div className="ios-detail-actions center">
          <button className="ios-playall" onClick={() => onPlay({ title: tracks[0].title, artist: tracks[0].artist, resolver: tracks[0].resolver })}>
            <PC_ICONS.play width="18" height="18" /> Play All
          </button>
          {(p.hosted || p.source) && (
            <button className="ios-save-pill" aria-label="Pull">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 11-3-6.7M21 4v4h-4"/></svg> Pull
            </button>
          )}
          <button className="ios-detail-more" aria-label="More"><PC_ICONS.more width="22" height="22" /></button>
        </div>
        <div>
          {tracks.map((t, i) => (
            <div key={i} className="ios-trk" onClick={() => onPlay({ title: t.title, artist: t.artist, resolver: t.resolver })}
              onContextMenu={(e) => { e.preventDefault(); onLong && onLong(t); }}>
              <div className="ios-trk__n">{i + 1}</div>
              <ArtPlaceholder name={t.title + t.artist} size={40} radius={5} />
              <div className="ios-trk__meta">
                <div className="ios-trk__title">{t.title}</div>
                <div className="ios-trk__artist">{t.artist}</div>
              </div>
              <div className="ios-trk__dur">{t.dur}</div>
              <div className="pc-rsqs">{rowResolvers.map(r => <ResolverSquare key={r} kind={r} />)}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── SHUFFLEUPAGUS ──────────────────────────────────────────────────
function ShuffleupagusScreen({ onMenu, dark }) {
  const [chat, setChat] = useStateS([
    { who: 'bot', text: "I'm Shuffleupagus, your AI DJ. Tell me a vibe, an artist, a memory — anything." },
    { who: 'user', text: "Late-night drive, kinda melancholy. Heavy on guitars." },
    { who: 'bot',  text: "Got it. I've added 10 tracks to your queue:" },
    { who: 'bot',  text: "Western Sky · Dangermuffin\nFox · Dogleg\nNo Name (Track 7) · Jack White\n…and 7 more" },
  ]);
  const [draft, setDraft] = useStateS('');
  const send = () => {
    if (!draft.trim()) return;
    setChat(c => [...c, { who: 'user', text: draft }, { who: 'bot', text: "On it. Pulling tracks from your library, Spotify, and Bandcamp…" }]);
    setDraft('');
  };
  return (
    <>
      <div style={{ paddingTop: 54 }}>
        <div className="ios-navRow">
          <button className="ios-glass icon" onClick={onMenu} aria-label="Close">
            <PC_ICONS.close width="18" height="18" />
          </button>
          <div className="ios-navTitle" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <PC_ICONS.mammoth width="22" height="22" style={{ color: 'var(--accent-primary)' }} />
            Shuffleupagus
          </div>
          <button className="ios-glass icon" aria-label="More">
            <PC_ICONS.more width="20" height="20" />
          </button>
        </div>
      </div>
      <div className="pc-shuffle">
        <div className="pc-shuffle__hero">
          <PC_ICONS.mammoth width="64" height="64" style={{ color: 'var(--accent-primary)' }} />
          <h2>Ask your DJ…</h2>
          <p>Tell me a vibe, era, or feeling. I'll pull tracks across all your sources.</p>
        </div>
        <div className="pc-shuffle__chat">
          {chat.map((m, i) => (
            <div key={i} className={m.who === 'user' ? 'pc-msg-user' : 'pc-msg-bot'} style={{ whiteSpace: 'pre-line' }}>
              {m.text}
            </div>
          ))}
        </div>
        <div className="pc-shuffle__input">
          <input
            className="pc-shuffle__field"
            placeholder="Tell me a vibe…"
            value={draft}
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && send()}
          />
          <button className="pc-shuffle__send" onClick={send} aria-label="Send"><PC_ICONS.send/></button>
        </div>
      </div>
    </>
  );
}

// ─── ARTIST ─────────────────────────────────────────────────────────
function ArtistScreen({ onMenu, onPlay, onLong, artist, onClose, dark, onQueue, onOpenAlbum, onArtistMenu }) {
  const [tab, setTab] = useStateS('Discography');
  const [filter, setFilter] = useStateS('All');
  const a = PC_DATA.ARTISTS[artist] || { genre: 'Indie', resolvers: ['spotify'] };
  const disco = PC_DATA.QA_DISCO;
  const topTracks = PC_DATA.TOP_TRACKS.map((t, i) => ({ ...t, artist, resolver: a.resolvers[i % a.resolvers.length], plays: 480 - i * 63 }));
  const counts = { All: disco.length, 'Studio Albums': disco.filter(d => d.type === 'ALBUM').length, EPs: 1, Singles: disco.filter(d => d.type === 'SINGLE').length };
  const filtered = filter === 'All' ? disco
    : filter === 'Studio Albums' ? disco.filter(d => d.type === 'ALBUM')
    : filter === 'Singles' ? disco.filter(d => d.type === 'SINGLE')
    : disco.slice(0, 1);

  return (
    <>
      <div style={{
        position: 'absolute', top: 0, left: 0, right: 0, zIndex: 5,
        paddingTop: 54, pointerEvents: 'none',
      }}>
        <div className="ios-navRow" style={{ pointerEvents: 'auto' }}>
          <button className="ios-glass icon" onClick={onClose} aria-label="Back">
            <PC_ICONS.chevronL width="18" height="18" />
          </button>
          <button className="ios-glass icon" aria-label="More" onClick={() => onArtistMenu && onArtistMenu(artist)}>
            <PC_ICONS.more width="20" height="20" />
          </button>
        </div>
      </div>

      <div className="pc-scroll" style={{ paddingBottom: 200 }}>
        <div style={{ position: 'relative' }}>
          <div className="ios-artist__hero" style={{
            background: `linear-gradient(135deg, ${ART_COLOR(artist)[0]}, ${ART_COLOR(artist)[1]})`,
          }}>
            <div style={{
              position: 'absolute', inset: 0,
              backgroundImage: `radial-gradient(circle at 30% 40%, rgba(255,255,255,0.15), transparent 50%)`,
              mixBlendMode: 'overlay',
            }} />
          </div>
          <div className="ios-artist__heroTitle">{artist}</div>
          <div className="ios-artist__cta">
            <button onClick={() => onPlay({ title: 'Rat Handed', artist, resolver: a.resolvers[0] })}>
              <PC_ICONS.play width="14" height="14" /> Play Top Tracks
            </button>
          </div>
        </div>

        <div style={{ paddingTop: 8 }}>
          <div className="ios-artist__tabs">
            {['Discography', 'Top Tracks', 'Biography', 'Related Artists'].map(t => (
              <button key={t} className={`ios-artist__tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>{t}</button>
            ))}
          </div>
          {tab === 'Discography' && (
            <>
              <div className="ios-artist__chips">
                {Object.entries(counts).map(([k, v]) => (
                  <div key={k} className={`ios-artist__chip ${filter === k ? 'active' : ''}`} onClick={() => setFilter(k)}>
                    {k} ({v})
                  </div>
                ))}
              </div>
              <div className="ios-artist__grid">
                {filtered.map((d, i) => (
                  <div key={i} className="ios-artist__relCard" onClick={() => onOpenAlbum && onOpenAlbum({ title: d.title, artist, year: d.year, type: d.type })}>
                    <div className="ios-artist__relArt"><ArtPlaceholder name={d.title + artist} size={160} radius={10} fill /></div>
                    <div className="ios-artist__relTitle">{d.title}</div>
                    <div className="ios-artist__relMeta">
                      <span>{d.year}</span>
                      <span className="release-badge" style={{ background: d.type === 'ALBUM' ? 'var(--accent-a10)' : 'var(--bg-inset)', color: d.type === 'ALBUM' ? 'var(--accent-primary)' : 'var(--fg2)' }}>
                        {d.type}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
          {tab === 'Top Tracks' && (
            <div style={{ paddingTop: 4 }}>
              {topTracks.map((t, i) => (
                <div key={i} className="pc-row" onClick={() => onPlay({ ...t })}
                  onContextMenu={(e) => { e.preventDefault(); onLong && onLong(t); }}>
                  <div className="pc-row__num" style={{ fontWeight: 700, color: 'var(--accent-primary)' }}>{i + 1}</div>
                  <ArtPlaceholder name={t.title + artist} size={44} radius={6} />
                  <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                    <div className="pc-row__title">{t.title}</div>
                    <div className="pc-row__artist">{t.plays.toLocaleString()} plays</div>
                  </div>
                  <ResolverChip kind={t.resolver} />
                  <div className="pc-row__dur">{t.dur}</div>
                </div>
              ))}
            </div>
          )}
          {tab === 'Biography' && (
            <div style={{ padding: '20px 20px 60px', font: '400 15px/1.55 var(--font-sans)', color: 'var(--fg1)' }}>
              <p style={{ marginTop: 0 }}><strong>{artist}</strong> formed in 2019 as a bedroom recording project and grew into a four-piece touring act. Their early singles circulated on Bandcamp before getting picked up by indie blogs.</p>
              <p>Known for jagged guitars and confessional songwriting, the band has a small but rabid following on Bandcamp and a steadily growing presence on Spotify.</p>
              <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
                {a.resolvers.map(r => <ResolverChip key={r} kind={r} />)}
                <span style={{ font: '400 13px/1.3 var(--font-sans)', color: 'var(--fg2)' }}>Available on {a.resolvers.length} sources</span>
              </div>
            </div>
          )}
          {tab === 'Related Artists' && (
            <div className="ios-artist__grid">
              {['Dogleg', 'Born Ruffians', 'Car Seat Headrest', 'Smashing Pumpkins'].map((nm, i) => (
                <div key={i} className="ios-artist__relCard" onClick={() => {}}>
                  <div className="ios-artist__relArt"><ArtPlaceholder name={nm} size={160} radius={10} fill /></div>
                  <div className="ios-artist__relTitle">{nm}</div>
                  <div className="ios-artist__relMeta"><span>{PC_DATA.ARTISTS[nm]?.genre || 'Indie'}</span></div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function ART_COLOR(name) {
  const h = hashStr(name);
  const palettes = [['#3d3d3d', '#1a1a1a'], ['#2a3a4a', '#0e1822'], ['#3a2a3a', '#1a121a'], ['#4a3a2a', '#1f1810'], ['#2a4a3a', '#0e221a']];
  return palettes[h % palettes.length];
}

Object.assign(window, { HomeScreen, SearchScreen, CollectionScreen, PlaylistsScreen, ShuffleupagusScreen, ArtistScreen, TrackRow });
