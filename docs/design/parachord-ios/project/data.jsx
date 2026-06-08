// data.jsx — Parachord iOS mock data + shared SVG icons + ArtPlaceholder
// Exports to window: PC_DATA, PC_ICONS, ArtPlaceholder, ResolverChip, HostedChip, hashStr

const ART_PALETTES = [
  ['#1a3a5c', '#0f2540'], ['#2c1f4a', '#1a1230'], ['#2d4a3a', '#1a2e22'],
  ['#5c2a2a', '#3a1a1a'], ['#3a2d4a', '#251c30'], ['#1a3a4a', '#0e2530'],
  ['#4a3a1a', '#2e2410'], ['#3a1a3a', '#241224'], ['#1a4a3a', '#0e2e22'],
  ['#4a1a2a', '#2e0e1a'], ['#1f3a2c', '#122418'], ['#3a2a4a', '#241a30'],
  ['#2c3a5c', '#1a2440'], ['#5c3a2a', '#40251a'], ['#4a3a3a', '#2e2424'],
];
const ART_PATTERNS = ['solid', 'circles', 'lines', 'grid', 'sweep', 'gradient'];

function hashStr(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

function ArtPlaceholder({ name = 'X', size = 100, radius = 8, fill = false }) {
  const h = hashStr(name);
  const [c1, c2] = ART_PALETTES[h % ART_PALETTES.length];
  const pat = ART_PATTERNS[(h >> 4) % ART_PATTERNS.length];
  const initial = name.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase() || 'P';
  const dim = fill ? '100%' : size;
  return (
    <div style={{
      width: dim, height: dim, borderRadius: radius, overflow: 'hidden',
      background: `linear-gradient(135deg, ${c1}, ${c2})`,
      position: 'relative',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: 'rgba(255,255,255,0.85)', fontWeight: 700,
      fontSize: size * 0.34, fontFamily: 'inherit', letterSpacing: '-0.02em',
    }}>
      {pat === 'circles' && (
        <svg style={{position:'absolute', inset:0, opacity:0.18}} viewBox="0 0 100 100" preserveAspectRatio="none">
          <circle cx="20" cy="80" r="35" fill="#fff"/>
          <circle cx="80" cy="20" r="25" fill="#fff"/>
        </svg>
      )}
      {pat === 'lines' && (
        <svg style={{position:'absolute', inset:0, opacity:0.18}} viewBox="0 0 100 100" preserveAspectRatio="none">
          {[0,1,2,3,4,5].map(i => <line key={i} x1={i*20} y1="0" x2={i*20+30} y2="100" stroke="#fff" strokeWidth="2"/>)}
        </svg>
      )}
      {pat === 'grid' && (
        <svg style={{position:'absolute', inset:0, opacity:0.16}} viewBox="0 0 100 100" preserveAspectRatio="none">
          {[1,2,3,4].map(i => <line key={'h'+i} x1="0" y1={i*20} x2="100" y2={i*20} stroke="#fff" strokeWidth="1"/>)}
          {[1,2,3,4].map(i => <line key={'v'+i} x1={i*20} y1="0" x2={i*20} y2="100" stroke="#fff" strokeWidth="1"/>)}
        </svg>
      )}
      {pat === 'sweep' && (
        <div style={{position:'absolute',inset:0,background:`conic-gradient(from ${(h%360)}deg, ${c2}, ${c1}, ${c2})`,opacity:0.55}}/>
      )}
      {pat === 'gradient' && (
        <div style={{position:'absolute',inset:0,background:`radial-gradient(circle at 30% 30%, ${c1}aa, transparent 70%)`,opacity:0.7}}/>
      )}
      <span style={{position:'relative', zIndex:1}}>{initial}</span>
    </div>
  );
}

// ─── Resolver chip ───
const RESOLVERS = {
  spotify:    { name: 'Spotify',     bg: 'var(--r-spotify-bg)',    fg: 'var(--r-spotify-fg)',    initial: 'S',  full: '#1DB954' },
  apple:      { name: 'Apple Music', bg: 'var(--r-applemusic-bg)', fg: 'var(--r-applemusic-fg)', initial: 'A',  full: '#FA243C' },
  bandcamp:   { name: 'Bandcamp',    bg: 'var(--r-bandcamp-bg)',   fg: 'var(--r-bandcamp-fg)',   initial: 'B',  full: '#1da0c3' },
  soundcloud: { name: 'SoundCloud',  bg: 'var(--r-soundcloud-bg)', fg: 'var(--r-soundcloud-fg)', initial: 'SC', full: '#ff5500' },
  youtube:    { name: 'YouTube',     bg: 'var(--r-youtube-bg)',    fg: 'var(--r-youtube-fg)',    initial: 'Y',  full: '#FF0000' },
  local:      { name: 'Local Files', bg: 'var(--r-localfiles-bg)', fg: 'var(--r-localfiles-fg)', initial: 'L',  full: '#9ca3af' },
};
function ResolverChip({ kind = 'spotify' }) {
  const r = RESOLVERS[kind];
  return <span className="pc-resolver" style={{ background: r.bg, color: r.fg }}>{r.initial}</span>;
}

// Brand-glyph resolver square (trust signal on track rows)
const RESOLVER_GLYPHS = {
  spotify:    { bg: '#1DB954', glyph: <svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm4.6 14.4a.62.62 0 01-.86.21c-2.35-1.44-5.3-1.76-8.79-.96a.62.62 0 11-.28-1.22c3.82-.87 7.09-.5 9.72 1.11a.62.62 0 01.21.86zm1.23-2.74a.78.78 0 01-1.07.26c-2.69-1.66-6.8-2.14-9.98-1.17a.78.78 0 11-.45-1.49c3.64-1.1 8.17-.57 11.25 1.33a.78.78 0 01.25 1.07zm.11-2.85C14.83 8.96 9.4 8.79 6.3 9.73a.93.93 0 11-.54-1.78c3.56-1.08 9.56-.87 13.34 1.37a.93.93 0 11-.95 1.6z"/></svg> },
  apple:      { bg: '#FA243C', glyph: <svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M16 4l-7 1.5v9.1a3 3 0 10.9 2.15V9l5.1-1.1v4.7a3 3 0 10.9 2.15V4z"/></svg> },
  bandcamp:   { bg: '#1da0c3', glyph: <svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M3 16l5-8h13l-5 8z"/></svg> },
  soundcloud: { bg: '#ff5500', glyph: <svg viewBox="0 0 24 24" width="14" height="14" fill="#fff"><path d="M4 14v3M7 12v5M10 10v7M13 9v8h5a3 3 0 000-6c-.3 0-.6 0-.9.1A4 4 0 0013 9z"/></svg> },
  youtube:    { bg: '#FF0000', glyph: <svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M9 8l7 4-7 4z"/></svg> },
  local:      { bg: '#6c5ce7', glyph: <svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M9 18V6l10-2v12"/><circle cx="6" cy="18" r="2.5"/><circle cx="16" cy="16" r="2.5"/></svg> },
};
function ResolverSquare({ kind = 'spotify' }) {
  const r = RESOLVER_GLYPHS[kind] || RESOLVER_GLYPHS.spotify;
  return <span className="pc-rsq" style={{ background: r.bg }}>{r.glyph}</span>;
}
function HostedChip() {
  return <span className="hosted-chip"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18"/></svg> Hosted</span>;
}
const SOURCE_LABELS = {
  spotify: { name: 'Spotify',     cls: 'spotify' },
  apple:   { name: 'Apple Music', cls: 'apple' },
  bandcamp:{ name: 'Bandcamp',    cls: 'bandcamp' },
  soundcloud:{ name: 'SoundCloud', cls: 'soundcloud' },
};
function SourceLabel({ kind }) {
  const s = SOURCE_LABELS[kind];
  if (!s) return null;
  return <span className={`src-label ${s.cls}`}>{s.name}</span>;
}

// ─── Mock data ─────────────────────────────────────────────────────────
const ARTISTS = {
  'Quarantine Angst': { genre: 'Indie · Post-punk', monthly: '124K', resolvers: ['spotify', 'bandcamp'] },
  'Born Ruffians':    { genre: 'Indie rock',         monthly: '89K',  resolvers: ['spotify', 'apple'] },
  'Pearl Jam':        { genre: 'Rock',               monthly: '5.4M', resolvers: ['spotify', 'apple'] },
  'Jack White':       { genre: 'Garage rock',        monthly: '3.1M', resolvers: ['spotify', 'apple'] },
  'Smashing Pumpkins':{ genre: 'Alt-rock',           monthly: '4.8M', resolvers: ['spotify', 'apple'] },
  'Car Seat Headrest':{ genre: 'Indie rock',         monthly: '1.2M', resolvers: ['spotify', 'bandcamp'] },
  'Fu Kaisha':        { genre: 'Hyperpop',           monthly: '34K',  resolvers: ['soundcloud'] },
  'Dogleg':           { genre: 'Emo · Punk',         monthly: '180K', resolvers: ['spotify', 'bandcamp'] },
  'Nikki Lane':       { genre: 'Country',            monthly: '420K', resolvers: ['spotify', 'apple'] },
  'Dangermuffin':     { genre: 'Folk-rock',          monthly: '52K',  resolvers: ['spotify'] },
};

const RECENTLY_ADDED = [
  { title: 'Post-Pardon',                 artist: 'Quarantine Angst',  year: 2026, tracks: 13 },
  { title: "Curly's Town",                artist: 'Quarantine Angst',  year: 2025, tracks: 1 },
  { title: 'Locomotion vs. Lack of Mot…', artist: 'Quarantine Angst',  year: 2025, tracks: 1 },
  { title: 'Athena',                      artist: 'Born Ruffians',     year: 2025, tracks: 5 },
  { title: 'The Scholars',                artist: 'Car Seat Headrest', year: 2025, tracks: 9 },
  { title: 'Ox Bone',                     artist: 'The Sleeping Cliffs', year: 2025, tracks: 3 },
  { title: 'Aghori Mhori Mei',            artist: 'Smashing Pumpkins', year: 2024, tracks: 10 },
  { title: 'No Name',                     artist: 'Jack White',        year: 2024, tracks: 13 },
];

const COLLECTION_ALBUMS = [
  ...RECENTLY_ADDED,
  { title: 'Myth of the Lone Genius',     artist: 'Quarantine Angst',  year: 2024, tracks: 1 },
  { title: 'Dark Matter',                 artist: 'Pearl Jam',         year: 2024, tracks: 1 },
  { title: 'Bueno',                       artist: 'Dogleg',            year: 2024, tracks: 12 },
  { title: 'Silver Ghost',                artist: 'Quarantine Angst',  year: 2024, tracks: 1 },
  { title: 'Quiet Storm',                 artist: 'Quarantine Angst',  year: 2024, tracks: 8 },
  { title: 'Epidural',                    artist: 'Quarantine Angst',  year: 2023, tracks: 1 },
  { title: 'Translucency',                artist: 'Quarantine Angst',  year: 2023, tracks: 11 },
];

const QA_DISCO = [
  { title: 'Post-Pardon',                  year: 2026, type: 'ALBUM' },
  { title: 'The Repression of Banditry',   year: 2025, type: 'SINGLE' },
  { title: 'Rat Handed',                   year: 2025, type: 'SINGLE' },
  { title: 'Deliberate',                   year: 2025, type: 'SINGLE' },
  { title: 'Understanding the Assignment', year: 2025, type: 'SINGLE' },
  { title: "Curly's Town",                 year: 2025, type: 'SINGLE' },
  { title: 'Locomotion vs. Lack of Mot…',  year: 2025, type: 'SINGLE' },
  { title: 'Aces Over Devices',            year: 2025, type: 'SINGLE' },
  { title: 'Saving Songs for Sunday',      year: 2025, type: 'SINGLE' },
  { title: 'Western Sky',                  year: 2024, type: 'SINGLE' },
];

const QUEUE = [
  { n: 1, title: 'Fox',                    artist: 'Dogleg',           dur: '2:50', resolver: 'bandcamp' },
  { n: 2, title: 'Western Sky',            artist: 'Quarantine Angst', dur: '4:12', resolver: 'spotify' },
  { n: 3, title: 'Trampled by Turtles',    artist: 'Send the Sun',     dur: '3:33', resolver: 'spotify' },
  { n: 4, title: '1080p2020',              artist: 'Fu Kaisha',        dur: '2:18', resolver: 'soundcloud' },
  { n: 5, title: 'Ox Bone',                artist: 'The Sleeping Cliffs', dur: '5:01', resolver: 'bandcamp' },
  { n: 6, title: 'Athena',                 artist: 'Born Ruffians',    dur: '3:48', resolver: 'apple' },
  { n: 7, title: 'Rat Handed',             artist: 'Quarantine Angst', dur: '3:20', resolver: 'spotify' },
  { n: 8, title: 'No Name (Track 7)',      artist: 'Jack White',       dur: '3:09', resolver: 'apple' },
];

const PLAYLISTS = [
  { title: 'Radio Paradise Rewind', tracks: 1555, creator: null,           source: 'apple',   hosted: false, artists: ['Radio Paradise', 'KCRW', 'NTS', 'WFUV'] },
  { title: 'Daily Brew',           tracks: 40,  creator: 'J Herskowitz',   source: 'spotify', hosted: false, artists: ['Quarantine Angst', 'Dogleg', 'Pearl Jam', 'Jack White'] },
  { title: 'KCRW Rewind',          tracks: 62,  creator: 'J Herskowitz',   source: 'spotify', hosted: false, artists: ['Car Seat Headrest', 'Born Ruffians', 'Fu Kaisha', 'Nikki Lane'] },
  { title: 'KEXP Rewind',          tracks: 295, creator: 'J Herskowitz',   source: 'spotify', hosted: false, artists: ['Smashing Pumpkins', 'Pearl Jam', 'Dogleg', 'Jack White'] },
  { title: 'SomaFM Groove Salad Rewind', tracks: 15, creator: 'J Herskowitz', source: 'spotify', hosted: false, artists: ['Quarantine Angst', 'Fu Kaisha', 'Born Ruffians', 'Dangermuffin'] },
  { title: 'SiriusXMU Rewind',     tracks: 288, creator: null,             source: 'spotify', hosted: true,  artists: ['Pearl Jam', 'Jack White', 'Car Seat Headrest', 'Dogleg'] },
  { title: 'NTS Radio Rewind',     tracks: 218, creator: null,             source: 'apple',   hosted: false, artists: ['Fu Kaisha', 'Nikki Lane', 'Quarantine Angst', 'Born Ruffians'] },
  { title: "Huff'n Duster with Allen", tracks: 177, creator: 'Nicholas Banning Romer', source: 'spotify', hosted: false, artists: ['Jack White', 'Dogleg', 'Pearl Jam', 'Smashing Pumpkins'] },
  { title: 'New music Friday',     tracks: 69,  creator: 'Deer Indie',     source: 'spotify', hosted: false, artists: ['Quarantine Angst', 'Dogleg', 'Fu Kaisha', 'Born Ruffians'] },
  { title: 'WFUV Rewind',          tracks: 14,  creator: null,             source: null,      hosted: false, artists: ['Nikki Lane', 'Pearl Jam', 'Car Seat Headrest', 'Jack White'] },
  { title: 'WFMU Rewind',          tracks: 190, creator: null,             source: null,      hosted: false, artists: ['Dogleg', 'Quarantine Angst', 'Fu Kaisha', 'Born Ruffians'] },
  { title: 'New Indie Weekly',     tracks: 36,  creator: 'Western Jaguar', source: 'spotify', hosted: false, artists: ['Car Seat Headrest', 'Dogleg', 'Quarantine Angst', 'Born Ruffians'] },
];

const FRIENDS = [
  { name: 'Fred McIntyre', initial: 'F', dot: true,  now: 'Send the Sun · Nikki Lane',     color: 'linear-gradient(135deg,#3d4a64,#1a253c)' },
  { name: 'Dan Kantor',    initial: 'D', dot: false, now: null,                              color: 'linear-gradient(135deg,#5c4033,#2d1f1a)' },
  { name: 'mkb',           initial: 'M', dot: true,  now: '1080p2020 · Fu Kaisha',           color: 'linear-gradient(135deg,#a855f7,#7c3aed)' },
  { name: 'rob',           initial: 'R', dot: false, now: null,                              color: 'linear-gradient(135deg,#a855f7,#6d28d9)' },
  { name: 'Richard Jones', initial: 'R', dot: false, now: null,                              color: 'linear-gradient(135deg,#eab308,#a16207)' },
  { name: 'James',         initial: 'J', dot: false, now: null,                              color: 'linear-gradient(135deg,#16a34a,#166534)' },
];

// Tracks list for an album/artist
const TOP_TRACKS = [
  { n: 1, title: 'Rat Handed',                       dur: '3:20',  resolvers: ['spotify','bandcamp'] },
  { n: 2, title: 'Western Sky',                      dur: '4:12',  resolvers: ['spotify'] },
  { n: 3, title: 'Saving Songs for Sunday',          dur: '3:48',  resolvers: ['spotify','bandcamp'] },
  { n: 4, title: 'Aces Over Devices',                dur: '4:33',  resolvers: ['spotify'] },
  { n: 5, title: 'The Repression of Banditry',      dur: '5:14',  resolvers: ['bandcamp'] },
  { n: 6, title: 'Deliberate',                       dur: '3:02',  resolvers: ['spotify','bandcamp'] },
];

// ─── Icons (Lucide-flavored stroke) ────────────────────────────────────
const PC_ICONS = {
  home: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12L12 4l9 8"/><path d="M5 10v10h14V10"/></svg>,
  search: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>,
  inventory: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>,
  list: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>,
  history: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>,
  star: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><polygon points="12 2 15 9 22 10 17 15 18 22 12 18 6 22 7 15 2 10 9 9 12 2"/></svg>,
  trending: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 17l6-6 4 4 8-8"/><path d="M17 7h4v4"/></svg>,
  trophy: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 4h8v6a4 4 0 01-8 0V4z"/><path d="M4 4h4v3a3 3 0 01-3-3z"/><path d="M16 4h4a3 3 0 01-3 3"/><path d="M9 17h6"/><path d="M12 13v4"/><path d="M9 20h6"/></svg>,
  settings: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.34 1.86l.06.06a2 2 0 11-2.82 2.83l-.06-.06A1.7 1.7 0 0015 19.4a1.7 1.7 0 00-1 1.55V21a2 2 0 11-4 0v-.09A1.7 1.7 0 008.94 19.4a1.7 1.7 0 00-1.86.33l-.06.07a2 2 0 11-2.83-2.83l.06-.06A1.7 1.7 0 004.6 15a1.7 1.7 0 00-1.55-1H3a2 2 0 110-4h.09A1.7 1.7 0 004.6 8.94a1.7 1.7 0 00-.33-1.86l-.07-.06a2 2 0 112.83-2.83l.06.06A1.7 1.7 0 009 4.6a1.7 1.7 0 001-1.55V3a2 2 0 114 0v.09c0 .67.4 1.27 1 1.51a1.7 1.7 0 001.86-.33l.06-.06a2 2 0 112.83 2.83l-.06.06A1.7 1.7 0 0019.4 9c.24.6.84 1 1.51 1H21a2 2 0 110 4h-.09c-.67 0-1.27.4-1.51 1z"/></svg>,
  menu: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18M3 12h18M3 18h18"/></svg>,
  more: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><circle cx="6" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="18" cy="12" r="2"/></svg>,
  play: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M7 5v14l11-7z"/></svg>,
  pause: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>,
  prev: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zM10 12l10 6V6z"/></svg>,
  next: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M16 6h2v12h-2zM4 18l10-6L4 6z"/></svg>,
  heart: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M12 21s-7-4.3-9.5-9.2C0.6 7.5 3.3 4 6.6 4c1.9 0 3.6 1 4.4 2.5C12.8 5 14.5 4 16.4 4c3.3 0 6 3.5 4.1 7.8C19 16.7 12 21 12 21z"/></svg>,
  heartO: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M12 21s-7-4.3-9.5-9.2C0.6 7.5 3.3 4 6.6 4c1.9 0 3.6 1 4.4 2.5C12.8 5 14.5 4 16.4 4c3.3 0 6 3.5 4.1 7.8C19 16.7 12 21 12 21z"/></svg>,
  shuffle: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M16 3h5v5"/><path d="M4 20L21 3"/><path d="M21 16v5h-5"/><path d="M15 15l6 6"/><path d="M4 4l5 5"/></svg>,
  repeat: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 014-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 01-4 4H3"/></svg>,
  queue: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h13M3 12h13M3 18h9M17 16v6M14 19h6"/></svg>,
  add: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/></svg>,
  send: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="currentColor"><path d="M3 3l18 9-18 9V14l13-2L3 10z"/></svg>,
  globe: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18"/></svg>,
  chevronR: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 5l7 7-7 7"/></svg>,
  chevronL: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 5l-7 7 7 7"/></svg>,
  chevronD: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 9l7 7 7-7"/></svg>,
  close: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M6 6l12 12M18 6L6 18"/></svg>,
  cast: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="6" width="20" height="13" rx="2"/><path d="M2 11a8 8 0 018 8M2 16a3 3 0 013 3"/></svg>,
  // Shuffleupagus mammoth — recreated from ParachordIcons.kt
  mammoth: (props={}) => (
    <svg {...props} viewBox="0 0 24 24" fill="currentColor">
      <path d="M5 14c0-3 2-5 5-5h3c2.2 0 4 1.4 4.5 3.4l1.3.5c.6.2 1.1.6 1.2 1.3l-.3.7-1.4.3v3l-1 .3-.6 2.5h-1.6l-.5-2v-1l-2 .5-.4 2.5h-1.5l-.5-2.6c-.7.1-1.3 0-2-.2L8 19h-1.5l-.5-3.2C5.4 15.2 5 14.6 5 14z"/>
      <circle cx="9.2" cy="11.5" r="0.7" fill="#fff"/>
      <path d="M11.5 9C11 8 11 6.5 12 5.5c.6-.5 1.5-.4 2 .2-.5.6-.8 1.4-.5 2.3" fill="none" stroke="currentColor" strokeWidth="0.8"/>
    </svg>
  ),
  spinoff: (props={}) => <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="11" r="2"/><path d="M6 7v10"/><path d="M6 12c0-3 4-3 6-3 3 0 4-2 4-2"/></svg>,
};

// Export to window
Object.assign(window, {
  PC_DATA: { RECENTLY_ADDED, COLLECTION_ALBUMS, QA_DISCO, QUEUE, PLAYLISTS, FRIENDS, ARTISTS, RESOLVERS, TOP_TRACKS },
  PC_ICONS, ArtPlaceholder, ResolverChip, ResolverSquare, HostedChip, SourceLabel, hashStr,
});
