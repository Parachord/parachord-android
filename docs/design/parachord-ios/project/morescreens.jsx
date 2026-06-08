// morescreens.jsx — Curated list, History, Settings, Concerts (pushed screens)
// Exports: CuratedListScreen, HistoryScreen, SettingsScreen, CURATED

const { useState: useStateM } = React;

// Shared floating back/▸ glass header used by all pushed screens.
// Pass `title` for a solid top bar with an uppercased, letter-spaced title (detail screens).
function PushBar({ onClose, title, onMore }) {
  if (title) {
    return (
      <div className="ios-pushbar">
        <button className="ios-pushbar__back" onClick={onClose} aria-label="Back">
          <PC_ICONS.chevronL width="22" height="22" />
        </button>
        <div className="ios-pushbar__title">{title}</div>
        {onMore !== false && (
          <button className="ios-pushbar__more" aria-label="More"><PC_ICONS.more width="20" height="20" /></button>
        )}
      </div>
    );
  }
  return (
    <div style={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 5, paddingTop: 54, pointerEvents: 'none' }}>
      <div className="ios-navRow" style={{ pointerEvents: 'auto' }}>
        <button className="ios-glass icon" onClick={onClose} aria-label="Back">
          <PC_ICONS.chevronL width="18" height="18" />
        </button>
        <button className="ios-glass icon" aria-label="More">
          <PC_ICONS.more width="20" height="20" />
        </button>
      </div>
    </div>
  );
}

// Track lists (artist + resolver + duration), rotated per preset for variety
const POOL = [
  { title: 'Rat Handed',                  artist: 'Quarantine Angst', dur: '3:20', resolver: 'spotify' },
  { title: 'Western Sky',                 artist: 'Dangermuffin',     dur: '4:12', resolver: 'spotify' },
  { title: 'Fox',                         artist: 'Dogleg',           dur: '2:50', resolver: 'bandcamp' },
  { title: 'No Name (Track 7)',           artist: 'Jack White',       dur: '3:09', resolver: 'apple' },
  { title: 'Athena',                      artist: 'Born Ruffians',    dur: '3:48', resolver: 'apple' },
  { title: '1080p2020',                   artist: 'Fu Kaisha',        dur: '2:18', resolver: 'soundcloud' },
  { title: 'Ox Bone',                     artist: 'The Sleeping Cliffs', dur: '5:01', resolver: 'bandcamp' },
  { title: 'Saving Songs for Sunday',     artist: 'Quarantine Angst', dur: '3:48', resolver: 'spotify' },
  { title: 'Deliberate',                  artist: 'Quarantine Angst', dur: '3:02', resolver: 'bandcamp' },
  { title: 'Dark Matter',                 artist: 'Pearl Jam',        dur: '5:34', resolver: 'apple' },
  { title: 'Cyclone',                     artist: 'Dogleg',           dur: '2:41', resolver: 'bandcamp' },
  { title: 'Solar Power',                 artist: 'Nikki Lane',       dur: '3:55', resolver: 'spotify' },
];
const rotate = (arr, n) => arr.slice(n).concat(arr.slice(0, n));

const CURATED = {
  recommendations: { title: 'Recommendations', stats: '65 Artists · 80 Songs', sub: 'Personalized picks for parachord_user', grad: ['#6d5bf0', '#c026d3'], chips: ['All (80)', 'ListenBrainz (50)', 'Last.fm (30)'], tracks: rotate(POOL, 0) },
  pop:             { title: 'Pop of the Tops',  stats: '50 Songs · This Week',  sub: 'Climbing the Sonemic charts right now',  grad: ['#ef4444', '#ea580c'], chips: ['All (50)', 'This Week', 'All Time'],          tracks: rotate(POOL, 3) },
  critical:        { title: 'Critical Darlings', stats: '60 Songs · 4.5★+',     sub: 'Top-rated on RateYourMusic & Pitchfork',  grad: ['#f59e0b', '#b45309'], chips: ['All (60)', 'RYM', 'Pitchfork'],             tracks: rotate(POOL, 6) },
  foryou:          { title: 'For You',           stats: '40 Songs · Daily Mix', sub: 'A daily mix tuned to your listening',     grad: ['#a855f7', '#7c3aed'], chips: ['All (40)', 'New', 'Familiar'],              tracks: rotate(POOL, 2) },
};

// Fresh Drops — release grid (albums / EPs / singles), some upcoming
const RELEASES = [
  { title: 'Little Wide Open',     artist: 'Waxahatchee',     date: 'Coming May 15', type: 'ALBUM',  up: true },
  { title: "It's the Long Goodbye", artist: 'The Twilight Sad', date: 'Coming Mar 27', type: 'ALBUM',  up: true },
  { title: 'Creature of Habit',    artist: 'Courtney Barnett', date: 'Coming Mar 27', type: 'SINGLE', up: true },
  { title: 'The Weight of Woods',  artist: 'Dermot Kennedy',   date: 'Coming Mar 27', type: 'ALBUM',  up: true },
  { title: 'Trickle Down',         artist: 'Sprints',          date: 'Mar 3',         type: 'SINGLE', up: false },
  { title: 'Attempt a Crash…',     artist: 'The Twilight Sad', date: 'Feb 24',        type: 'SINGLE', up: false },
  { title: 'Mantis / Sugar Plum',  artist: 'Courtney Barnett', date: 'Feb 24',        type: 'SINGLE', up: false },
  { title: 'Refuge',               artist: 'Dermot Kennedy',   date: 'Feb 20',        type: 'EP',     up: false },
  { title: 'Post-Pardon',          artist: 'Quarantine Angst', date: 'Feb 18',        type: 'ALBUM',  up: false },
  { title: 'Bueno',                artist: 'Dogleg',           date: 'Feb 12',        type: 'ALBUM',  up: false },
];

// Star pattern for the Recommendations-style hero
function HeroStars() {
  const stars = [];
  for (let r = 0; r < 4; r++) for (let cI = 0; cI < 7; cI++) {
    stars.push(<polygon key={r + '-' + cI} points="6,0 7.4,4.2 12,4.2 8.3,6.9 9.7,11 6,8.5 2.3,11 3.7,6.9 0,4.2 4.6,4.2"
      transform={`translate(${cI * 60 + (r % 2 ? 30 : 0)}, ${r * 46}) scale(1.4)`} fill="#fff" />);
  }
  return <svg className="ios-cur__stars" viewBox="0 0 420 200" preserveAspectRatio="xMidYMid slice">{stars}</svg>;
}

