# Le categorie di Pactum

Cosa conta come **Social**, **Giochi**, **Video**, **Musica** e **Altre app** (al computer si chiama **Altro**), sul telefono e sul computer.

Revisione completa del 2 ottobre 2026. Le liste valgono dal prossimo aggiornamento dell'app del telefono e del programma del computer.

## La regola

1. Un'app, un programma o un sito che sta in una delle liste qui sotto è nella categoria della sua lista.
2. Sul telefono, un'app che non è nelle liste va in Giochi solo se dice da sola di essere un gioco.
3. Tutto il resto va in Altre app: i browser e le app di messaggi ci stanno sempre.
4. Al computer, il tempo nel browser va nella categoria del sito aperto (YouTube in Chrome conta come Social); un sito che non è nelle liste resta in Altre app.

## Perché è cambiata

Prima il telefono si fidava della categoria che ogni app dichiara di sé. Ma ogni app la sceglie da sola: Firefox si dichiara "social", e finiva nei Social insieme a Instagram.

Per esempio, con 20 minuti di Instagram, 15 di Firefox e 10 di YouTube i Social facevano 45 minuti.
Con le liste nuove fanno 30 (Instagram 20 + YouTube 10), e i 15 minuti di Firefox vanno in Altre app.

Adesso decidono le liste scritte da noi. Dell'app ci si fida solo quando dice di essere un gioco, perché lì non sbaglia quasi mai.

## Le scelte

Decise da Andrea:

- WhatsApp e tutte le app di messaggi (Telegram, Messenger, Signal, gli SMS, Viber, WeChat, Line, Threema, Skype) non sono in nessuna categoria: vanno in Altre app.
- YouTube è Social.
- Discord, Instagram, Snapchat e TikTok sono Social.

Proposte in questa revisione e confermate da Andrea il 2 ottobre (se una non va, si cambia una riga):

