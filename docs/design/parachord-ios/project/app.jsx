// app.jsx — Parachord iOS prototype shell
const { useState: useStateA, useEffect: useEffectA, useRef: useRefA } = React;

const PUSH_W = 402;
const SPRING = 'transform 0.36s cubic-bezier(0.16,1,0.3,1)';

function ParachordIOSApp() {
  const [tweaks, setTweak] = useTweaks(window.PC_TWEAKS_DEFAULTS || { theme: 'light' });
  const dark = tweaks.theme === 'dark';

  const [tab, setTab] = useStateA('home');
  const [stack, setStack] = useStateA([]);        // pushed screens, top = last
  const [pushIn, setPushIn] = useStateA(false);   // top screen settled in
  const [sidebar, setSidebar] = useStateA(false);
  const [shuffle, setShuffle] = useStateA(false);
  const [addSheet, setAddSheet] = useStateA(false);
  const [npOpen, setNpOpen] = useStateA(false);
  const [queueOpen, setQueueOpen] = useStateA(false);
  const [ctxTrack, setCtxTrack] = useStateA(null);
  const [menu, setMenu] = useStateA(null);
  const [playing, setPlaying] = useStateA(true);
  const [track, setTrack] = useStateA({ title: 'Fox', artist: 'Dogleg', resolver: 'bandcamp' });

  const miniArtRef = useRefA(null);

  const onPlay = (t) => { setTrack(t); setPlaying(true); };
  const onQueue = () => setQueueOpen(true);

  // Navigation stack
  const push = (entry) => setStack(s => [...s, entry]);
  const onArtist = (artist) => push({ kind: 'artist', artist });
  const onOpenPlaylist = (playlist) => push({ kind: 'playlist', playlist });
  const onOpenList = (preset) => push({ kind: 'list', preset });
  const onOpenAlbum = (album) => push({ kind: 'album', album });
  const onOpenFriend = (friend) => push({ kind: 'friend', friend });
  const onOpenFriends = () => push({ kind: 'friends' });
  const onOpenWeekly = (playlist) => push({ kind: 'weekly', playlist });
  const onEditPlaylist = (playlist) => push({ kind: 'editplaylist', playlist });

  // ── Surface overflow menus (derived from app capabilities) ──
  const I = window.PC_ICONS;
  const albumMenu = (al) => ({
    title: al.title, subtitle: al.artist, artName: al.title + al.artist,
    items: [
      { label: 'Play Next', icon: I.queue },
      { label: 'Add to Queue', icon: I.add, onClick: onQueue },
      { label: 'Add to Playlist…', icon: I.list },
      { label: 'Go to Artist', icon: I.chevronR, onClick: () => onArtist(al.artist) },
      { label: 'Add to Collection', icon: I.inventory },
      { label: 'Share Album', icon: I.send },
    ],
  });
  const playlistMenu = (pl) => ({
    title: pl.title, subtitle: pl.creator ? `by ${pl.creator}` : 'Playlist', artName: pl.title,
    items: [
      { label: 'Edit Playlist', icon: I.list, onClick: () => onEditPlaylist(pl) },
      { label: 'Add to Queue', icon: I.add, onClick: onQueue },
      ...((pl.hosted || pl.source) ? [{ label: 'Pull (re-sync)', icon: I.globe }] : []),
      { label: 'Rename', icon: I.more },
      { label: 'Share', icon: I.send },
      { label: 'Delete Playlist', icon: I.close, destructive: true },
    ],
  });
  const artistMenu = (name) => ({
    title: name, subtitle: 'Artist', artName: name + ' artist', artRound: true,
    items: [
      { label: 'Follow', icon: I.add },
      { label: 'Start Artist Radio', icon: I.shuffle },
      { label: 'Add All to Queue', icon: I.queue, onClick: onQueue },
      { label: 'Share Artist', icon: I.send },
    ],
  });
  const nowPlayingMenu = () => ({
    title: track.title, subtitle: track.artist, artName: track.title,
    items: [
      { label: 'Add to Playlist…', icon: I.list },
      { label: 'Go to Album', icon: I.inventory },
      { label: 'Go to Artist', icon: I.chevronR, onClick: () => { setNpOpen(false); onArtist(track.artist); } },
      { label: 'Start Radio', icon: I.shuffle },
      { label: 'Sleep Timer', icon: I.history },
      { label: 'Share', icon: I.send },
    ],
  });
  const tabMenu = (which) => ({
    home:       { title: 'Home', items: [ { label: 'Refresh', icon: I.history }, { label: 'Settings', icon: I.settings, onClick: () => { resetStack(); push({ kind: 'settings' }); } } ] },
    search:     { title: 'Search', items: [ { label: 'Clear Recent Searches', icon: I.close, destructive: true } ] },
    collection: { title: 'Collection', items: [ { label: 'Sort By…', icon: I.list }, { label: 'Sync Now', icon: I.globe } ] },
    playlists:  { title: 'Playlists', items: [ { label: 'New Playlist', icon: I.add }, { label: 'Import Playlist', icon: I.globe } ] },
  }[which]);
  function pop() {
    setPushIn(false);
    setTimeout(() => setStack(s => s.slice(0, -1)), 360);
  }
  function resetStack() { setPushIn(false); setStack([]); }

  // Animate the top pushed screen in whenever the stack grows
  useEffectA(() => {
    if (stack.length) {
      const id = setTimeout(() => setPushIn(true), 20);
      return () => clearTimeout(id);
    }
  }, [stack.length]);

  const edge = useEdgeSwipe({ width: PUSH_W, onPop: pop, enabled: stack.length > 0 && pushIn });

  useEffectA(() => {
    document.documentElement.classList.toggle('dark', dark);
  }, [dark]);

  const depth = stack.length;
  const navOpen = depth > 0;
  const p = navOpen ? (pushIn ? Math.max(0, 1 - edge.dx / PUSH_W) : 0) : 0; // 1 = fully pushed in
  const pushTranslate = (1 - p) * PUSH_W;
  const baseTranslate = -22 * p;
  const dimOpacity = 0.2 * p;
  const noTrans = edge.dragging;

  const screenProps = { onPlay, onLong: setCtxTrack, onQueue, dark, onArtist, onOpenPlaylist, onOpenList, onOpenAlbum, onOpenFriend, onOpenFriends, onOpenWeekly, onEditPlaylist, onAlbumMenu: (al) => setMenu(albumMenu(al)), onPlaylistMenu: (pl) => setMenu(playlistMenu(pl)), onArtistMenu: (n) => setMenu(artistMenu(n)), onTabMenu: (w) => setMenu(tabMenu(w)), onTab: (id) => { resetStack(); setTab(id); }, onMenu: () => setSidebar(true) };

  const renderScreen = (entry) => {
    const common = { onClose: pop, ...screenProps };
    switch (entry.kind) {
      case 'artist':   return <ArtistScreen artist={entry.artist} {...common} />;
      case 'playlist': return <PlaylistDetailScreen playlist={entry.playlist} {...common} />;
      case 'album':    return <AlbumDetailScreen album={entry.album} {...common} />;
      case 'list':     return <CuratedListScreen preset={entry.preset} {...common} />;
      case 'history':  return <HistoryScreen {...common} />;
      case 'settings': return <SettingsScreen {...common} />;
      case 'friend':   return <FriendScreen friend={entry.friend} {...common} />;
      case 'friends':  return <FriendsScreen {...common} />;
      case 'weekly':   return <WeeklyPlaylistScreen playlist={entry.playlist} {...common} />;
      case 'editplaylist': return <EditPlaylistScreen playlist={entry.playlist} {...common} />;
      case 'concerts': return <ConcertsScreen {...common} />;
      default:         return null;
    }
  };

  const tabScreen = (() => {
    const props = { ...screenProps };
    if (tab === 'home')       return <HomeScreen {...props} />;
    if (tab === 'search')     return <SearchScreen {...props} />;
    if (tab === 'collection') return <CollectionScreen {...props} />;
    if (tab === 'playlists')  return <PlaylistsScreen {...props} />;
    return null;
  })();

  // Layer behind the top push: previous stack entry (parallax) or the tab screen
  const behindScreen = depth > 1 ? renderScreen(stack[depth - 2]) : tabScreen;
  const topScreen = depth > 0 ? renderScreen(stack[depth - 1]) : null;

  return (
    <div className="host" data-screen-label="Parachord iOS">
      <IOSDevice width={402} height={874} dark={dark}>
                {!npOpen && <DynamicIslandLive track={track} playing={playing} />}

        <div className="ios-stage">
          {shuffle ? (
            <ShuffleupagusScreen onMenu={() => setShuffle(false)} dark={dark} />
          ) : (
            <>
              <div className="nav-base" style={{ transform: `translateX(${baseTranslate}%)`, transition: noTrans ? 'none' : SPRING }}>
                {behindScreen}
              </div>
              {navOpen && <div className="nav-base__dim" style={{ opacity: dimOpacity }} />}
              {navOpen && (
                <div className="nav-push" style={{ transform: `translateX(${pushTranslate}px)`, transition: noTrans ? 'none' : SPRING }} {...edge.handlers}>
                  {topScreen}
                </div>
              )}
            </>
          )}
        </div>

        {/* Mini player + Tab bar — kept mounted for the shared-element morph */}
        {!shuffle && (
          <div style={{ opacity: npOpen ? 0 : 1, pointerEvents: npOpen ? 'none' : 'auto', transition: 'opacity 0.2s ease' }}>
            <MiniPlayer
              track={track}
              playing={playing}
              artRef={miniArtRef}
              onToggle={() => setPlaying(p => !p)}
              onExpand={() => setNpOpen(true)}
            />
            <TabBar
              active={navOpen ? null : tab}
              onChange={(id) => { resetStack(); setTab(id); }}
              onCenter={() => setAddSheet(true)}
            />
          </div>
        )}

        <NowPlaying
          open={npOpen}
          onClose={() => setNpOpen(false)}
          track={track}
          playing={playing}
          miniArtRef={miniArtRef}
          onToggle={() => setPlaying(p => !p)}
          onShowQueue={() => setQueueOpen(true)}
          onMore={() => setMenu(nowPlayingMenu())}
          queueLen={PC_DATA.QUEUE.length - 1}
        />
        <QueueSheet
          open={queueOpen}
          onClose={() => setQueueOpen(false)}
          queue={PC_DATA.QUEUE}
          onPick={(t) => { onPlay(t); setQueueOpen(false); }}
          currentTitle={track.title}
        />

        <Sidebar open={sidebar} onClose={() => setSidebar(false)} dark={dark}
          onOpenFriend={(f) => { resetStack(); onOpenFriend(f); }}
          onNav={(id) => {
          if (id === 'collection') { resetStack(); setTab('collection'); }
          else if (id === 'playlists') { resetStack(); setTab('playlists'); }
          else if (id === 'history') { resetStack(); push({ kind: 'history' }); }
          else if (id === 'settings') { resetStack(); push({ kind: 'settings' }); }
          else if (id === 'recommendations') { resetStack(); push({ kind: 'list', preset: 'recommendations' }); }
          else if (id === 'pop') { resetStack(); push({ kind: 'list', preset: 'pop' }); }
          else if (id === 'critical') { resetStack(); push({ kind: 'list', preset: 'critical' }); }
          else if (id === 'fresh') { resetStack(); push({ kind: 'list', preset: 'fresh' }); }
          else if (id === 'concerts') { resetStack(); push({ kind: 'concerts' }); }
          else { resetStack(); setTab('home'); }
        }} />

        <ContextMenu open={!!ctxTrack} onClose={() => setCtxTrack(null)} track={ctxTrack} />
        <ContextMenu open={!!menu} onClose={() => setMenu(null)} menu={menu} />

        <AddActionSheet
          open={addSheet}
          onClose={() => setAddSheet(false)}
          onShuffleupagus={() => setShuffle(true)}
          onAddFriend={() => onOpenFriends()}
        />
      </IOSDevice>

      <TweaksPanel>
        <TweakSection label="Theme">
          <TweakRadio
            label="Appearance"
            value={tweaks.theme}
            options={[{value:'light',label:'Light'},{value:'dark',label:'Dark'}]}
            onChange={v => setTweak('theme', v)}
          />
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

window.ParachordIOSApp = ParachordIOSApp;
