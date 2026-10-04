# Pactum

Parental control turned around: **the teenager writes their own rules and the
parent verifies them**. It started from one constraint: never block anything,
be a witness rather than a jailer. Two exceptions have since been let in, both
named and both visible in the log: a session the teenager switches on
themselves, and household chores the parents can set, which lock the devices
until they're done.

Four parts: an Android app on the teenager's phone that measures usage and
records what happens, a Windows program that does the same on their computer, an
Android app for each parent that can read and propose changes to the rules but
never impose them, and a server that holds the rules and the log. The apps are sideloaded APKs and the
Windows program is a zip, both downloaded from the server's own page rather than
from a store.

## Why it exists

I'm the teenager in this. I built it for my own phone, because the version of
this problem I actually know is the one from the inside: every parental control
I'd seen treats the kid as the adversary, and the kid responds by getting better
at working around it. That fight is winnable by whoever is more technical, which
is a strange thing to base a family relationship on.

So the question I started from wasn't "how does a parent restrict a phone", it
was "what would I actually agree to". The answer turned out to be: I'll set the
limits myself, and I'll accept being seen keeping them, as long as being seen has
a boundary and as long as breaking a limit leads to a conversation instead of a
punishment the software administers.

Success here isn't measured by the phone being used less. It's the kid keeping a
promise they made to themselves, and saying so out loud when they don't.

## The decisions that matter

**The asymmetric lock.** Tightening a rule takes effect immediately. Loosening
one requires four days to have passed since it was last changed. The lock exists
to protect me from myself at 11pm, and it only ever resists in the direction
where I'd be arguing with my own past judgement. The exception is agreement:
either side can propose a change to the other, and a change both sides accepted
takes effect at once, because if both agree the lock isn't protecting anyone.
The idea is borrowed from Beeminder's akrasia horizon.

**Referee verification, stickK-style.** Some rules aren't measurable by a phone,
"walk an hour a day" being the obvious kind. For those the teenager names a human
referee. A declared success needs the referee to confirm it; a declared failure is
believed straight away, because nobody lies against themselves. My variant: the
parent can confirm on the referee's behalf ("I spoke to her outside the app"),
and the log records that as exactly what it was, *confirmed by the parent on
behalf of X*, so it stays visible who vouched for what.

**A window, not a glass wall.** The parent sees everything about the pact: the
rules, whether they're being kept, overruns, the history of changes, remaining
bonus minutes, tamper events, silences. What stays outside is everything about
the phone that isn't the pact: message contents, what's on screen, location,
real-time position. The line has moved twice, deliberately. Daily usage totals
for *all* apps are inside the window rather than only apps with a rule attached,
because "two hours, no limit set" is material for a conversation. And websites
are inside as domains: on the phone through an optional log fed by a local VPN
that carries nothing but DNS queries, on the computer by reading the domain from
the browser's address bar. Either way what's recorded is the domain and how
much, never the page. On the computer that's a weaker promise than "can't see",
and it's written down as exactly that.

**The log is the product.** Nothing is blocked, so the entire value rests on
whether the record can be trusted. Extended airplane mode, force-stop, a changed
system clock, uninstalling: all recorded as events, in the same register as an
ordinary overrun, as in *the app couldn't see between 15:00 and 18:00*. The
strongest defence deliberately lives off the phone, where the app can't be
persuaded, with the server watching for missing heartbeats. Force-stop and
uninstall look identical from the server side, and that's fine, because telling
them apart belongs in a conversation rather than in an API. There is deliberately
no Device Admin and no uninstall lock: it would contradict the premise, and on a
sideloaded app it trips Android's anti-stalkerware checks anyway.

**Sessions: the one thing that stops you.** Since 0.11 there is a single
exception to "never blocks", and it's one the teenager turns on. A session is a
name and a list of apps, say *Study*: the school app, a dictionary, the
calculator. The teenager creates it, the parent approves it once and then every
change to it, and from there the teenager starts it whenever they want, for as
long as they choose. While it runs, opening an app outside the list brings up a
full-screen barrier whose only button goes back to the home screen; calls, the
keyboard, Settings and Pactum stay reachable, and time in the session's own apps
doesn't count against any limit. A session can always be ended early from
Pactum. The parent sees the start, the end and any early close, never the
attempts in between. It got in under one rule: a limit the teenager sets on
themselves is not a limit the parent imposes.

