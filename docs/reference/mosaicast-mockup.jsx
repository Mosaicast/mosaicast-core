import { useState, useEffect, useRef } from "react";
import {
  Play, Pause, Lock, Sun, Moon, ChevronLeft, ChevronDown, Menu,
  SkipBack, SkipForward, Volume2, Check, Layers, Trophy, Clock, Coffee, Mic
} from "lucide-react";

/* ============================================================
   Mosaicast – layout mockup
   Demonstrates: top-bar chrome, persistent player, feed of
   wide cards with a card slot, detail page with main/sidebar,
   semantic theme tokens (light/dark), accent-from-seed,
   and a "show slots" overlay revealing the plugin regions.
   All fake data – for layout alignment only.
   ============================================================ */

const COVER_PALETTE = ["#C8553D", "#E0913A", "#2E6E6A", "#FDF6EE", "#A8412F", "#EBC88A"];

const ACCENTS = [
  { id: "logo", label: "Auto (Logo)", accent: "#C8553D", contrast: "#FFF8F2" },
  { id: "teal", label: "Teal", accent: "#2E6E6A", contrast: "#F2FBFA" },
  { id: "indigo", label: "Indigo", accent: "#5457E6", contrast: "#F4F4FF" },
  { id: "rose", label: "Rose", accent: "#C13B6B", contrast: "#FFF4F8" },
];

const EPISODES = [
  {
    id: 8, season: 2, ep: 13, title: "Coming soon: the big year-in-review (too early)",
    date: "Sat, Jun 28", dur: 0, locked: false, upcoming: true,
    desc: "The episode isn't out yet – but our bingos are already open. Make your predictions before it drops.",
    alex: 50, plays: "—", bingoHits: 0,
  },
  {
    id: 7, season: 2, ep: 12, title: "Why pigeons secretly hate us",
    date: "Jun 21, 2026", dur: 4122, locked: false,
    desc: "Alex has a theory, Jonas has doubts, and somewhere in between it's about city pigeons, homing pigeons and an incident on a balcony in Cologne.",
    alex: 54, plays: "12,4k", bingoHits: 3,
  },
  {
    id: 6, season: 2, ep: 11, title: "The great coffee controversy",
    date: "Jun 14, 2026", dur: 3890, locked: false,
    desc: "Filter vs. espresso machine. It gets more heated than expected.",
    alex: 61, plays: "11,9k", bingoHits: 5,
  },
  {
    id: 5, season: 2, ep: 10, title: "Nobody understands insurance, us included",
    date: "Jun 7, 2026", dur: 4360, locked: false,
    desc: "An honest attempt to explain home contents insurance. Failed.",
    alex: 49, plays: "10,2k", bingoHits: 2,
  },
  {
    id: 99, season: null, ep: null, title: "Bonus: reading Wikipedia drunk",
    date: "Jun 1, 2026", dur: 2980, locked: true, tier: "Hot-Beverage tier",
    desc: "Exclusive for supporters. Connect your Patreon to listen.",
    alex: 52, plays: "—", bingoHits: 0,
  },
  {
    id: 4, season: 2, ep: 9, title: "Alex once had a plan",
    date: "May 24, 2026", dur: 3710, locked: false,
    desc: "Spoiler: the plan doesn't survive the first fifteen minutes.",
    alex: 58, plays: "9,8k", bingoHits: 4,
  },
  {
    id: 3, season: 2, ep: 8, title: "The microphone disaster",
    date: "May 17, 2026", dur: 3540, locked: false,
    desc: "Half the episode was recorded with no audio. We talk about it anyway.",
    alex: 47, plays: "9,1k", bingoHits: 6,
  },
];