// ─── Fresh Drops — release grid ─────────────────────────────────────────
function FreshDropsScreen({ onClose, onPlay, onOpenAlbum }) {
  const [f, setF] = useStateM('All');
  const filters = [`All (${RELEASES.length})`, `Albums (${RELEASES.filter(r => r.type === 'ALBUM').length})`, `EPs (${RELEASES.filter(r => r.type === 'EP').length})`, `Singles (${RELEASES.filter(r => r.type === 'SINGLE').length})`];
  const key = f.split(' ')[0];
  const shown = key === 'All' ? RELEASES : RELEASES.filter(r => (key === 'Albums' && r.type === 'ALBUM') || (key === 'EPs' && r.type === 'EP') || (key === 'Singles' && r.type === 'SINGLE'));
  return (
    <>
      <PushBar onClose={onClose} />
      <div className="pc-scroll" style={{ paddingTop: 0, paddingBottom: 200 }}>
        <div className="ios-cur__hero" style={{ background: 'linear-gradient(135deg, #10b981, #0e7490)' }}>
          <h1>Fresh Drops</h1>
          <div className="ios-cur__stats">{RELEASES.length} Releases</div>
        </div>
        <div className="ios-cur__chips">
          {filters.map(x => <div key={x} className={`ios-cur__chip ${f === x ? 'active' : ''}`} onClick={() => setF(x)}>{x}</div>)}
        </div>
        <div className="ios-fresh__grid">
          {shown.map((r, i) => (
            <div key={i} className="ios-fresh__card" onClick={() => onOpenAlbum && onOpenAlbum({ title: r.title, artist: r.artist, year: 2026, type: r.type })}>
              <div className="ios-fresh__art">
                <ArtPlaceholder name={r.title + r.artist} size={160} radius={10} fill />
                <span className="ios-fresh__badge">{r.type}</span>
              </div>
              <div className="ios-fresh__title">{r.title}</div>
              <div className="ios-fresh__artist">{r.artist}</div>
              <div className={`ios-fresh__date ${r.up ? 'upcoming' : 'out'}`}>{r.date}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Critical Darlings — album list with critic blurbs ──────────────────
const DARLINGS = [
  { title: 'Post-Pardon',  artist: 'Quarantine Angst', rating: '4.7', src: 'Pitchfork', type: 'ALBUM', year: 2026, blurb: 'A jagged, confessional triumph — the rare record that sounds both blown-out and intimate, like a basement show pressed straight to tape.' },
  { title: 'The Scholars', artist: 'Car Seat Headrest', rating: '4.6', src: 'RYM',       type: 'ALBUM', year: 2025, blurb: "Will Toledo's most ambitious song-cycle yet, trading lo-fi murk for widescreen arrangements without ever losing the bite." },
  { title: 'Bueno',        artist: 'Dogleg',            rating: '4.8', src: 'RYM',       type: 'ALBUM', year: 2024, blurb: 'Twenty-eight minutes of pure kinetic catharsis — emo-punk that never once stops sprinting.' },
  { title: 'Dark Matter',  artist: 'Pearl Jam',         rating: '4.5', src: 'Pitchfork', type: 'ALBUM', year: 2024, blurb: 'Their most urgent set in two decades: taut, angry, and unexpectedly limber.' },
  { title: 'Athena',       artist: 'Born Ruffians',     rating: '4.4', src: 'Pitchfork', type: 'EP',    year: 2025, blurb: 'Wiry, melodic, and quietly devastating — a grower that rewards every repeat spin.' },
  { title: 'No Name',      artist: 'Jack White',        rating: '4.6', src: 'RYM',       type: 'ALBUM', year: 2024, blurb: 'A surprise drop that strips the myth back to riffs, grit, and forward momentum.' },
];

function CriticalDarlingsScreen({ onClose, onOpenAlbum }) {
  const [chip, setChip] = useStateM('All');
  const chips = ['All', 'RYM', 'Pitchfork'];
  const shown = chip === 'All' ? DARLINGS : DARLINGS.filter(d => d.src === chip);
  return (
    <>
      <PushBar onClose={onClose} />
      <div className="pc-scroll" style={{ paddingTop: 0, paddingBottom: 200 }}>
        <div className="ios-cur__hero" style={{ background: 'linear-gradient(135deg, #f59e0b, #b45309)' }}>
          <HeroStars />
          <h1>Critical Darlings</h1>
          <div className="ios-cur__stats">{DARLINGS.length} Albums · 4.5★+</div>
          <div className="ios-cur__sub">Top-rated on RateYourMusic &amp; Pitchfork</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 20px 6px' }}>
          <div style={{ flex: 1, display: 'inline-flex', alignItems: 'center', gap: 5, font: '600 15px/1 var(--font-sans)', color: 'var(--fg1)', cursor: 'pointer' }}
            onClick={() => setChip(chips[(chips.indexOf(chip) + 1) % chips.length])}>
            {chip === 'All' ? 'All Sources' : chip} <PC_ICONS.chevronD width="15" height="15" style={{ color: 'var(--fg2)' }} />
          </div>
          <button className="ios-glass icon" style={{ width: 34, height: 34 }} aria-label="Sort">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h12M3 12h8M3 18h5M17 6v12M17 18l3-3M17 18l-3-3"/></svg>
          </button>
          <button className="ios-glass icon" style={{ width: 34, height: 34 }} aria-label="Refresh">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 11-3-6.7M21 4v4h-4"/></svg>
          </button>
        </div>
        <div>
          {shown.map((d, i) => (
            <div key={i} className="ios-darling" onClick={() => onOpenAlbum && onOpenAlbum({ title: d.title, artist: d.artist, year: d.year, type: d.type })}>
              <div className="ios-darling__art"><ArtPlaceholder name={d.title + d.artist} size={88} radius={8} /></div>
              <div className="ios-darling__body">
                <div className="ios-darling__title">{d.title}</div>
                <div className="ios-darling__artist">{d.artist} · {d.year}</div>
                <div className="ios-darling__rating">
                  <span className="ios-darling__stars">★ {d.rating}</span>
                  <span className="ios-darling__src">{d.src}</span>
                </div>
                <div className="ios-darling__blurb">{d.blurb}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Curated list (Recommendations / Pop of the Tops / For You)
function CuratedListScreen({ preset, onClose, onPlay, onLong, onQueue, onOpenAlbum }) {
  if (preset === 'fresh') return <FreshDropsScreen onClose={onClose} onPlay={onPlay} onOpenAlbum={onOpenAlbum} />;
  if (preset === 'critical') return <CriticalDarlingsScreen onClose={onClose} onOpenAlbum={onOpenAlbum} />;
  if (preset === 'pop') return <PopChartScreen onClose={onClose} onPlay={onPlay} onOpenAlbum={onOpenAlbum} />;
  const c = CURATED[preset] || CURATED.recommendations;
  const [chip, setChip] = useStateM(c.chips[0]);
  return (
    <>
      <PushBar onClose={onClose} />
      <div className="pc-scroll" style={{ paddingTop: 0, paddingBottom: 200 }}>
        <div className="ios-cur__hero" style={{ background: `linear-gradient(135deg, ${c.grad[0]}, ${c.grad[1]})` }}>
          <HeroStars />
          <h1>{c.title}</h1>
          <div className="ios-cur__stats">{c.stats}</div>
          <div className="ios-cur__sub">{c.sub}</div>
        </div>
        <div className="ios-cur__chips">
          {c.chips.map(x => <div key={x} className={`ios-cur__chip ${chip === x ? 'active' : ''}`} onClick={() => setChip(x)}>{x}</div>)}
        </div>
        <div>
          {c.tracks.map((t, i) => (
            <TrackRow key={i} track={{ ...t, n: i + 1 }} onPlay={() => onPlay({ ...t })} onLong={onLong} onQueue={onQueue} />
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Pop of the Tops — chart with movement ──────────────────────────────
function PopChartScreen({ onClose, onPlay, onOpenAlbum }) {
  const [scope, setScope] = useStateM('This Week');
  const base = rotate(POOL, 4);
  const moves = ['up', 'up', 'same', 'down', 'up', 'same', 'down', 'up', 'down', 'same', 'up', 'same'];
  const deltas = [2, 1, 0, 3, 5, 0, 1, 4, 2, 0, 6, 0];
  const chart = base.concat(base.slice(0, 4)).map((t, i) => ({ ...t, move: moves[i % moves.length], delta: deltas[i % deltas.length] }));
  return (
    <>
      <PushBar onClose={onClose} />
      <div className="pc-scroll" style={{ paddingTop: 0, paddingBottom: 200 }}>
        <div className="ios-cur__hero" style={{ background: 'linear-gradient(135deg, #ef4444, #ea580c)' }}>
          <HeroStars />
          <h1>Pop of the Tops</h1>
          <div className="ios-cur__stats">Top {chart.length} · {scope}</div>
          <div className="ios-cur__sub">Trending across Sonemic charts</div>
        </div>
        <div className="ios-cur__chips">
          {['This Week', 'Last Week', 'All Time'].map(x => <div key={x} className={`ios-cur__chip ${scope === x ? 'active' : ''}`} onClick={() => setScope(x)}>{x}</div>)}
        </div>
        <div>
          {chart.map((t, i) => (
            <div key={i} className="ios-chart__row" onClick={() => onPlay({ ...t })}>
              <div className="ios-chart__rank">{i + 1}</div>
              <div className={`ios-chart__move ${t.move}`}>
                {t.move === 'up' && <><span>▲</span><span>{t.delta}</span></>}
                {t.move === 'down' && <><span>▼</span><span>{t.delta}</span></>}
                {t.move === 'same' && <span>–</span>}
              </div>
              <div className="ios-chart__art"><ArtPlaceholder name={t.title + t.artist} size={48} radius={6} /></div>
              <div className="ios-chart__meta">
                <div className="ios-chart__title">{t.title}</div>
                <div className="ios-chart__artist">{t.artist}</div>
              </div>
              <ResolverChip kind={t.resolver} />
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Listening activity (shared by History + Friend) ───────────────────
function ListeningActivity({ order, onPlay, onLong, onArtist, onOpenAlbum }) {
  const TABS = order || ['Top Songs', 'Top Albums', 'Top Artists', 'Recent'];
  const [tab, setTab] = useStateM(TABS[0]);
  const [range, setRange] = useStateM('Month');
  const RANGES = ['7 Days', 'Month', '3 Months', '6 Months', '12 Months'];

  const groups = [
    { label: 'Today', items: [ { ...POOL[0], at: '2:14 PM' }, { ...POOL[2], at: '1:58 PM' }, { ...POOL[5], at: '1:40 PM' }, { ...POOL[3], at: '11:02 AM' } ] },
    { label: 'Yesterday', items: [ { ...POOL[7], at: '9:21 PM' }, { ...POOL[9], at: '8:47 PM' }, { ...POOL[1], at: '8:30 PM' } ] },
    { label: 'Earlier', items: [ { ...POOL[6], at: 'Mon' }, { ...POOL[10], at: 'Mon' }, { ...POOL[4], at: 'Sun' } ] },
  ];
  const topTracks = rotate(POOL, 1).map((t, i) => ({ ...t, plays: 142 - i * 11 }));
  const topAlbums = [
    { title: 'Post-Pardon', artist: 'Quarantine Angst', plays: 284 }, { title: 'Bueno', artist: 'Dogleg', plays: 196 },
    { title: 'Dark Matter', artist: 'Pearl Jam', plays: 173 }, { title: 'No Name', artist: 'Jack White', plays: 142 },
    { title: 'The Scholars', artist: 'Car Seat Headrest', plays: 118 }, { title: 'Athena', artist: 'Born Ruffians', plays: 96 },
  ];
  const topArtists = [
    { name: 'Quarantine Angst', plays: 1284 }, { name: 'Dogleg', plays: 642 }, { name: 'Pearl Jam', plays: 531 },
    { name: 'Jack White', plays: 488 }, { name: 'Born Ruffians', plays: 372 }, { name: 'Fu Kaisha', plays: 241 },
  ];

  return (
    <>
      <div className="ios-counttabs">
        {TABS.map(t => (
          <button key={t} className={`ios-counttab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>
      {tab !== 'Recent' && (
        <div className="ios-rangechips">
          {RANGES.map(r => (
            <button key={r} className={`ios-rangechip ${range === r ? 'active' : ''}`} onClick={() => setRange(r)}>{r}</button>
          ))}
        </div>
      )}

      {tab === 'Recent' && groups.map((g, gi) => (
        <div key={gi}>
          <div className="pc-section-h">{g.label}</div>
          {g.items.map((t, i) => (
            <div key={i} className="pc-row" onClick={() => onPlay({ ...t })} onContextMenu={(e) => { e.preventDefault(); onLong && onLong(t); }}>
              <ArtPlaceholder name={t.title + t.artist} size={44} radius={6} />
              <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                <div className="pc-row__title">{t.title}</div>
                <div className="pc-row__artist">{t.artist}</div>
              </div>
              <ResolverChip kind={t.resolver} />
              <div className="pc-row__time">{t.at}</div>
            </div>
          ))}
        </div>
      ))}

      {tab === 'Top Songs' && (
        <div style={{ paddingTop: 6 }}>
          {topTracks.map((t, i) => (
            <div key={i} className="pc-row" onClick={() => onPlay({ ...t })}>
              <div className="pc-row__num" style={{ fontWeight: 700, color: 'var(--accent-primary)' }}>{i + 1}</div>
              <ArtPlaceholder name={t.title + t.artist} size={44} radius={6} />
              <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                <div className="pc-row__title">{t.title}</div>
                <div className="pc-row__artist">{t.artist}</div>
              </div>
              <ResolverChip kind={t.resolver} />
              <div className="pc-row__time">{t.plays} plays</div>
            </div>
          ))}
        </div>
      )}

      {tab === 'Top Albums' && (
        <div className="ios-rankgrid">
          {topAlbums.map((al, i) => (
            <div key={i} className="ios-rank" onClick={() => onOpenAlbum && onOpenAlbum({ title: al.title, artist: al.artist, year: 2025, type: 'ALBUM' })}>
              <div className="ios-rank__art" style={{ borderRadius: 10 }}>
                <ArtPlaceholder name={al.title + al.artist} size={160} radius={10} fill />
                <span className="ios-rank__badge">#{i + 1}</span>
                <span className="ios-rank__plays">{al.plays} plays</span>
              </div>
              <div className="ios-rank__name">{al.title}</div>
              <div className="ios-rank__sub">{al.artist}</div>
            </div>
          ))}
        </div>
      )}

      {tab === 'Top Artists' && (
        <div className="ios-rankgrid">
          {topArtists.map((ar, i) => (
            <div key={i} className="ios-rank" onClick={() => onArtist && onArtist(ar.name)}>
              <div className="ios-rank__art" style={{ borderRadius: 999 }}>
                <ArtPlaceholder name={ar.name + ' artist'} size={160} radius={999} fill />
                <span className="ios-rank__badge">#{i + 1}</span>
              </div>
              <div className="ios-rank__name">{ar.name}</div>
              <div className="ios-rank__sub">{ar.plays.toLocaleString()} plays</div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

// ─── History — your listening stats ────────────────────────────────────
function HistoryScreen({ onClose, onPlay, onLong, onQueue, onArtist, onOpenAlbum }) {
  return (
    <>
      <PushBar onClose={onClose} title="History" />
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <ListeningActivity order={['Top Songs', 'Top Albums', 'Top Artists', 'Recent']}
          onPlay={onPlay} onLong={onLong} onArtist={onArtist} onOpenAlbum={onOpenAlbum} />
      </div>
    </>
  );
}

// ─── Settings — Plug-ins / General / About ──────────────────────────────
function SettingsScreen({ onClose, dark }) {
  const [tab, setTab] = useStateM('Plug-ins');
  const [toggles, setToggles] = useStateM({ gapless: true, crossfade: false, scrobble: true, dataSaver: false });
  const flip = (key) => setToggles(s => ({ ...s, [key]: !s[key] }));

  const RESOLVERS = [
    { name: 'Apple Music', color: '#FA243C', glyph: <svg width="38" height="38" viewBox="0 0 24 24" fill="#fff"><path d="M16 4l-7 1.5v9.1a3 3 0 10.9 2.15V9l5.1-1.1v4.7a3 3 0 10.9 2.15V4z"/></svg>, on: true },
    { name: 'Spotify', color: '#1DB954', glyph: <svg width="40" height="40" viewBox="0 0 24 24" fill="#fff"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm4.6 14.4a.62.62 0 01-.86.21c-2.35-1.44-5.3-1.76-8.79-.96a.62.62 0 11-.28-1.22c3.82-.87 7.09-.5 9.72 1.11a.62.62 0 01.21.86zm1.23-2.74a.78.78 0 01-1.07.26c-2.69-1.66-6.8-2.14-9.98-1.17a.78.78 0 11-.45-1.49c3.64-1.1 8.17-.57 11.25 1.33a.78.78 0 01.25 1.07zm.11-2.85C14.83 8.96 9.4 8.79 6.3 9.73a.93.93 0 11-.54-1.78c3.56-1.08 9.56-.87 13.34 1.37a.93.93 0 11-.95 1.6z"/></svg>, on: true },
    { name: 'Local Files', color: '#6366f1', glyph: <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><path d="M12 16v-5M9.5 13l2.5-2.5L14.5 13"/></svg>, on: true },
    { name: 'Bandcamp', color: '#1da0c3', glyph: <svg width="40" height="40" viewBox="0 0 24 24" fill="#fff"><path d="M3 16l5-8h13l-5 8z"/></svg>, on: true },
    { name: 'SoundCloud', color: '#ff5500', glyph: <svg width="42" height="42" viewBox="0 0 24 24" fill="#fff"><path d="M4 14v4M7 12v6M10 10v8M13 9v9h5.5a3.5 3.5 0 000-7c-.4 0-.8.07-1.2.2A4.5 4.5 0 0013 9z"/></svg>, on: false },
  ];
  const META = [
    { name: 'Achordion', color: '#7c3aed', label: 'a', on: true },
    { name: 'ChatGPT', color: '#10a37f', glyph: <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.4"><path d="M12 4a4 4 0 00-3.8 2.8A4 4 0 006 14a4 4 0 003.8 5.2A4 4 0 0018 17a4 4 0 00-.2-7.2A4 4 0 0012 4z"/></svg>, on: true },
    { name: 'Claude', color: '#d97757', label: '✻', on: false },
    { name: 'Discogs', color: '#1a1a1a', glyph: <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.6"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.5" fill="#fff"/></svg>, on: true },
    { name: 'Google Gemini', color: '#4285f4', glyph: <svg width="34" height="34" viewBox="0 0 24 24" fill="#fff"><path d="M12 2c.5 5 5 9.5 10 10-5 .5-9.5 5-10 10-.5-5-5-9.5-10-10 5-.5 9.5-5 10-10z"/></svg>, on: true },
    { name: 'Last.fm', color: '#d51007', label: 'lastfm', on: true },
    { name: 'Libre.fm', color: '#2e8b57', label: 'LIBRE.FM', on: false },
    { name: 'ListenBrainz', color: '#1e2a5a', glyph: <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.6"><path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/></svg>, on: true },
    { name: 'Wikipedia', color: '#000', label: 'W', serif: true, on: true },
  ];
  const CONCERTS_P = [
    { name: 'Bandsintown', color: '#00b4b3', label: 'bit', on: false },
    { name: 'SeatGeek', color: '#ff5b00', label: 'SEAT GEEK', on: true },
    { name: 'Songkick', color: '#f80046', label: 'sk', on: false },
    { name: 'Ticketmaster', color: '#026cdf', label: 't', on: true },
  ];

  const Check = () => <span className="ios-btile__chk"><svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M2 6.5l3 3 5-6"/></svg></span>;
  const Tile = ({ item, num }) => (
    <div className={`ios-btile ${item.on ? '' : 'off'}`}>
      <div className="ios-btile__sq" style={{ background: item.color }}>
        {num != null && <span className="ios-btile__num">{num}</span>}
        {item.on && <Check />}
        {item.glyph ? item.glyph : <span className={`ios-btile__label ${item.serif ? 'serif' : ''}`}>{item.label}</span>}
      </div>
      <div className="ios-btile__name">{item.name}</div>
    </div>
  );

  return (
    <>
      <PushBar onClose={onClose} title="Settings" onMore={false} />
      <div className="ios-counttabs" style={{ position: 'absolute', top: 100, left: 0, right: 0, zIndex: 5, background: 'var(--bg-primary)' }}>
        {['Plug-ins', 'General', 'About'].map(t => (
          <button key={t} className={`ios-counttab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>
      <div className="pc-scroll" style={{ paddingTop: 145, paddingBottom: 200 }}>
        {tab === 'Plug-ins' && (
          <>
            <div className="pc-section-h">Content Resolvers</div>
            <div className="ios-set-sub">Drag to reorder playback priority</div>
            <div className="ios-restrip">
              {RESOLVERS.map((r, i) => (
                <div key={r.name} className="ios-restrip__item">
                  {r.on && (
                    <div className={`ios-btile`} style={{ width: 'auto' }}>
                      <div className="ios-btile__sq" style={{ background: r.color }}>
                        <span className="ios-btile__num">{i + 1}</span>
                        <Check />
                        {r.glyph}
                      </div>
                      <div className="ios-btile__name">{r.name}</div>
                    </div>
                  )}
                  {!r.on && (
                    <div className="ios-btile off" style={{ width: 'auto' }}>
                      <div className="ios-btile__sq" style={{ background: r.color }}>{r.glyph}</div>
                      <div className="ios-btile__name">{r.name}</div>
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="ios-set-sub" style={{ paddingTop: 0, color: 'var(--fg3)' }}>SoundCloud — Not connected</div>

            <div className="pc-section-h" style={{ marginTop: 6 }}>Meta Services</div>
            <div className="ios-set-sub">Services for recommendations, metadata, and AI features</div>
            <div className="ios-bgrid">
              {META.map(m => <Tile key={m.name} item={m} />)}
            </div>

            <div className="pc-section-h" style={{ marginTop: 6 }}>Concerts &amp; Events</div>
            <div className="ios-set-sub">Concert discovery and ticket providers</div>
            <div className="ios-bgrid">
              {CONCERTS_P.map(c => <Tile key={c.name} item={c} />)}
            </div>

            <div className="ios-plupd">
              <div>
                <div className="ios-plupd__t">Plugin Updates</div>
                <div className="ios-plupd__s">18 plugins loaded</div>
              </div>
              <button className="ios-plupd__btn">Check for updates</button>
            </div>
          </>
        )}

        {tab === 'General' && (
          <>
            <div className="ios-set__profile">
              <div className="ios-set__avatar">P</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ font: '600 18px/1.2 var(--font-sans)', color: 'var(--fg1)', letterSpacing: '-0.2px' }}>parachord_user</div>
                <div style={{ font: '400 13px/1.3 var(--font-sans)', color: 'var(--fg2)', marginTop: 2 }}>Scrobbling to ListenBrainz · 18,402 plays</div>
              </div>
              <PC_ICONS.chevronR width="18" height="18" style={{ color: 'var(--fg3)' }} />
            </div>
            <div className="ios-set__seclabel">Playback</div>
            <div className="ios-set__group">
              <div className="ios-set__row" onClick={() => flip('gapless')}>
                <div className="ios-set__meta"><div className="ios-set__name">Gapless Playback</div></div>
                <div className={`ios-set__toggle ${toggles.gapless ? 'on' : ''}`} />
              </div>
              <div className="ios-set__row" onClick={() => flip('crossfade')}>
                <div className="ios-set__meta"><div className="ios-set__name">Crossfade</div><div className="ios-set__sub">{toggles.crossfade ? '6 seconds' : 'Off'}</div></div>
                <div className={`ios-set__toggle ${toggles.crossfade ? 'on' : ''}`} />
              </div>
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">Audio Quality</div></div>
                <span className="ios-set__sub" style={{ marginRight: 6 }}>Lossless</span>
                <PC_ICONS.chevronR width="16" height="16" className="ios-set__chev" />
              </div>
              <div className="ios-set__row" onClick={() => flip('dataSaver')}>
                <div className="ios-set__meta"><div className="ios-set__name">Data Saver</div></div>
                <div className={`ios-set__toggle ${toggles.dataSaver ? 'on' : ''}`} />
              </div>
            </div>
            <div className="ios-set__seclabel">Account</div>
            <div className="ios-set__group">
              <div className="ios-set__row" onClick={() => flip('scrobble')}>
                <div className="ios-set__meta"><div className="ios-set__name">Scrobble to ListenBrainz</div></div>
                <div className={`ios-set__toggle ${toggles.scrobble ? 'on' : ''}`} />
              </div>
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">Manage Subscription</div></div>
                <PC_ICONS.chevronR width="16" height="16" className="ios-set__chev" />
              </div>
            </div>
          </>
        )}

        {tab === 'About' && (
          <>
            <div className="ios-set__seclabel">About</div>
            <div className="ios-set__group">
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">Version</div></div>
                <span className="ios-set__sub">2.4.0 (iOS)</span>
              </div>
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">What's New</div></div>
                <PC_ICONS.chevronR width="16" height="16" className="ios-set__chev" />
              </div>
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">Help &amp; Feedback</div></div>
                <PC_ICONS.chevronR width="16" height="16" className="ios-set__chev" />
              </div>
              <div className="ios-set__row">
                <div className="ios-set__meta"><div className="ios-set__name">Open Source Licenses</div></div>
                <PC_ICONS.chevronR width="16" height="16" className="ios-set__chev" />
              </div>
            </div>
            <div className="ios-set-sub" style={{ textAlign: 'center', paddingTop: 24 }}>Parachord for iOS · Made with ♪</div>
          </>
        )}
      </div>
    </>
  );
}

// ─── Album detail ───────────────────────────────────────────────────────
function AlbumDetailScreen({ album, onClose, onPlay, onLong, onQueue, onArtist, onAlbumMenu }) {
  const al = album || {};
  const artist = al.artist || 'Quarantine Angst';
  const resolvers = (PC_DATA.ARTISTS[artist] && PC_DATA.ARTISTS[artist].resolvers) || ['spotify'];
  const rowResolvers = resolvers.includes('apple') ? ['apple', 'spotify'] : (resolvers.length > 1 ? resolvers.slice(0, 2) : [resolvers[0], 'spotify']);
  const n = al.type === 'SINGLE' ? 2 : al.type === 'EP' ? 5 : 9;
  const tracks = rotate(POOL, ((al.title || '').length) % POOL.length).slice(0, n)
    .map((t, i) => ({ title: t.title, artist, dur: t.dur, n: i + 1 }));
  const kindLabel = al.type === 'SINGLE' ? 'SINGLE' : al.type === 'EP' ? 'EP' : 'ALBUM';
  return (
    <>
      <PushBar onClose={onClose} title={al.title} />
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <div className="ios-detail-hdr">
          <div className="ios-detail-hdr__cover"><ArtPlaceholder name={al.title + artist} size={150} radius={12} fill /></div>
          <div className="ios-detail-hdr__meta">
            <div className="ios-detail-hdr__title">{al.title}</div>
            <div className="ios-detail-hdr__artist" onClick={() => onArtist && onArtist(artist)}>{artist}</div>
            <div className="ios-detail-hdr__badges">
              <span className="ios-album-badge">{kindLabel}</span>
              <span className="ios-detail-hdr__year">{al.year || 2025}</span>
            </div>
          </div>
        </div>
        <div className="ios-detail-actions">
          <button className="ios-playall" onClick={() => onPlay({ ...tracks[0], resolver: rowResolvers[1] })}><PC_ICONS.play width="18" height="18" /> Play All</button>
          <button className="ios-detail-more" aria-label="More" onClick={() => onAlbumMenu && onAlbumMenu(al)}><PC_ICONS.more width="22" height="22" /></button>
        </div>
        <div>
          {tracks.map((t, i) => (
            <div key={i} className="ios-trk" onClick={() => onPlay({ ...t, resolver: rowResolvers[1] })}
              onContextMenu={(e) => { e.preventDefault(); onLong && onLong(t); }}>
              <div className="ios-trk__n">{t.n}</div>
              <ArtPlaceholder name={al.title + artist} size={48} radius={6} />
              <div className="ios-trk__meta">
                <div className="ios-trk__title">{t.title}</div>
                <div className="ios-trk__artist">{t.artist}</div>
              </div>
              <div className="ios-trk__dur">{t.dur}</div>
              <div className="pc-rsqs">{rowResolvers.map(r => <ResolverSquare key={r} kind={r} />)}</div>
            </div>
          ))}
        </div>
        <div className="ios-detail-footer">Source: musicbrainz, {resolvers.join(', ')}</div>
      </div>
    </>
  );
}

// ─── Friend detail — centered profile + listening activity ──────────────
function FriendScreen({ friend, onClose, onPlay, onLong, onQueue, onArtist, onOpenAlbum }) {
  const f = friend || PC_DATA.FRIENDS[0];
  const handle = '@' + f.name.toLowerCase().replace(/[^a-z0-9]/g, '');
  const source = f.source === 'lb' ? 'ListenBrainz' : 'Last.fm';
  const live = !!f.now;
  const avBg = f.color && f.color.includes('gradient') ? f.color : `linear-gradient(135deg, ${f.color || '#7c3aed'}, #4c1d95)`;
  return (
    <>
      <PushBar onClose={onClose} title={f.name} onMore={false} />
      <button className="ios-pushbar__more" style={{ position: 'absolute', top: 54, right: 10, zIndex: 7 }} aria-label="Pin">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="var(--accent-primary)"><path d="M14 2l8 8-5 1-3 3-1 6-3-3-5 5 5-5-3-3 6-1 3-3z"/></svg>
      </button>
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <div className="ios-fdetail">
          <div className="ios-fdetail__avatar" style={{ background: avBg }}>{f.initial}</div>
          {live && <div className="ios-fdetail__onair"><span className="dot" /> ON AIR</div>}
          <div className="ios-fdetail__handle">{handle}</div>
          <div className="ios-fdetail__src">Listening activity from {source}</div>
        </div>

        {live && (() => {
          const nowParts = f.now.split(' · ');
          return (
            <div className="pc-row" style={{ padding: '4px 20px 10px' }} onClick={() => onPlay({ title: nowParts[0], artist: nowParts[1] || '', resolver: 'spotify' })}>
              <div style={{ position: 'relative' }}>
                <ArtPlaceholder name={f.now} size={48} radius={8} />
                <span style={{ position: 'absolute', right: -3, bottom: -3, width: 15, height: 15, borderRadius: 8, background: 'var(--success)', border: '2px solid var(--bg-primary)' }} />
              </div>
              <div className="pc-row__meta" style={{ marginLeft: 4 }}>
                <div className="pc-row__title">{nowParts[0]}</div>
                <div className="pc-row__artist">{nowParts[1] || ''}</div>
              </div>
              <ResolverChip kind="spotify" />
            </div>
          );
        })()}

        <ListeningActivity order={['Recent', 'Top Songs', 'Top Albums', 'Top Artists']}
          onPlay={onPlay} onLong={onLong} onArtist={onArtist} onOpenAlbum={onOpenAlbum} />
      </div>
    </>
  );
}

// ─── Friends list ───────────────────────────────────────────────────────
function FriendsScreen({ onClose, onOpenFriend }) {
  const lb = [
    { name: 'vonsnack', initial: 'VO', color: 'linear-gradient(135deg,#5c4033,#2d1f1a)', last: 'Crass Shadows (at Walden Pawn) · Ryan …' },
    { name: 'frankmorello', initial: 'FR', color: 'linear-gradient(135deg,#16a34a,#166534)', last: 'A New Way of Living · The Early Years · 2…' },
    { name: 'phredspin', initial: 'PH', color: 'linear-gradient(135deg,#3d4a64,#1a253c)', last: 'Bada Bing · DANGERDOOM, MF DOOM, D…' },
    { name: 'drfeelgood', initial: 'DR', color: 'linear-gradient(135deg,#6d5bf0,#4c1d95)', last: 'B.A.S. (feat. Kyle Richh) · Megan Thee Sta…' },
    { name: 'cxy', initial: 'CX', color: 'linear-gradient(135deg,#5c2a2a,#2d1414)', last: 'Round And Around · Jaki Graham · Yeste…' },
    { name: 'kutx', initial: 'KU', color: 'linear-gradient(135deg,#222,#000)', now: 'Multiphase · Bayonne', online: true, pinned: true },
    { name: 'jukevox', initial: 'JU', color: 'linear-gradient(135deg,#1b4332,#2d6a4f)', last: 'rHegoz · Mouse on Mars · 5h ago' },
    { name: 'jbwharris', initial: 'JB', color: 'linear-gradient(135deg,#1a1a2e,#0f3460)', last: 'Angelcover · The New Pornographers · 2…' },
    { name: 'rob', initial: 'RO', color: 'linear-gradient(135deg,#3e2723,#5d4037)', last: 'Some Resolve · Röyksopp · 15w ago' },
  ];
  const lastfm = [
    { name: 'JoJO', initial: 'JO' }, { name: 'zprocket', initial: 'ZP' }, { name: 'rocketsurgeonX', initial: 'RS' },
    { name: 'Art Spivy', initial: 'AS' }, { name: 'cmonarsenal', initial: 'CM' }, { name: 'Tom O', initial: 'TO' },
    { name: 'Fred WIlson', initial: 'FW' }, { name: 'dcicero8', initial: 'DC' }, { name: 'bankoff', initial: 'BA' },
    { name: 'Nancy', initial: 'NA' }, { name: 'Jordan', initial: 'JO' }, { name: 'Mark Collier', initial: 'MC' },
    { name: 'Paul Lamere', initial: 'PL' },
  ];
  const palette = ['linear-gradient(135deg,#3d4a64,#1a253c)', 'linear-gradient(135deg,#1b4332,#2d6a4f)', 'linear-gradient(135deg,#5c2a2a,#2d1414)', 'linear-gradient(135deg,#3b1e54,#5f3a8c)', 'linear-gradient(135deg,#3e2723,#5d4037)'];

  const Row = ({ f, badge }) => (
    <div className="ios-frow" onClick={() => onOpenFriend && onOpenFriend({ name: f.name, initial: f.initial, color: f.color || palette[f.name.length % palette.length], dot: f.online, now: f.now, source: badge === 'LB' ? 'lb' : 'lastfm' })}>
      <div className="ios-frow__av" style={{ background: f.color || palette[f.name.length % palette.length] }}>
        {f.initial}
        {f.online && <span className="ios-frow__online" />}
      </div>
      <div className="ios-frow__meta">
        <div className="ios-frow__name">{f.name} <span className={`ios-frow__badge ${badge === 'LB' ? 'lb' : 'lfm'}`}>{badge}</span></div>
        {f.now
          ? <div className="ios-frow__chip"><span className="npd" /><span className="t">♪ {f.now}</span></div>
          : <div className="ios-frow__last">{f.last}</div>}
      </div>
      <button className={`ios-frow__pin ${f.pinned ? 'on' : ''}`} onClick={(e) => e.stopPropagation()} aria-label="Pin">
        <svg width="18" height="18" viewBox="0 0 24 24" fill={f.pinned ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.6"><path d="M14 2l8 8-5 1-3 3-1 6-3-3-5 5 5-5-3-3 6-1 3-3z"/></svg>
      </button>
    </div>
  );

  return (
    <>
      <PushBar onClose={onClose} title="Friends" onMore={false} />
      <button className="ios-pushbar__more" style={{ position: 'absolute', top: 54, right: 10, zIndex: 7 }} aria-label="Add friend">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="8" r="4"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M18 8v6M21 11h-6"/></svg>
      </button>
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        {lb.map((f, i) => <Row key={i} f={f} badge="LB" />)}
        {lastfm.map((f, i) => <Row key={i} f={f} badge="Last.fm" />)}
      </div>
    </>
  );
}

// ─── Weekly playlist (ListenBrainz auto-playlist) ───────────────────────
function WeeklyPlaylistScreen({ playlist, onClose, onPlay, onLong, onQueue, onOpenAlbum }) {
  const p = playlist || {};
  const isExpl = /Exploration/i.test(p.title || '');
  const label = isExpl ? 'Weekly Exploration' : 'Weekly Jams';
  const week = (p.title || '').split('·').pop().trim() || 'This Week';
  const tracks = [
    { title: 'Chelsea Dagger', artist: 'The Fratellis' }, { title: 'One of the Greats', artist: 'Florence + the Machine' },
    { title: "SEEIN' STARS", artist: 'Turnstile' }, { title: 'Harvest Moon', artist: 'Neil Young' },
    { title: 'Perfect Hand', artist: 'This Is Lorelei' }, { title: 'Roman Holiday', artist: 'Fontaines D.C.' },
    { title: 'Bitter Sweet Symphony', artist: 'The Verve' }, { title: 'Gold Guns Girls', artist: 'Metric' },
    { title: 'The Underdog', artist: 'Spoon' }, { title: 'Space Song', artist: 'Beach House' },
    { title: 'The Less I Know the Better', artist: 'Tame Impala' }, { title: 'Just Like Honey', artist: 'The Jesus and Mary Chain' },
    { title: 'Someday', artist: 'The Strokes' }, { title: 'California Stars', artist: 'Billy Bragg & Wilco' },
    { title: 'Francis Forever', artist: 'Mitski' }, { title: 'Need 2', artist: 'Pinegrove' },
  ];
  return (
    <>
      <PushBar onClose={onClose} title={`${week} — ${label}`} />
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <div className="ios-cdetail">
          <div className="ios-cdetail__cover">
            {(p.artists || ['a', 'b', 'c', 'd']).slice(0, 4).map((a, j) => <ArtPlaceholder key={j} name={a + j} size={75} radius={0} fill />)}
          </div>
          <div className="ios-cdetail__title">{label} for jherskowitz, week of 2026-06-01 Mon</div>
          <div className="ios-cdetail__by">ListenBrainz · 50 tracks · {week}</div>
          <div className="ios-weekly-blurb">The ListenBrainz {label} playlist features songs that you have listened to before, helping you reconnect with old favorites.</div>
        </div>
        <div className="ios-detail-actions center">
          <button className="ios-playall" onClick={() => onPlay({ ...tracks[0], resolver: 'spotify' })}><PC_ICONS.play width="18" height="18" /> Play All</button>
          <button className="ios-save-pill" aria-label="Save">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3v12M7 11l5 5 5-5M5 21h14"/></svg> Save
          </button>
        </div>
        <div>
          {tracks.map((t, i) => (
            <div key={i} className="ios-trk" onClick={() => onPlay({ ...t, resolver: 'spotify' })}>
              <div className="ios-trk__n">{i + 1}</div>
              <ArtPlaceholder name={t.title + t.artist} size={40} radius={5} />
              <div className="ios-trk__meta">
                <div className="ios-trk__title">{t.title}</div>
                <div className="ios-trk__artist">{t.artist}</div>
              </div>
              <div className="ios-trk__dur">0:00</div>
              <div className="pc-rsqs"><ResolverSquare kind="apple" /><ResolverSquare kind="spotify" /></div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Concerts — upcoming shows ──────────────────────────────────────────
const CONCERTS = [
  { artist: 'Quarantine Angst', venue: 'The Crocodile',      city: 'Seattle, WA',   mo: 'JUN', day: '14', miles: '2 mi', soon: true },
  { artist: 'Dogleg',           venue: 'Lincoln Hall',       city: 'Chicago, IL',   mo: 'JUN', day: '21', miles: '1,742 mi', soon: false },
  { artist: 'Born Ruffians',    venue: 'Great American',     city: 'San Francisco', mo: 'JUL', day: '02', miles: '680 mi', soon: false },
  { artist: 'Pearl Jam',        venue: 'Climate Pledge Arena', city: 'Seattle, WA', mo: 'JUL', day: '09', miles: '3 mi', soon: true },
  { artist: 'Car Seat Headrest', venue: 'Neumos',            city: 'Seattle, WA',   mo: 'AUG', day: '03', miles: '2 mi', soon: true },
  { artist: 'Jack White',       venue: 'The Showbox',        city: 'Seattle, WA',   mo: 'AUG', day: '17', miles: '2 mi', soon: false },
];

function ConcertsScreen({ onClose, onArtist }) {
  const [scope, setScope] = useStateM('Near You');
  const shown = scope === 'Near You' ? CONCERTS.filter(c => parseInt(c.miles.replace(/,/g, '')) < 50) : CONCERTS;
  return (
    <>
      <PushBar onClose={onClose} />
      <div className="pc-scroll" style={{ paddingTop: 0, paddingBottom: 200 }}>
        <div className="ios-cur__hero" style={{ background: 'linear-gradient(135deg, #0d9488, #10c9b4)' }}>
          <HeroStars />
          <h1>Concerts</h1>
          <div className="ios-cur__stats">{CONCERTS.length} Upcoming · Seattle, WA</div>
          <div className="ios-cur__sub">Shows from artists you follow</div>
        </div>
        <div className="ios-cur__chips">
          {['Near You', 'All Shows'].map(x => <div key={x} className={`ios-cur__chip ${scope === x ? 'active' : ''}`} onClick={() => setScope(x)}>{x}</div>)}
        </div>
        <div>
          {shown.map((c, i) => (
            <div key={i} className="ios-concert" onClick={() => onArtist && onArtist(c.artist)}>
              <div className="ios-concert__date">
                <span className="mo">{c.mo}</span>
                <span className="day">{c.day}</span>
              </div>
              <div className="ios-concert__meta">
                <div className="ios-concert__artist">{c.artist}{c.soon && <span className="ios-concert__tour" />}</div>
                <div className="ios-concert__venue">{c.venue} · {c.city}</div>
                <div className="ios-concert__miles">{c.miles} away</div>
              </div>
              <button className="ios-concert__btn" onClick={(e) => e.stopPropagation()}>Tickets</button>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Edit playlist ──────────────────────────────────────────────────────
function EditPlaylistScreen({ playlist, onClose, onDelete }) {
  const p = playlist || {};
  const [name, setName] = useStateM(p.title || 'Guitar Songs');
  const base = PC_DATA.QUEUE.concat(PC_DATA.QUEUE.slice(0, 4));
  const [rows, setRows] = useStateM(base.map((t, i) => ({ ...t, _id: i })));
  const remove = (id) => setRows(rs => rs.filter(r => r._id !== id));
  return (
    <>
      <div className="ios-pushbar">
        <button className="ios-pushbar__back" onClick={onClose} aria-label="Back"><PC_ICONS.chevronL width="22" height="22" /></button>
        <div className="ios-pushbar__title">Edit Playlist</div>
        <button className="ios-pushbar__done" onClick={onClose}>Done</button>
      </div>
      <div className="pc-scroll" style={{ paddingTop: 100, paddingBottom: 200 }}>
        <div className="ios-edit__label">Playlist Name</div>
        <input className="ios-edit__field" value={name} onChange={e => setName(e.target.value)} />
        <button className="ios-edit__delete" onClick={onClose}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6"/></svg>
          Delete Playlist
        </button>
        <div className="ios-edit__count">{rows.length} tracks</div>
        <div>
          {rows.map(t => (
            <div key={t._id} className="ios-edit__row">
              <span className="ios-edit__grip"><svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><circle cx="9" cy="7" r="1.5"/><circle cx="15" cy="7" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="9" cy="17" r="1.5"/><circle cx="15" cy="17" r="1.5"/></svg></span>
              <ArtPlaceholder name={t.title + t.artist} size={44} radius={5} />
              <div className="ios-edit__meta">
                <div className="ios-edit__t">{t.title}</div>
                <div className="ios-edit__a">{t.artist}</div>
              </div>
              <button className="ios-edit__x" onClick={() => remove(t._id)} aria-label="Remove">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
              </button>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

Object.assign(window, { CuratedListScreen, FreshDropsScreen, PopChartScreen, CriticalDarlingsScreen, HistoryScreen, SettingsScreen, AlbumDetailScreen, FriendScreen, FriendsScreen, WeeklyPlaylistScreen, EditPlaylistScreen, ConcertsScreen, ListeningActivity, CURATED });
