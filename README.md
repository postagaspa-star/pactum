# Pactum

An Android app that inverts parental control: **the teenager writes their own
rules and the parent verifies them**. It never blocks anything, which makes it a
witness rather than a jailer, and the whole design follows from that one
constraint.

Three parts: an app on the teenager's phone that measures usage and records what
happens, an app for the parent that can read and propose but never impose, and a
server that holds the rules and the log. Distributed as a sideloaded APK, not
through a store.

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

## The four decisions that matter

**The asymmetric lock.** Tightening a rule takes effect immediately. Loosening
one requires four days to have passed since it was last changed. The lock exists
to protect me from myself at 11pm, and it only ever resists in the direction
where I'd be arguing with my own past judgement. There's one exception: a change
that came from a parent's proposal I accepted takes effect at once, because if
both sides agree the lock isn't protecting anyone. The idea is borrowed from
Beeminder's akrasia horizon.

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
real-time position. The line moved once, deliberately. Daily usage totals for
*all* apps are now inside the window rather than only apps with a rule attached,
because "two hours, no limit set" is material for a conversation. Aggregated per
day, still no content.

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

## What it deliberately doesn't do

It never blocks an app, a site or a feature. It doesn't let the parent read
messages or content. It doesn't let the parent edit or impose a rule, only
propose one. And it doesn't run hidden: the teenager always knows it's there.
That last one is less a feature than the precondition for any of the rest to mean
anything.

## Status — work in progress

This is not a finished product, and the honest version of where it stands is:

- **It runs on my own phone, against a server on my PC.** Moving the server to a
  NAS is underway and not finished; what's in `docker-compose.yml` is the target,
  not something that has been running unattended.
- **The parent app isn't installed on my father's phone yet.** He's the parent
  it's designed for and he has seen it demonstrated, but nobody has used the
  parent side day to day. Getting it onto his phone is the milestone that would
  make this real, and it hasn't happened.
- **The interface needs redoing.** There's a full design review in `docs/` that I
  agree with and haven't acted on yet.
- **The complete pact flow has never been driven live between the two apps.** It's
  covered by server unit tests and adversarial review at every milestone, and the
  server suite is green, but tests passing is not the same claim as two real
  phones working end to end.

Treat this as a design that has been built and reviewed rather than a system in
production.

## Stack

Kotlin and Jetpack Compose on both apps, minSdk 26. Usage is measured through
`UsageStatsManager` from paired resume/pause events, read back by a periodic
worker rather than by a permanently running service, so the phone records its own
history and the app doesn't have to stay alive to measure. FastAPI and SQLite on
the server, in Docker. The server clock is authoritative for every timestamp; the
phone's clock is recorded alongside it and never trusted.

Domain terms are in Italian throughout the code (`regola`, `sforamento`, `bonus`,
`patto`, `finestra`, `arbitro`), technical plumbing in English. The documents in
`docs/` are in Italian too: [`concept.md`](docs/concept.md) for what it is and
why, [`analisi-e-ragionamento.md`](docs/analisi-e-ragionamento.md) for how each
decision was reached, [`architettura.md`](docs/architettura.md) for how it fits
together.

I make the product and design calls and find the behavioural problems by using
the thing; Claude writes the implementation and finds the bugs a few layers down.

Personal project. Not a commercial product, and not advice on how to raise
anyone.