const ALEX_CARD = [
  { t: "Jonas defends his coffee machine", hit: true },
  { t: "tangent about pigeons", hit: true },
  { t: "someone forgets the mic", hit: false },
  { t: ""we'll google that later"", hit: true },
  { t: "MITTE", free: true },
  { t: "Jonas laughs too loud", hit: false },
  { t: "listener mail read out", hit: true },
  { t: "silence > 5 seconds", hit: false },
  { t: "pun escalation", hit: true },
];

const JONAS_CARD = [
  { t: "Alex says "damn it"", hit: true },
  { t: "tangent about coffee", hit: true },
  { t: "Alex brings up his pigeon theory", hit: true },
  { t: "we lose the thread", hit: false },
  { t: "MITTE", free: true },
  { t: "spontaneous bet", hit: false },
  { t: ""that was episode 38, right?"", hit: true },
  { t: "someone has to sneeze", hit: false },
  { t: "conspiracy corner", hit: false },
];

function fmt(s) {
  const m = Math.floor(s / 60), sec = Math.floor(s % 60);
  const h = Math.floor(m / 60);
  if (h > 0) return `${h}:${String(m % 60).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
  return `${m}:${String(sec).padStart(2, "0")}`;
}

function rng(seed) {
  let s = seed % 2147483647;
  if (s <= 0) s += 2147483646;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

function MosaicCover({ seed, size = 88, radius = 14 }) {
  const r = rng(seed * 97 + 13);
  const cells = [];
  const tile = 28, gap = 3;
  for (let i = 0; i < 9; i++) {
    const c = COVER_PALETTE[Math.floor(r() * COVER_PALETTE.length)];
    const x = (i % 3) * (tile + gap);
    const y = Math.floor(i / 3) * (tile + gap);
    cells.push(<rect key={i} x={x} y={y} width={tile} height={tile} rx={5} fill={c} />);
  }
  return (
    <svg viewBox="0 0 90 90" width={size} height={size}
      style={{ borderRadius: radius, display: "block", flexShrink: 0 }}
      aria-hidden="true">{cells}</svg>
  );
}

function BrandMark({ size = 30 }) {
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true">
      <rect x="4" y="4" width="43" height="43" rx="11" fill="#C8553D" />
      <g fill="#FDF6EE">
        <rect x="14" y="20.5" width="3" height="10" rx="1.5" /><rect x="19" y="15.5" width="3" height="20" rx="1.5" />
        <rect x="24" y="18.5" width="3" height="14" rx="1.5" /><rect x="29" y="14.5" width="3" height="22" rx="1.5" />
        <rect x="34" y="19.5" width="3" height="12" rx="1.5" />
      </g>
      <rect x="53" y="4" width="43" height="43" rx="11" fill="#E0913A" />
      <g fill="#FDF6EE">
        <rect x="60" y="15" width="8" height="8" rx="2" /><rect x="71" y="17.25" width="17" height="3.5" rx="1.75" />
        <rect x="60" y="29" width="8" height="8" rx="2" /><rect x="71" y="31.25" width="17" height="3.5" rx="1.75" />
      </g>
      <rect x="4" y="53" width="43" height="43" rx="11" fill="#2E6E6A" />
      <g stroke="#FDF6EE" strokeWidth="2.2" strokeLinecap="round">
        <line x1="25.5" y1="74.5" x2="25.5" y2="62" /><line x1="25.5" y1="74.5" x2="15" y2="84" /><line x1="25.5" y1="74.5" x2="36" y2="84" />
      </g>
      <g fill="#FDF6EE">
        <circle cx="25.5" cy="74.5" r="4.5" /><circle cx="25.5" cy="62" r="3" /><circle cx="15" cy="84" r="3" /><circle cx="36" cy="84" r="3" />
      </g>
      <rect x="53" y="53" width="43" height="43" rx="11" fill="none" stroke="#CBB9A6" strokeWidth="2.5" strokeDasharray="5 5" />
    </svg>
  );
}

export default function App() {
  const [theme, setTheme] = useState("light");
  const [accent, setAccent] = useState(ACCENTS[0]);
  const [view, setView] = useState("feed");
  const [selected, setSelected] = useState(EPISODES[0]);
  const [season, setSeason] = useState("all");
  const [slots, setSlots] = useState(false);
  const [menu, setMenu] = useState(false);

  // persistent player state – survives the view switch
  const [current, setCurrent] = useState(EPISODES[0]);
  const [playing, setPlaying] = useState(false);
  const [t, setT] = useState(742);
  const tick = useRef(null);

  useEffect(() => {
    if (playing) {
      tick.current = setInterval(() => {
        setT((x) => {
          if (x >= current.dur) { setPlaying(false); return current.dur; }
          return x + 1;
        });
      }, 1000);
    }
    return () => clearInterval(tick.current);
  }, [playing, current]);

  function playEpisode(ep) {
    if (ep.locked || ep.upcoming) return;
    if (current.id === ep.id) { setPlaying((p) => !p); return; }
    setCurrent(ep); setT(0); setPlaying(true);
  }
  function openDetail(ep) { setSelected(ep); setView("detail"); window.scrollTo?.(0, 0); }

  const list = EPISODES.filter((e) =>
    season === "all" ? true : season === "bonus" ? e.locked : String(e.season) === season
  );

  return (
    <div data-theme={theme} className={"mc-root" + (slots ? " mc-slots" : "")}
      style={{ "--accent": accent.accent, "--accent-contrast": accent.contrast }}>
      <style>{CSS}</style>

      {/* ---------- Top Bar ---------- */}
      <header className="mc-top" data-slot="top">
        <div className="mc-top-inner">
          <button className="mc-brand" onClick={() => setView("feed")}>
            <MosaicCover seed={1} size={34} radius={9} />
            <div className="mc-brand-text">
              <strong>Halbwissen</strong>
              <span>the podcast with Alex &amp; Jonas</span>
            </div>
          </button>

          <nav className={"mc-nav" + (menu ? " open" : "")}>
            <a className="active">Episodes</a><a>Wiki</a><a>About</a><a>Support</a>
          </nav>

          <div className="mc-tools">
            <div className="mc-swatches" title="Accent color (seed)">
              {ACCENTS.map((a) => (
                <button key={a.id} onClick={() => setAccent(a)}
                  className={"mc-sw" + (accent.id === a.id ? " on" : "")}
                  style={{ background: a.accent }} aria-label={a.label} title={a.label} />
              ))}
            </div>
            <button className={"mc-icon" + (slots ? " on" : "")} onClick={() => setSlots((s) => !s)} title="Show plugin slots">
              <Layers size={18} />
            </button>
            <button className="mc-icon" onClick={() => setTheme((x) => (x === "light" ? "dark" : "light"))} title="Toggle theme">
              {theme === "light" ? <Moon size={18} /> : <Sun size={18} />}
            </button>
            <button className="mc-icon mc-only-mobile" onClick={() => setMenu((m) => !m)}><Menu size={18} /></button>
          </div>
        </div>
      </header>

      {/* ---------- Content ---------- */}
      <main className="mc-main-area">
        {view === "feed"
          ? <FeedView list={list} season={season} setSeason={setSeason} onOpen={openDetail} onPlay={playEpisode} current={current} playing={playing} />
          : <DetailView ep={selected} onBack={() => setView("feed")} onPlay={playEpisode} current={current} playing={playing} onOpen={openDetail} />}
        <footer className="mc-foot">
          <BrandMark size={20} /> <span>powered by <strong>Mosaicast</strong> · mockup, all data fictional</span>
        </footer>
      </main>

      {/* ---------- Persistenter Player ---------- */}
      <div className="mc-player" data-slot="player">
        <div className="mc-player-now">
          <MosaicCover seed={current.id} size={46} radius={9} />
          <div className="mc-player-meta">
            <strong>{current.title}</strong>
            <span>Halbwissen{current.season ? ` · S${current.season} E${current.ep}` : " · Bonus"}</span>
          </div>
        </div>
        <div className="mc-player-ctrls">
          <div className="mc-player-buttons">
            <button className="mc-pbtn"><SkipBack size={18} /></button>
            <button className="mc-pbtn main" onClick={() => setPlaying((p) => !p)}>
              {playing ? <Pause size={20} /> : <Play size={20} />}
            </button>
            <button className="mc-pbtn"><SkipForward size={18} /></button>
          </div>
          <div className="mc-scrub">
            <span>{fmt(t)}</span>
            <input type="range" min={0} max={current.dur} value={t}
              onChange={(e) => setT(Number(e.target.value))} />
            <span>{fmt(current.dur)}</span>
          </div>
        </div>
        <div className="mc-player-vol"><Volume2 size={18} /><div className="mc-vol-track"><div className="mc-vol-fill" /></div></div>
      </div>
    </div>
  );
}

function FilterBar({ season, setSeason }) {
  return (
    <div className="mc-filterbar">
      <div className="mc-select">
        <span>Season</span>
        <select value={season} onChange={(e) => setSeason(e.target.value)}>
          <option value="all">All</option>
          <option value="2">Season 2</option>
          <option value="1">Season 1</option>
          <option value="bonus">Bonus</option>
        </select>
        <ChevronDown size={15} />
      </div>
      <div className="mc-select">
        <span>Sort</span>
        <select defaultValue="new"><option value="new">Newest first</option><option value="old">Oldest first</option></select>
        <ChevronDown size={15} />
      </div>
      <span className="mc-filter-note">Filters belong to the host – plugins only react to them</span>
    </div>
  );
}

function EpisodeCard({ ep, onOpen, onPlay, current, playing }) {
  const isCur = current.id === ep.id;
  return (
    <article className={"mc-card" + (ep.locked ? " locked" : "") + (ep.upcoming ? " upcoming" : "")}>
      <button className="mc-card-cover" onClick={() => (ep.locked ? null : onOpen(ep))}>
        <MosaicCover seed={ep.id} size={88} />
        {!ep.locked && !ep.upcoming && (
          <span className="mc-play-badge" onClick={(e) => { e.stopPropagation(); onPlay(ep); }}>
            {isCur && playing ? <Pause size={18} /> : <Play size={18} />}
          </span>
        )}
        {ep.locked && <span className="mc-lock-badge"><Lock size={16} /></span>}
        {ep.upcoming && <span className="mc-up-badge"><Clock size={15} /></span>}
      </button>

      <div className="mc-card-body">
        <div className="mc-card-badges">
          {ep.season ? <span className="mc-badge">S{ep.season} · E{ep.ep}</span> : <span className="mc-badge alt">Bonus</span>}
          {ep.upcoming
            ? <span className="mc-badge up"><Clock size={11} /> Upcoming</span>
            : <span className="mc-badge ghost"><Clock size={12} /> {fmt(ep.dur)}</span>}
          {ep.locked && <span className="mc-badge lock"><Lock size={11} /> {ep.tier}</span>}
        </div>
        <h3 onClick={() => (ep.locked ? null : onOpen(ep))}>{ep.title}</h3>
        <p className="mc-card-desc">{ep.desc}</p>

        {/* card slot: compact plugin renderings */}
        <div className="mc-card-slot" data-slot="card">
          {ep.upcoming ? (
            <a className="mc-unlock accent2">Fill in the bingo for the upcoming episode →</a>
          ) : !ep.locked ? (
            <>
              <span className="mc-chip"><Mic size={12} /> Alex {ep.alex}% / Jonas {100 - ep.alex}%</span>
              <span className="mc-chip accent"><Check size={12} /> Bingo {ep.bingoHits}/8 hit</span>
            </>
          ) : (
            <a className="mc-unlock">Connect with Patreon →</a>
          )}
        </div>
        <div className="mc-card-meta"><span>{ep.upcoming ? "releases " + ep.date : ep.date}</span><span>{ep.plays} Plays</span></div>
      </div>
    </article>
  );
}

function FeedView({ list, season, setSeason, onOpen, onPlay, current, playing }) {
  return (
    <section className="mc-feed">
      <div className="mc-feed-head">
        <h1>Episodes</h1>
        <p>{list.length} episodes · two feeds unified (public + Patreon-free)</p>
      </div>
      <FilterBar season={season} setSeason={setSeason} />
      <div className="mc-grid">
        {list.map((ep) => <EpisodeCard key={ep.id} ep={ep} onOpen={onOpen} onPlay={onPlay} current={current} playing={playing} />)}
      </div>
    </section>
  );
}

function BingoCardGrid({ cells, resolved }) {
  return (
    <div className="mc-bingo">
      {cells.map((b, i) => (
        <div key={i} className={"mc-bcell" + (b.free ? " free" : resolved ? (b.hit ? " hit" : "") : " pending")}>
          {resolved && b.hit && <Check size={14} className="mc-bcheck" />}
          <span>{b.t}</span>
        </div>
      ))}
    </div>
  );
}

function BingoPlugin({ resolved = true }) {
  const cards = [
    { who: "Alex", seed: 11, cells: ALEX_CARD },
    { who: "Jonas", seed: 22, cells: JONAS_CARD },
  ];
  const [tab, setTab] = useState(0);
  const active = tab < cards.length ? cards[tab] : null;
  const hits = (c) => c.cells.filter((x) => x.hit).length;
  return (
    <div className="mc-plugin" data-slot="main">
      <div className="mc-plugin-head">
        <h2>Bingo</h2>
        <span className="mc-pill">{resolved ? "resolved" : "prediction phase running"}</span>
      </div>
      <p className="mc-plugin-sub">
        {resolved
          ? "Each host made their own bingo – green = came true. Switch the card:"
          : "The episode isn't out yet. See what Alex & Jonas predicted, and predict yourself."}
      </p>
      <div className="mc-bingo-tabs">
        {cards.map((c, i) => (
          <button key={i} className={"mc-btab" + (tab === i ? " on" : "")} onClick={() => setTab(i)}>
            <MosaicCover seed={c.seed} size={18} radius={5} />
            {c.who}{resolved && <b>{hits(c)}/8</b>}
          </button>
        ))}
        <button className={"mc-btab" + (tab === cards.length ? " on" : "")} onClick={() => setTab(cards.length)}>
          Your bingo
        </button>
      </div>
      {active ? (
        <>
          <BingoCardGrid cells={active.cells} resolved={resolved} />
          <button className="mc-cta">{resolved ? `${active.who}'s card vs. yours` : "Fill in your own bingo now"}</button>
        </>
      ) : (
        <div className="mc-bingo-empty">
          <p>Build your own 3×3 and predict against Alex &amp; Jonas.</p>
          <button className="mc-cta">Create your own bingo</button>
        </div>
      )}
    </div>
  );
}