- Tutti i browser vanno in Altre app.
- Twitch è Social, come YouTube. Per lo stesso motivo sono Social anche Kick, Dailymotion, Rumble e Bilibili: sono video fatti dalle persone, non film o serie. Vimeo invece è Video: cortometraggi e lavori, senza lo scorrimento infinito.
- Video vuol dire film, serie, TV e sport: Netflix, Prime Video, RaiPlay e simili.
- YouTube Music è Musica, anche al computer.
- Al computer conta solo Mediaset Infinity come Video, non tutto mediaset.it (lì c'è anche TgCom24). Allo stesso modo solo Apple Music e Apple TV, non tutto apple.com.

Due cose da sapere:

- Sul telefono il tempo nel browser resta tutto in Altre app, anche se dentro guardi YouTube: il telefono non sa quanto tempo passi su ogni sito.
- Al computer i siti si leggono in Chrome, Edge, Firefox e Brave. Negli altri browser il tempo resta in Altro.
- (0.13) Minecraft nell'edizione Java gira dentro `javaw.exe` o `java.exe` (cioè "Java") e finirebbe in Altre app. Quando il programma in primo piano è `javaw.exe` o `java.exe` e il titolo della sua finestra comincia con "Minecraft", il computer lo conta come il programma `exe:minecraft-java`, nome leggibile "Minecraft (Java)", categoria Giochi. Il titolo si legge solo per questo controllo e si butta: non viene mai salvato né scritto nei log. Per il server `exe:minecraft-java` è una chiave `exe:` come le altre, anche nelle regole (un limite su Minecraft Java è `exe:minecraft-java`).

## Telefono

### Social

| App | Nome tecnico |
|---|---|
| Instagram | `com.instagram.android` |
| Instagram Lite | `com.instagram.lite` |
| TikTok | `com.zhiliaoapp.musically` |
| TikTok (versione asiatica) | `com.ss.android.ugc.trill` |
| TikTok Lite | `com.zhiliaoapp.musically.go`, `com.ss.android.ugc.tiktok.lite`, `com.tiktok.lite.go` |
| Snapchat | `com.snapchat.android` |
| Facebook | `com.facebook.katana` |
| Facebook Lite | `com.facebook.lite` |
| X (Twitter) | `com.twitter.android` |
| Threads | `com.instagram.barcelona` |
| Reddit | `com.reddit.frontpage` |
| Pinterest | `com.pinterest` |
| Tumblr | `com.tumblr` |
| BeReal | `com.bereal.ft` |
| Discord | `com.discord` |
| LinkedIn | `com.linkedin.android` |
| Bluesky | `xyz.blueskyweb.app` |
| YouTube | `com.google.android.youtube` |
| YouTube Kids | `com.google.android.apps.youtube.kids` |
| Altri modi di guardare YouTube: ReVanced, Vanced, NewPipe | `app.revanced.android.youtube`, `com.vanced.android.youtube`, `org.schabi.newpipe` |
| Twitch | `tv.twitch.android.app` |
| Kick | `com.kick.mobile` |
| Dailymotion | `com.dailymotion.dailymotion` |
| Rumble | `com.rumble.battles` |
| Bilibili | `tv.danmaku.bili`, `com.bstar.intl` |

### Video

| App | Nome tecnico |
|---|---|
| Netflix | `com.netflix.mediaclient` |
| Prime Video | `com.amazon.avod.thirdpartyclient` |
| Disney+ | `com.disney.disneyplus` |
| RaiPlay | `it.rainet` |
| Mediaset Infinity | `it.fabbricadigitale.android.videomediaset` |
| DAZN | `com.dazn` |
| NOW | `com.nowtv.it` |
| Sky Go | `it.sky.anywhere` |
| Crunchyroll | `com.crunchyroll.crunchyroid` |
| Paramount+ | `com.cbs.app` |
| Apple TV | `com.apple.atve.androidtv.appletv` |
| Pluto TV | `tv.pluto.android` |
| HBO Max | `com.wbd.stream`, `com.wbd.hbomax` |
| Plex | `com.plexapp.android` |
| VLC | `org.videolan.vlc` |
| Vimeo | `com.vimeo.android.videoapp` |

### Musica

| App | Nome tecnico |
|---|---|
| Spotify | `com.spotify.music` |
| YouTube Music | `com.google.android.apps.youtube.music` |
| YouTube Music ReVanced | `app.revanced.android.apps.youtube.music` |
| Apple Music | `com.apple.android.music` |
| Amazon Music | `com.amazon.mp3` |
| Deezer | `deezer.android.app` |
| SoundCloud | `com.soundcloud.android` |
| Tidal | `com.aspiro.tidal` |
| Shazam | `com.shazam.android` |

### Giochi

Tutte le app che dicono da sole di essere un gioco, più queste (molti giochi non lo dicono):

| App | Nome tecnico |
|---|---|
| Brawl Stars | `com.supercell.brawlstars` |
| Clash Royale | `com.supercell.clashroyale` |
| Clash of Clans | `com.supercell.clashofclans` |
| Hay Day | `com.supercell.hayday` |
| Minecraft | `com.mojang.minecraftpe` |
| Roblox | `com.roblox.client` |
| Fortnite | `com.epicgames.fortnite` |
| Subway Surfers | `com.kiloo.subwaysurf` |
| Candy Crush Saga | `com.king.candycrushsaga` |
| Candy Crush Soda Saga | `com.king.candycrushsodasaga` |
| PUBG Mobile | `com.tencent.ig` |
| Call of Duty: Mobile | `com.activision.callofduty.shooter` |
| Genshin Impact | `com.miHoYo.GenshinImpact` |
| Honkai: Star Rail | `com.HoYoverse.hkrpgoversea` |
| Zenless Zone Zero | `com.HoYoverse.Nap` |
| Pokémon GO | `com.nianticlabs.pokemongo` |
| Among Us | `com.innersloth.spacemafia` |
| eFootball | `jp.konami.pesam` |
| EA SPORTS FC Mobile | `com.ea.gp.fifamobile` |
| Stumble Guys | `com.kitkagames.fallbuddies` |
| Free Fire, Free Fire MAX | `com.dts.freefireth`, `com.dts.freefiremax` |
| 8 Ball Pool | `com.miniclip.eightballpool` |
| Geometry Dash | `com.robtopx.geometryjump` |
| League of Legends: Wild Rift | `com.riotgames.league.wildrift` |
| Chess.com | `com.chess` |
| Lichess | `org.lichess.mobileV2`, `org.lichess.mobileapp` |
| Steam | `com.valvesoftware.android.steam.community` |
| Epic Games | `com.epicgames.portal`, `com.epicgames.ega` |
| Xbox, Xbox Game Pass | `com.microsoft.xboxone.smartglass`, `com.gamepass` |
| PlayStation App, PS Remote Play | `com.scee.psxandroid`, `com.playstation.remoteplay` |

### Sempre in Altre app

Le app di messaggi:

| App | Nome tecnico |
|---|---|
| WhatsApp, WhatsApp Business | `com.whatsapp`, `com.whatsapp.w4b` |
| Telegram, Telegram X | `org.telegram.messenger`, `org.telegram.messenger.web`, `org.thunderdog.challegram` |
| Messenger, Messenger Lite | `com.facebook.orca`, `com.facebook.mlite` |
| Signal | `org.thoughtcrime.securesms` |
| Gli SMS: Messaggi di Google, Messaggi Samsung, Messaggi di Android | `com.google.android.apps.messaging`, `com.samsung.android.messaging`, `com.android.mms`, `com.android.messaging` |
| Viber | `com.viber.voip` |
| WeChat | `com.tencent.mm` |
| Line | `jp.naver.line.android` |
| Threema | `ch.threema.app` |
| Skype | `com.skype.raider` |

I browser:

| App | Nome tecnico |
|---|---|
| Chrome (anche Beta, Dev, Canary) | `com.android.chrome`, `com.chrome.beta`, `com.chrome.dev`, `com.chrome.canary` |
| Firefox (anche Beta, Nightly, Focus, Klar) | `org.mozilla.firefox`, `org.mozilla.firefox_beta`, `org.mozilla.fenix`, `org.mozilla.focus`, `org.mozilla.klar` |
| Brave (anche Beta, Nightly) | `com.brave.browser`, `com.brave.browser_beta`, `com.brave.browser_nightly` |
| Edge (anche Beta, Dev, Canary) | `com.microsoft.emmx`, `com.microsoft.emmx.beta`, `com.microsoft.emmx.dev`, `com.microsoft.emmx.canary` |
| Opera, Opera Beta, Opera Mini, Opera GX | `com.opera.browser`, `com.opera.browser.beta`, `com.opera.mini.native`, `com.opera.gx` |
| Samsung Internet (anche Beta) | `com.sec.android.app.sbrowser`, `com.sec.android.app.sbrowser.beta` |
| DuckDuckGo | `com.duckduckgo.mobile.android` |
| Vivaldi | `com.vivaldi.browser` |
| Tor Browser | `org.torproject.torbrowser` |
| Kiwi | `com.kiwibrowser.browser` |
| Mi Browser (Xiaomi) | `com.mi.globalbrowser` |
| Huawei Browser | `com.huawei.browser` |
| UC Browser | `com.UCMobile.intl` |
| Yandex | `com.yandex.browser` |
| Ecosia | `com.ecosia.android` |

## Computer

### Programmi

**Social**: Discord (`discord.exe`, `discordptb.exe`, `discordcanary.exe`), Instagram (`instagram.exe`), Facebook (`facebook.exe`), TikTok (`tiktok.exe`), Snapchat (`snapchat.exe`), Threads (`threads.exe`), X (`x.exe`, `twitter.exe`), Reddit (`reddit.exe`), Pinterest (`pinterest.exe`), Twitch (`twitch.exe`).

**Giochi**, i negozi e i launcher: Steam (`steam.exe`, `steamwebhelper.exe`), Epic Games (`epicgameslauncher.exe`), Battle.net (`battle.net.exe`), Riot Client (`riotclientservices.exe`, `riotclientux.exe`, `riot client.exe`), EA app e Origin (`eadesktop.exe`, `origin.exe`), Ubisoft Connect (`ubisoftconnect.exe`, `upc.exe`), Xbox (`xboxpcapp.exe`), GOG Galaxy (`galaxyclient.exe`).

**Giochi**, i giochi: Minecraft (`minecraft.windows.exe`, `minecraftlauncher.exe`, `minecraft.exe`, e l'edizione Java `exe:minecraft-java`, v. sotto), Roblox (`robloxplayerbeta.exe`, `robloxplayerlauncher.exe`, `windows10universal.exe`), Fortnite (`fortniteclient-win64-shipping.exe`, `fortnitelauncher.exe`), League of Legends (`leagueclient.exe`, `leagueclientux.exe`, `league of legends.exe`), Valorant (`valorant.exe`, `valorant-win64-shipping.exe`), Overwatch (`overwatch.exe`), GTA V (`gta5.exe`, `gta5_enhanced.exe`, `playgtav.exe`), Rocket League (`rocketleague.exe`), Counter-Strike (`cs2.exe`, `csgo.exe`), Dota 2 (`dota2.exe`), Genshin Impact (`genshinimpact.exe`), Zenless Zone Zero (`zenlesszonezero.exe`), Honkai: Star Rail (`starrail.exe`), Among Us (`among us.exe`), Brawlhalla (`brawlhalla.exe`), osu! (`osu!.exe`), Terraria (`terraria.exe`), Stardew Valley (`stardew valley.exe`), EA SPORTS FC e FIFA (`fc24.exe`, `fc25.exe`, `fc26.exe`, `fifa23.exe`), NBA 2K25 (`nba2k25.exe`), Apex Legends (`r5apex.exe`, `r5apex_dx12.exe`), Call of Duty (`cod.exe`), Destiny 2 (`destiny2.exe`), PUBG (`tslgame.exe`), Rainbow Six Siege (`rainbowsix.exe`), Elden Ring (`eldenring.exe`), Geometry Dash (`geometrydash.exe`), Fall Guys (`fallguys_client_game.exe`), Marvel Rivals (`marvel-win64-shipping.exe`), Rust (`rustclient.exe`), Hollow Knight (`hollow_knight.exe`), Palworld (`palworld-win64-shipping.exe`), Helldivers 2 (`helldivers2.exe`).

**Video**: Netflix (`netflix.exe`), Prime Video (`primevideo.exe`), Disney+ (`disneyplus.exe`), Apple TV (`appletv.exe`), Crunchyroll (`crunchyroll.exe`), RaiPlay (`raiplay.exe`), Plex (`plex.exe`); i programmi per guardare video: VLC (`vlc.exe`), Film e TV (`video.ui.exe`), Lettore multimediale (`microsoft.media.player.exe`), Windows Media Player (`wmplayer.exe`), MPC-HC (`mpc-hc64.exe`, `mpc-hc.exe`), MPC-BE (`mpc-be64.exe`), PotPlayer (`potplayermini64.exe`, `potplayermini.exe`).

**Musica**: Spotify (`spotify.exe`), iTunes (`itunes.exe`), Apple Music (`applemusic.exe`), Deezer (`deezer.exe`), Tidal (`tidal.exe`), Amazon Music (`amazon music.exe`), SoundCloud (`soundcloud.exe`), YouTube Music (`youtube music.exe`), MusicBee (`musicbee.exe`), foobar2000 (`foobar2000.exe`), AIMP (`aimp.exe`).

**Sempre in Altro**:

- i browser: Chrome (`chrome.exe`), Edge (`msedge.exe`), Firefox (`firefox.exe`), Brave (`brave.exe`), Opera e Opera GX (`opera.exe`), Vivaldi (`vivaldi.exe`), DuckDuckGo (`duckduckgo.exe`), Arc (`arc.exe`), Zen (`zen.exe`), LibreWolf (`librewolf.exe`), Waterfox (`waterfox.exe`), Internet Explorer (`iexplore.exe`). Il loro tempo va nella categoria del sito aperto, se il sito è nelle liste;
- i messaggi: WhatsApp (`whatsapp.exe`, `whatsapp.root.exe`), Telegram (`telegram.exe`), Signal (`signal.exe`), Messenger (`messenger.exe`), Skype (`skype.exe`).

### Siti

Un sito vale con tutte le sue pagine e i suoi indirizzi: `www.youtube.com`, `m.youtube.com` e `youtu.be` sono tutti youtube.com, `twitter.com` è x.com, `steamcommunity.com` è steampowered.com.

**Social**: instagram.com, tiktok.com, facebook.com, x.com, reddit.com, snapchat.com, discord.com, threads.net, threads.com, pinterest.com, pinterest.it, tumblr.com, bsky.app (Bluesky), bereal.com, linkedin.com, vk.com, weibo.com, youtube.com, twitch.tv, kick.com, dailymotion.com, rumble.com, bilibili.com.

**Giochi**: roblox.com, poki.com, crazygames.com, miniclip.com, chess.com, lichess.org, steampowered.com, epicgames.com, y8.com, friv.com, coolmathgames.com, itch.io, kongregate.com, agar.io, slither.io, krunker.io, geoguessr.com, minecraft.net, gamejolt.com, armorgames.com, addictinggames.com, gamepix.com, 1001games.com.

**Video**: netflix.com, primevideo.com, disneyplus.com, raiplay.it, dazn.com, nowtv.it, crunchyroll.com, paramountplus.com, pluto.tv, la7.it, discoveryplus.com, max.com, hbomax.com, timvision.it, plex.tv, tubi.tv, vimeo.com; e solo queste pagine di siti più grandi:

- mediasetinfinity.mediaset.it (Mediaset Infinity; il resto di mediaset.it, come TgCom24, no);
- tv.apple.com (Apple TV; il resto di apple.com no).

**Musica**: spotify.com, soundcloud.com, deezer.com, tidal.com, bandcamp.com, last.fm, genius.com, shazam.com, audiomack.com; e solo queste pagine di siti più grandi:

- music.youtube.com (YouTube Music; il resto di YouTube è Social);
- music.apple.com (Apple Music; il resto di apple.com no);
- music.amazon.it e music.amazon.com (Amazon Music; il resto di Amazon no).

Nell'elenco dei siti del giorno queste pagine restano sotto il sito grande: YouTube Music si vede come youtube.com, ma i suoi minuti vanno in Musica.

**Sempre in Altro**, i siti di messaggi: whatsapp.com (anche web.whatsapp.com), telegram.org, messenger.com, signal.org.

## Come si cambia una lista

Si aggiunge o si sposta una riga nel codice: per il telefono in `app-figlio/app/src/main/java/eu/stgm/pactum/figlio/catalogo/CatalogoApp.kt`, per il computer in `pc/Pactum/Nucleo/Categorie.cs`. Poi la stessa riga in questo documento. Vale dal primo aggiornamento dopo la modifica.