**Chores: the exception my family asked for.** Since 0.13 a parent can set
household chores, from "now" or from a time they choose. Until every chore has
a photo of the finished job, taken there and then with the camera, the phone is
locked except for a short list (calls, contacts, SMS, wallet, camera, photos, a
couple of payment and home apps, Settings, Pactum), and the computer is covered
completely. The last photo unlocks both at once; a parent can reject a photo
within 24 hours, which reopens that chore and the lock with it. This one runs
against the premise and I know it: it's a rule my family and I agreed on,
because it's the way I actually get chores done straight away. It uses the
same means as the session barrier, so it isn't spyware and it can be broken
(uninstalling, force-stopping, killing the program on the computer), but every
break lands in the log the parents see. Photos travel without location data and
are deleted from the server after 30 days.

## Day to day

- Rules of three kinds: a daily time limit (on one app, on a category such as
  social, video, games or music, or on the whole device), a time-of-day window,
  and a real-life rule with a referee.
- A notification five minutes and one minute before a limit runs out.
- On an overrun, a full-screen notice on the phone or the computer, closed with
  one tap; the parent's app shows the overrun within a minute or two. Nothing is
  stopped.
- Bonus minutes (+5, +15, +30) the teenager grants themselves without asking,
  within daily and weekly caps; the parent sees what's left.
- A short animated page at the start and end of each session, its theme picked
  from the session's name.
- One family can have several parents and several children, each child with a
  phone and a computer. Every device keeps its own rules, bonus and log. Devices
  and parents join with a six-digit code shown in a parent's app, and the log
  says which parent proposed, approved or set what.

## What it deliberately doesn't do

Outside a session the teenager started and the chores lock, it never blocks an
app, a site or a feature. It doesn't let the parents read messages or content.
It doesn't let them edit or impose a rule or start a session: they propose
changes and approve the teenager's. And it doesn't run hidden: the teenager always knows
it's there. That last one is less a feature than the precondition for any of the
rest to mean anything.

## Status — work in progress

Where it honestly stands at version 0.14, in October 2026:

- **It's deployed in one family, mine.** Since September the server has run on a
  NAS at home, in Docker, reachable from outside through Tailscale Funnel, with a
  nightly backup of the log. My phone reports to it, and the parent app has been
  on my father's phone since the start of October, so real use is measured in
  days, not months.
- **The Windows program runs on the home computer but is two versions behind**
  the phone apps.
- **Sessions, the chores lock and several parents have been built, tested and
  adversarially reviewed, not yet tried live** on a real phone.
- **The app categories were redone in 0.13.** The first version trusted the
  category each Android app declares about itself, which is how Firefox ended up
  under social. Curated lists replaced it.
- **Tests aren't use.** There are more than 2,000 automated tests across the four
  parts and every milestone went through an adversarial review, but the
  behaviour that matters most only shows up on a real phone over a real day. The
  visual design is where the work is now.

Treat this as a system in early real use, not a finished product.

## Stack

- **Phone apps.** Kotlin and Jetpack Compose, minSdk 26, sharing a
  `core-design` module. Usage comes from `UsageStatsManager`'s event stream
  (paired resume/pause events), re-read from the history Android keeps by
  itself, so an app that gets killed loses timeliness rather than data. A
  foreground service checks once a minute while the screen is on so warnings
  land on time, with WorkManager as the backstop. The parent app runs its own
  foreground service that polls the server every minute instead of relying on
  push notifications.
- **Windows program.** C# on .NET 8: a WinForms shell around a WebView2
  interface in plain HTML and JavaScript. It measures the program in the
  foreground and, through UI Automation, the domain in the browser's address
  bar. Notes in [`docs/pc-programma.md`](docs/pc-programma.md).
- **Server.** FastAPI and SQLite in Docker, on a home NAS, published through
  Tailscale Funnel; [`docs/deploy-nas-ugreen.md`](docs/deploy-nas-ugreen.md) is
  the current deployment guide, the Synology/Cloudflare ones are older. The
  server clock is authoritative for every timestamp; device clocks are recorded
  alongside and never trusted. Every night it copies the database with
  `VACUUM INTO`, checks the copy and keeps the last 30.
- **The contract comes first.** Every protocol change is written into
  [`docs/contratto-api.md`](docs/contratto-api.md) before any code. The rule
  dates from the first two parts built in parallel, which invented incompatible
  dialects of the same API.

Domain terms are in Italian throughout the code (`regola`, `sforamento`,
`bonus`, `patto`, `finestra`, `arbitro`, `sessione`), technical plumbing in
English. The documents in `docs/` are in Italian too:
[`concept.md`](docs/concept.md) for what it is and why,
[`analisi-e-ragionamento.md`](docs/analisi-e-ragionamento.md) for how each
decision was reached, [`architettura.md`](docs/architettura.md) for how it fits
together. They're working notes kept as written, so the older ones describe the
project as it was then.

I make the product and design calls and find the behavioural problems by using
the thing; Claude writes the implementation and finds the bugs a few layers down.

Personal project. Not a commercial product, and not advice on how to raise
anyone.