function StatsPlugin({ ep }) {
  return (
    <div className="mc-plugin" data-slot="main">
      <div className="mc-plugin-head"><h2>Statistics</h2><span className="mc-pill">from MAT</span></div>
      <div className="mc-stat-bars">
        <div className="mc-stat-row"><label>Alex</label><div className="mc-bar"><div className="mc-bar-fill a" style={{ width: ep.alex + "%" }} /></div><b>{ep.alex}%</b></div>
        <div className="mc-stat-row"><label>Jonas</label><div className="mc-bar"><div className="mc-bar-fill b" style={{ width: 100 - ep.alex + "%" }} /></div><b>{100 - ep.alex}%</b></div>
      </div>
      <div className="mc-stat-grid">
        <div className="mc-stat-mini"><Clock size={16} /><strong>{fmt(ep.dur)}</strong><span>Runtime</span></div>
        <div className="mc-stat-mini"><Coffee size={16} /><strong>7×</strong><span>coffee mentioned</span></div>
        <div className="mc-stat-mini"><Mic size={16} /><strong>0:42</strong><span>longest silence</span></div>
      </div>
    </div>
  );
}

function DetailView({ ep, onBack, onPlay, current, playing, onOpen }) {
  const isCur = current.id === ep.id;
  const related = EPISODES.filter((e) => e.id !== ep.id && !e.locked && !e.upcoming).slice(0, 3);
  return (
    <section className="mc-detail">
      <button className="mc-back" onClick={onBack}><ChevronLeft size={16} /> Back to episodes</button>

      <div className="mc-detail-hero">
        <MosaicCover seed={ep.id} size={132} radius={18} />
        <div className="mc-detail-hero-body">
          <div className="mc-card-badges">
            <span className="mc-badge">S{ep.season} · E{ep.ep}</span>
            {ep.upcoming
              ? <span className="mc-badge up"><Clock size={12} /> Upcoming</span>
              : <span className="mc-badge ghost"><Clock size={12} /> {fmt(ep.dur)}</span>}
          </div>
          <h1>{ep.title}</h1>
          <p className="mc-detail-date">{ep.upcoming ? "releases " + ep.date : ep.date + " · " + ep.plays + " Plays"}</p>
          {ep.upcoming
            ? <div className="mc-upcoming-chip"><Clock size={16} /> releases {ep.date} – bingos are already open</div>
            : <button className="mc-bigplay" onClick={() => onPlay(ep)}>
                {isCur && playing ? <Pause size={18} /> : <Play size={18} />}
                {isCur && playing ? "Pause" : "Play"}
              </button>}
        </div>
      </div>

      <div className="mc-detail-cols">
        <div className="mc-col-main">
          <div className="mc-plugin desc" data-slot="main">
            <h2>{ep.upcoming ? "What it will be about" : "Shownotes"}</h2>
            <p>{ep.desc}{!ep.upcoming && " Also: a listener writes in about episode 38, Jonas defends his coffee machine, and Alex sticks to his pigeon theory."}</p>
          </div>
          <BingoPlugin resolved={!ep.upcoming} />
          {ep.upcoming
            ? <div className="mc-stat-placeholder"><Clock size={18} /> Statistics – speaking shares, runtime, longest silence – appear automatically once the episode shows up in the RSS feed.</div>
            : <StatsPlugin ep={ep} />}
        </div>

        <aside className="mc-col-side" data-slot="sidebar">
          <div className="mc-side-box">
            <div className="mc-plugin-head"><h2><Trophy size={16} /> Bingo leaderboard</h2></div>
            <p className="mc-plugin-sub">Host hits this season</p>
            <ol className="mc-lb">
              <li><MosaicCover seed={11} size={26} radius={7} /><span>Alex</span><b>7</b></li>
              <li><MosaicCover seed={22} size={26} radius={7} /><span>Jonas</span><b>5</b></li>
            </ol>
          </div>
          <div className="mc-side-box">
            <div className="mc-plugin-head"><h2>Jump to season 2</h2></div>
            <button className="mc-side-link">View all 12 episodes →</button>
          </div>
          <div className="mc-side-box">
            <div className="mc-plugin-head"><h2>Related episodes</h2></div>
            <ul className="mc-related">
              {related.map((r) => (
                <li key={r.id} onClick={() => onOpen(r)}>
                  <MosaicCover seed={r.id} size={40} radius={8} />
                  <div><strong>{r.title}</strong><span>S{r.season} · E{r.ep}</span></div>
                </li>
              ))}
            </ul>
          </div>
        </aside>
      </div>
    </section>
  );
}

const CSS = `
@import url('https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,500;12..96,700;12..96,800&family=Inter:wght@400;500;600;700&display=swap');

.mc-root{
  --bg:#FBF6EF;--surface:#FFFFFF;--surface-2:#F4ECE1;--surface-3:#EFE4D5;
  --text:#2B2722;--muted:#7C7064;--border:#E8DDCD;--accent-2:#2E6E6A;--lock:#B89B7E;
  --shadow:0 1px 2px rgba(43,39,34,.05),0 8px 24px rgba(43,39,34,.06);
  background:var(--bg);color:var(--text);
  font-family:Inter,system-ui,sans-serif;min-height:100vh;padding-bottom:92px;
  transition:background .25s,color .25s;
}
.mc-root[data-theme="dark"]{
  --bg:#171411;--surface:#211D19;--surface-2:#2A2520;--surface-3:#332C25;
  --text:#F3EBE0;--muted:#A99B8C;--border:#3A332C;--accent-2:#46A59E;--lock:#6E5E4D;
  --shadow:0 1px 2px rgba(0,0,0,.3),0 10px 30px rgba(0,0,0,.4);
}
.mc-root *{box-sizing:border-box}
h1,h2,h3,strong,b{font-family:'Bricolage Grotesque',Inter,sans-serif}
button{font-family:inherit;cursor:pointer;border:none;background:none;color:inherit}
:focus-visible{outline:2px solid var(--accent-2);outline-offset:2px;border-radius:6px}

/* ---- Top Bar ---- */
.mc-top{position:sticky;top:0;z-index:30;background:color-mix(in srgb,var(--surface) 88%,transparent);
  backdrop-filter:blur(10px);border-bottom:1px solid var(--border)}
.mc-top-inner{max-width:1180px;margin:0 auto;padding:11px 22px;display:flex;align-items:center;gap:18px}
.mc-brand{display:flex;align-items:center;gap:11px}
.mc-brand-text{display:flex;flex-direction:column;line-height:1.15;text-align:left}
.mc-brand-text strong{font-size:18px;font-weight:800;letter-spacing:-.02em}
.mc-brand-text span{font-size:11.5px;color:var(--muted)}
.mc-nav{display:flex;gap:4px;margin-left:8px}
.mc-nav a{padding:7px 13px;border-radius:9px;font-size:14px;font-weight:500;color:var(--muted);cursor:pointer}
.mc-nav a:hover{background:var(--surface-2);color:var(--text)}
.mc-nav a.active{color:var(--text);background:var(--surface-2)}
.mc-tools{margin-left:auto;display:flex;align-items:center;gap:8px}
.mc-swatches{display:flex;gap:5px;padding:4px;background:var(--surface-2);border-radius:10px}
.mc-sw{width:20px;height:20px;border-radius:6px;border:2px solid transparent;transition:transform .1s}
.mc-sw:hover{transform:scale(1.1)}
.mc-sw.on{border-color:var(--text);box-shadow:0 0 0 2px var(--surface)}
.mc-icon{width:38px;height:38px;border-radius:10px;display:grid;place-items:center;background:var(--surface-2);color:var(--text)}
.mc-icon:hover{background:var(--surface-3)}
.mc-icon.on{background:var(--accent-2);color:#fff}
.mc-only-mobile{display:none}

/* ---- Layout ---- */
.mc-main-area{max-width:1180px;margin:0 auto;padding:30px 22px}
.mc-feed-head h1{font-size:30px;font-weight:800;letter-spacing:-.025em}
.mc-feed-head p{color:var(--muted);font-size:14px;margin-top:3px}

.mc-filterbar{display:flex;align-items:center;gap:12px;margin:20px 0 24px;flex-wrap:wrap}
.mc-select{display:flex;align-items:center;gap:7px;background:var(--surface);border:1px solid var(--border);
  border-radius:11px;padding:8px 11px;position:relative;box-shadow:var(--shadow)}
.mc-select span{font-size:12px;color:var(--muted);font-weight:600}
.mc-select select{appearance:none;background:none;border:none;color:var(--text);font-size:13.5px;font-weight:600;padding-right:18px;cursor:pointer}
.mc-select svg{position:absolute;right:10px;color:var(--muted);pointer-events:none}
.mc-filter-note{font-size:12px;color:var(--muted);margin-left:auto;font-style:italic}

.mc-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}

/* ---- Cards ---- */
.mc-card{display:flex;gap:15px;background:var(--surface);border:1px solid var(--border);
  border-radius:16px;padding:15px;box-shadow:var(--shadow);transition:transform .12s,border-color .12s}
.mc-card:hover{transform:translateY(-2px);border-color:color-mix(in srgb,var(--accent) 40%,var(--border))}
.mc-card.locked{opacity:.96}
.mc-card.locked .mc-card-cover{filter:saturate(.5)}
.mc-card-cover{position:relative;flex-shrink:0;height:88px}
.mc-play-badge,.mc-lock-badge{position:absolute;inset:auto 6px 6px auto;width:30px;height:30px;border-radius:9px;
  display:grid;place-items:center;background:var(--accent);color:var(--accent-contrast);box-shadow:0 3px 8px rgba(0,0,0,.2)}
.mc-lock-badge{background:var(--lock);color:#fff}
.mc-card-body{display:flex;flex-direction:column;gap:7px;min-width:0;flex:1}
.mc-card-badges{display:flex;gap:6px;flex-wrap:wrap}
.mc-badge{font-size:11px;font-weight:700;padding:3px 8px;border-radius:7px;background:var(--accent-2);color:#fff;display:inline-flex;align-items:center;gap:4px}
.mc-badge.alt{background:var(--accent)}
.mc-badge.ghost{background:var(--surface-2);color:var(--muted)}
.mc-badge.lock{background:var(--lock);color:#fff}
.mc-card-body h3{font-size:16px;font-weight:700;letter-spacing:-.01em;line-height:1.25;cursor:pointer}
.mc-card-body h3:hover{color:var(--accent)}
.mc-card-desc{font-size:13px;color:var(--muted);line-height:1.45;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.mc-card-slot{display:flex;gap:7px;flex-wrap:wrap;margin-top:2px}
.mc-chip{font-size:11.5px;font-weight:600;color:var(--muted);background:var(--surface-2);padding:4px 9px;border-radius:8px;display:inline-flex;align-items:center;gap:5px}
.mc-chip.accent{color:var(--accent);background:color-mix(in srgb,var(--accent) 12%,transparent)}
.mc-unlock{font-size:12.5px;font-weight:700;color:var(--accent);cursor:pointer}
.mc-card-meta{display:flex;justify-content:space-between;font-size:11.5px;color:var(--muted);margin-top:auto;padding-top:4px}

/* ---- Detail ---- */
.mc-back{display:inline-flex;align-items:center;gap:5px;font-size:13.5px;font-weight:600;color:var(--muted);margin-bottom:18px}
.mc-back:hover{color:var(--accent)}
.mc-detail-hero{display:flex;gap:22px;align-items:center;margin-bottom:26px}
.mc-detail-hero-body h1{font-size:27px;font-weight:800;letter-spacing:-.02em;line-height:1.15;margin:8px 0 4px}
.mc-detail-date{font-size:13px;color:var(--muted);margin-bottom:14px}
.mc-bigplay{display:inline-flex;align-items:center;gap:8px;background:var(--accent);color:var(--accent-contrast);
  font-weight:700;font-size:14px;padding:10px 20px;border-radius:11px;box-shadow:var(--shadow)}
.mc-bigplay:hover{filter:brightness(.95)}
.mc-detail-cols{display:grid;grid-template-columns:1fr 320px;gap:22px;align-items:start}
.mc-col-main{display:flex;flex-direction:column;gap:18px}

.mc-plugin{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:20px;box-shadow:var(--shadow)}
.mc-plugin-head{display:flex;align-items:center;justify-content:space-between;gap:10px}
.mc-plugin-head h2{font-size:17px;font-weight:700;display:flex;align-items:center;gap:7px}
.mc-plugin-sub{font-size:13px;color:var(--muted);margin:6px 0 14px}
.mc-plugin.desc p{font-size:14.5px;line-height:1.6;color:var(--text);margin-top:10px}
.mc-pill{font-size:11px;font-weight:700;color:var(--accent);background:color-mix(in srgb,var(--accent) 12%,transparent);padding:4px 10px;border-radius:20px}

.mc-bingo{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin-bottom:16px}
.mc-bcell{position:relative;aspect-ratio:1.55;border:1px solid var(--border);border-radius:11px;padding:9px;
  display:grid;place-items:center;text-align:center;font-size:11.5px;font-weight:600;color:var(--muted);background:var(--surface-2)}
.mc-bcell.hit{background:var(--accent);color:var(--accent-contrast);border-color:transparent}
.mc-bcell.free{background:var(--accent-2);color:#fff;border-color:transparent;font-weight:800;letter-spacing:.05em}
.mc-bcheck{position:absolute;top:6px;right:6px}
.mc-cta{width:100%;padding:11px;border-radius:11px;background:var(--surface-2);color:var(--text);font-weight:700;font-size:13.5px;border:1px solid var(--border)}
.mc-cta:hover{border-color:var(--accent);color:var(--accent)}

.mc-bingo-tabs{display:flex;gap:7px;margin:0 0 14px;flex-wrap:wrap}
.mc-btab{display:inline-flex;align-items:center;gap:7px;padding:6px 11px 6px 7px;border-radius:10px;
  background:var(--surface-2);font-size:13px;font-weight:700;color:var(--muted);border:1.5px solid transparent}
.mc-btab:hover{color:var(--text)}
.mc-btab.on{background:var(--surface);border-color:var(--accent);color:var(--text);box-shadow:var(--shadow)}
.mc-btab b{font-size:11px;font-weight:700;color:var(--accent);background:color-mix(in srgb,var(--accent) 14%,transparent);padding:1px 6px;border-radius:6px}
.mc-bcell.pending{background:var(--surface-2);color:var(--text);border-style:dashed}
.mc-bingo-empty{text-align:center;padding:22px 8px;color:var(--muted)}
.mc-bingo-empty p{font-size:13.5px;margin-bottom:14px}
.mc-badge.up{background:var(--accent-2)}
.mc-up-badge{position:absolute;inset:auto 6px 6px auto;width:30px;height:30px;border-radius:9px;display:grid;place-items:center;background:var(--accent-2);color:#fff;box-shadow:0 3px 8px rgba(0,0,0,.2)}
.mc-unlock.accent2{color:var(--accent-2)}
.mc-card.upcoming{border-style:dashed}
.mc-upcoming-chip{display:inline-flex;align-items:center;gap:8px;background:var(--surface-2);color:var(--text);font-weight:700;font-size:13.5px;padding:10px 18px;border-radius:11px;border:1px dashed var(--border)}
.mc-stat-placeholder{display:flex;align-items:center;gap:10px;background:var(--surface);border:1px dashed var(--border);border-radius:16px;padding:18px 20px;color:var(--muted);font-size:13.5px;line-height:1.5}

.mc-stat-bars{display:flex;flex-direction:column;gap:11px;margin:6px 0 18px}
.mc-stat-row{display:flex;align-items:center;gap:12px}
.mc-stat-row label{width:46px;font-size:13px;font-weight:600}
.mc-stat-row b{width:38px;text-align:right;font-size:13px}
.mc-bar{flex:1;height:11px;background:var(--surface-2);border-radius:20px;overflow:hidden}
.mc-bar-fill{height:100%;border-radius:20px}
.mc-bar-fill.a{background:var(--accent)}
.mc-bar-fill.b{background:var(--accent-2)}
.mc-stat-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px}
.mc-stat-mini{background:var(--surface-2);border-radius:12px;padding:13px;display:flex;flex-direction:column;gap:3px;color:var(--muted)}
.mc-stat-mini strong{font-size:18px;color:var(--text);font-weight:800}
.mc-stat-mini span{font-size:11.5px}

.mc-col-side{display:flex;flex-direction:column;gap:16px}
.mc-side-box{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:17px;box-shadow:var(--shadow)}
.mc-side-box .mc-plugin-head h2{font-size:15px}
.mc-lb{list-style:none;padding:0;margin:12px 0 0;display:flex;flex-direction:column;gap:9px}
.mc-lb li{display:flex;align-items:center;gap:10px;font-size:14px;font-weight:600}
.mc-lb li b{margin-left:auto;color:var(--accent);font-size:16px}
.mc-side-link{margin-top:12px;font-size:13.5px;font-weight:700;color:var(--accent)}
.mc-related{list-style:none;padding:0;margin:12px 0 0;display:flex;flex-direction:column;gap:11px}
.mc-related li{display:flex;gap:11px;align-items:center;cursor:pointer}
.mc-related li:hover strong{color:var(--accent)}
.mc-related strong{font-size:13.5px;font-weight:700;display:block;line-height:1.3}
.mc-related span{font-size:11.5px;color:var(--muted)}

.mc-foot{margin-top:30px;padding-top:18px;border-top:1px solid var(--border);display:flex;align-items:center;gap:8px;font-size:12.5px;color:var(--muted)}

/* ---- Player ---- */
.mc-player{position:fixed;bottom:0;left:0;right:0;z-index:40;height:78px;background:var(--surface);
  border-top:1px solid var(--border);display:flex;align-items:center;gap:20px;padding:0 22px;box-shadow:0 -6px 24px rgba(0,0,0,.07)}
.mc-player-now{display:flex;align-items:center;gap:12px;width:260px;min-width:0}
.mc-player-meta{min-width:0}
.mc-player-meta strong{display:block;font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mc-player-meta span{font-size:11.5px;color:var(--muted)}
.mc-player-ctrls{flex:1;display:flex;flex-direction:column;align-items:center;gap:6px;max-width:560px;margin:0 auto}
.mc-player-buttons{display:flex;align-items:center;gap:10px}
.mc-pbtn{width:34px;height:34px;border-radius:50%;display:grid;place-items:center;color:var(--muted)}
.mc-pbtn:hover{color:var(--text);background:var(--surface-2)}
.mc-pbtn.main{width:42px;height:42px;background:var(--accent);color:var(--accent-contrast)}
.mc-pbtn.main:hover{filter:brightness(.95);background:var(--accent)}
.mc-scrub{display:flex;align-items:center;gap:10px;width:100%}
.mc-scrub span{font-size:11px;color:var(--muted);font-variant-numeric:tabular-nums;width:42px;text-align:center}
.mc-scrub input{flex:1;-webkit-appearance:none;appearance:none;height:5px;border-radius:5px;background:var(--surface-3);cursor:pointer}
.mc-scrub input::-webkit-slider-thumb{-webkit-appearance:none;width:13px;height:13px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--accent) 25%,transparent)}
.mc-scrub input::-moz-range-thumb{width:13px;height:13px;border:none;border-radius:50%;background:var(--accent)}
.mc-player-vol{display:flex;align-items:center;gap:8px;width:120px;color:var(--muted)}
.mc-vol-track{flex:1;height:5px;border-radius:5px;background:var(--surface-3)}
.mc-vol-fill{width:65%;height:100%;border-radius:5px;background:var(--accent-2)}

/* ---- Slot-Overlay ---- */
.mc-slots [data-slot]{outline:1.5px dashed var(--accent-2);outline-offset:-1px;border-radius:14px;position:relative}
.mc-slots [data-slot]::after{content:attr(data-slot);position:absolute;top:6px;left:6px;z-index:25;
  font:700 9px/1 Inter,sans-serif;letter-spacing:.08em;text-transform:uppercase;color:#fff;background:var(--accent-2);padding:3px 6px;border-radius:6px;pointer-events:none}
.mc-slots .mc-player::after{top:8px;left:8px}

@media (max-width:880px){
  .mc-detail-cols{grid-template-columns:1fr}
  .mc-grid{grid-template-columns:1fr}
}
@media (max-width:680px){
  .mc-nav{display:none;position:absolute;top:60px;left:0;right:0;flex-direction:column;background:var(--surface);
    border-bottom:1px solid var(--border);padding:8px}
  .mc-nav.open{display:flex}
  .mc-only-mobile{display:grid}
  .mc-swatches{display:none}
  .mc-brand-text span{display:none}
  .mc-player-now{width:auto}
  .mc-player-meta{display:none}
  .mc-player-vol{display:none}
  .mc-filter-note{display:none}
  .mc-detail-hero{flex-direction:column;text-align:center;align-items:center}
}
@media (prefers-reduced-motion:reduce){.mc-root *{transition:none!important;animation:none!important}}
`;
