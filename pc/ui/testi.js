/*
 * Pactum per il computer: le parole e la logica pura dell'interfaccia.
 *
 * Niente DOM e niente rete qui dentro, così si prova con node
 * (module.exports) e nella pagina vive come window.PactumTesti.
 * Le frasi sono quelle dell'app del telefono (app-figlio, strings.xml),
 * adattate al computer: stessi fatti, stesse parole.
 */
(function (radice) {
  'use strict';

  // --- Numeri e parole -------------------------------------------------------

  function plurale(n, uno, molti) {
    return n === 1 ? uno : molti;
  }

  /**
   * Un numero vero, oppure null. Attenzione: Number(null) è 0, e un "nessun
   * dato" non deve mai diventare uno zero finto (contratto: null ≠ 0).
   */
  function numero(x) {
    if (x === null || x === undefined || x === '' || typeof x === 'boolean') return null;
    const n = Number(x);
    return Number.isFinite(n) ? n : null;
  }

  /** "48 min", "1 h", "1 h 15 min": le ore tonde senza " 0 min" in coda. */
  function durata(minuti) {
    const m = Math.max(0, Math.round(numero(minuti) || 0));
    if (m < 60) return m + ' min';
    if (m % 60 === 0) return m / 60 + ' h';
    return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
  }

  function visite(n) {
    const v = Math.max(0, Math.round(numero(n) || 0));
    return v + ' ' + plurale(v, 'visita', 'visite');
  }

  // --- La striscia degli 8 giorni ---------------------------------------------

  /**
   * Lo `stato` del contratto tradotto: solo "verde" e "rosso" hanno un
   * significato, tutto il resto ("grigio", un valore nuovo) è "nessun dato".
   */
  function segnale(stato) {
    if (stato === 'verde') return 'mantenuta';
    if (stato === 'rosso') return 'fuori';
    return 'nessuno';
  }

  /** (giorni mantenuti, giorni con dati): i giorni senza dati escono dal conto. */
  function contaGiorni(giorni) {
    let mantenuti = 0;
    let conDati = 0;
    for (const g of giorni || []) {
      const s = segnale(g && g.stato);
      if (s === 'nessuno') continue;
      conDati++;
      if (s === 'mantenuta') mantenuti++;
    }
    return { mantenuti, conDati };
  }

  /** "6 su 7 giorni dentro tutte le regole", o la coda che serve. */
  function fraseConteggio(giorni, coda) {
    const { mantenuti, conDati } = contaGiorni(giorni);
    if (conDati === 0) return 'Negli ultimi 8 giorni non ci sono ancora dati';
    return mantenuti + ' su ' + conDati + ' ' + plurale(conDati, 'giorno', 'giorni') + ' ' + coda;
  }

  function fraseStriscia(giorni) {
    return fraseConteggio(giorni, 'dentro tutte le regole');
  }

  /** "6 su 7" per le righe corte (dispositivi, regole). */
  function conteggioBreve(giorni) {
    const { mantenuti, conDati } = contaGiorni(giorni);
    return conDati === 0 ? 'nessun dato' : mantenuti + ' su ' + conDati;
  }

  /**
   * La riga sotto la striscia, con le STESSE frasi dell'app del genitore:
   * "Nessun giorno fuori regola · registrazione completa".
   */
  function fraseRiepilogo(riepilogo) {
    if (!riepilogo || typeof riepilogo !== 'object') return null;
    const fuori = Math.max(0, numero(riepilogo.giorni_fuori_regola) || 0);
    const interruzioni = Math.max(0, numero(riepilogo.interruzioni) || 0);
    const parteFuori = fuori === 0
      ? 'Nessun giorno fuori regola'
      : fuori + ' ' + plurale(fuori, 'giorno fuori regola', 'giorni fuori regola');
    const parteInterruzioni = interruzioni === 0
      ? 'registrazione completa'
      : interruzioni + ' ' + plurale(interruzioni, 'interruzione nella registrazione', 'interruzioni nella registrazione');
    return parteFuori + ' · ' + parteInterruzioni;
  }

  /**
   * La serie, come sul telefono (core-design, serieDiGiorni): giorni mantenuti
   * di fila contati all'indietro; se oggi non ha ancora dati si parte da ieri.
   * Serve solo di riserva: la serie vera la calcola il motore (/locale/serie).
   */
  function serieDiGiorni(giorni) {
    const lista = (giorni || []).slice();
    if (lista.length && segnale(lista[lista.length - 1].stato) === 'nessuno') lista.pop();
    let n = 0;
    for (let i = lista.length - 1; i >= 0 && segnale(lista[i].stato) === 'mantenuta'; i--) n++;
    return n;
  }

  function testoSerie(serie) {
    return serie + ' ' + plurale(serie, 'giorno di fila', 'giorni di fila');
  }

  /**
   * (v3) La riga di un dispositivo sotto la striscia del figlio, con le stesse
   * parole del telefono: "Telefono: 6 su 7", "Questo computer: ancora nessun
   * dato", "Telefono (non più collegato): 5 su 6". Contata dalla SUA striscia.
   */
  function rigaDispositivo(dispositivo, idQuesto) {
    const d = dispositivo || {};
    let nome = d.id != null && d.id === idQuesto ? 'Questo computer' : String(d.nome || '').trim() || 'Dispositivo';
    if (d.revocato === true) nome += ' (non più collegato)';
    const { mantenuti, conDati } = contaGiorni(d.striscia);
    return conDati === 0 ? nome + ': ancora nessun dato' : nome + ': ' + mantenuti + ' su ' + conDati;
  }

  // --- Regole -----------------------------------------------------------------

  const CATEGORIE = [
    { chiave: 'categoria:social', nome: 'Social', esempi: 'Discord, Instagram…' },
    { chiave: 'categoria:giochi', nome: 'Giochi', esempi: 'Steam, Minecraft, Roblox…' },
    { chiave: 'categoria:video', nome: 'Video', esempi: 'YouTube, Netflix, Twitch…' },
    { chiave: 'categoria:musica', nome: 'Musica', esempi: 'Spotify…' },
    { chiave: 'categoria:altro', nome: 'Altro', esempi: 'tutto quello che non sta nelle altre' },
  ];

  /**
   * Le app del telefono più comuni: una proposta su una regola del telefono
   * arriva col nome del pacchetto, che dal computer non si può tradurre.
   */
  const APP_TELEFONO = {
    'com.instagram.android': 'Instagram',
    'com.zhiliaoapp.musically': 'TikTok',
    'com.ss.android.ugc.trill': 'TikTok',
    'com.google.android.youtube': 'YouTube',
    'com.google.android.apps.youtube.music': 'YouTube Music',
    'com.whatsapp': 'WhatsApp',
    'com.snapchat.android': 'Snapchat',
    'com.facebook.katana': 'Facebook',
    'com.twitter.android': 'X',
    'com.reddit.frontpage': 'Reddit',
    'com.discord': 'Discord',
    'tv.twitch.android.app': 'Twitch',
    'com.spotify.music': 'Spotify',
    'com.netflix.mediaclient': 'Netflix',
    'com.amazon.avod.thirdpartyclient': 'Prime Video',
    'com.disney.disneyplus': 'Disney+',
    'com.roblox.client': 'Roblox',
    'com.mojang.minecraftpe': 'Minecraft',
    'com.supercell.clashroyale': 'Clash Royale',
    'com.supercell.brawlstars': 'Brawl Stars',
    'com.epicgames.fortnite': 'Fortnite',
    'org.telegram.messenger': 'Telegram',
    'com.pinterest': 'Pinterest',
    'com.bereal.ft': 'BeReal',
    'com.duolingo': 'Duolingo',
    'com.android.chrome': 'Chrome',
  };

  /** Il bersaglio di un limite detto per nome: "Minecraft", "youtube.com", "Social". */
  function nomeBersaglio(chiave, nomi) {
    if (chiave == null || chiave === '') return '?';
    const k = String(chiave).trim();
    const minuscola = k.toLowerCase();
    if (minuscola.startsWith('categoria:')) {
      const c = CATEGORIE.find((x) => x.chiave === minuscola);
      return c ? c.nome : k.slice('categoria:'.length);
    }
    // Un nome noto (visto dal motore, o mandato dal server con la regola) vince.
    const trovato = nomi && (typeof nomi.get === 'function' ? nomi.get(minuscola) : nomi[minuscola]);
    if (trovato) return trovato;
    if (minuscola.startsWith('exe:')) return k.slice('exe:'.length);
    if (minuscola.startsWith('sito:')) return k.slice('sito:'.length);
    return APP_TELEFONO[k] || k;
  }

  /** Che cosa è il bersaglio: programma, sito, categoria o app del telefono. */
  function tipoBersaglio(chiave) {
    const k = String(chiave || '').toLowerCase();
    if (k.startsWith('exe:')) return 'programma';
    if (k.startsWith('sito:')) return 'sito';
    if (k.startsWith('categoria:')) return 'categoria';
    return 'app';
  }

  const GIORNI = ['lun', 'mar', 'mer', 'gio', 'ven', 'sab', 'dom'];
  const GIORNI_INTERI = {
    lun: 'lunedì', mar: 'martedì', mer: 'mercoledì', gio: 'giovedì',
    ven: 'venerdì', sab: 'sabato', dom: 'domenica',
  };

  function testoGiorni(giorni) {
    const scelti = GIORNI.filter((g) => Array.isArray(giorni) && giorni.includes(g));
    if (scelti.length === 7) return 'tutti i giorni';
    const feriali = ['lun', 'mar', 'mer', 'gio', 'ven'];
    if (scelti.length === 5 && feriali.every((g) => scelti.includes(g))) return 'da lunedì a venerdì';
    if (scelti.length === 2 && scelti.includes('sab') && scelti.includes('dom')) return 'sabato e domenica';
    return scelti.length ? scelti.join(', ') : '?';
  }

  /**
   * La regola in italiano semplice, da tipo e parametri (contratto-api.md).
   * `opzioni.nomi`: i nomi leggibili dei programmi (chiave exe: → nome).
   * Le fasce dicono "telefono" o "computer" secondo il dispositivo della regola.
   */
  function descrizioneRegola(regola, opzioni) {
    const o = opzioni || {};
    const p = (regola && regola.parametri) || {};
    switch (regola && regola.tipo) {
      case 'limite_tempo':
        return nomeBersaglio(p.app_o_categoria, o.nomi) + ': al massimo ' + durata(p.minuti_al_giorno) + ' al giorno';
      case 'fascia_oraria': {
        const tipo = (regola.dispositivo && regola.dispositivo.tipo) || o.tipoDispositivo || 'computer';
        const cosa = tipo === 'telefono' ? 'telefono' : 'computer';
        return 'Niente ' + cosa + ' dalle ' + (p.dalle || '?') + ' alle ' + (p.alle || '?') + ' (' + testoGiorni(p.giorni) + ')';
      }
      case 'vita_reale':
        return (p.descrizione || '?') + ' — arbitro: ' + (p.arbitro_nome || '?') + ', ' + (p.frequenza || '?');
      default:
        return String((regola && regola.tipo) || 'Regola');
    }
  }

  function etichettaTipo(tipo) {
    return ({
      limite_tempo: 'LIMITE DI TEMPO',
      fascia_oraria: 'FASCIA ORARIA',
      vita_reale: 'VITA REALE',
    })[tipo] || String(tipo || '').toUpperCase();
  }

  /** L'ordine delle regole a schermo: tempo, fasce, vita reale; poi per id. */
  function ordinaRegole(regole) {
    const peso = { limite_tempo: 0, fascia_oraria: 1, vita_reale: 2 };
    return (regole || []).slice().sort((a, b) => {
      const pa = a.tipo in peso ? peso[a.tipo] : 3;
      const pb = b.tipo in peso ? peso[b.tipo] : 3;
      return pa - pb || (numero(a.id) || 0) - (numero(b.id) || 0);
    });
  }

  // --- Orari e fasce ------------------------------------------------------------

  function oraValida(testo) {
    return /^([01]\d|2[0-3]):[0-5]\d$/.test(String(testo || ''));
  }

  function minutiDaOra(testo) {
    const [ore, minuti] = String(testo).split(':').map(Number);
    return ore * 60 + minuti;
  }

  function inizioGiorno(d) {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
  }

  function spostaGiorni(d, n) {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  }

  function conOra(giorno, orario) {
    const [ore, minuti] = String(orario).split(':').map(Number);
    return new Date(giorno.getFullYear(), giorno.getMonth(), giorno.getDate(), ore, minuti, 0, 0);
  }

  function siglaGiorno(d) {
    return GIORNI[(d.getDay() + 6) % 7];
  }

  function minutiTra(da, a) {
    return Math.max(1, Math.floor((a - da) / 60000));
  }

  /**
   * Dove siamo rispetto a una fascia oraria, come Valutatore.momentoFascia del
   * telefono: prima (mancano N minuti all'inizio), in corso (fino a), finita
   * per oggi, oppure oggi non c'è. Le fasce che passano la mezzanotte
   * appartengono al giorno in cui partono.
   */
  function momentoFascia(parametri, adesso) {
    const p = parametri || {};
    if (!oraValida(p.dalle) || !oraValida(p.alle)) return null;
    const ora = adesso ? new Date(adesso) : new Date();
    const giorni = Array.isArray(p.giorni) ? p.giorni : [];
    const oggi = inizioGiorno(ora);
    const stessoGiorno = minutiDaOra(p.alle) > minutiDaOra(p.dalle);
    let finitaOggi = false;
    for (const ancora of [spostaGiorni(oggi, -1), oggi]) {
      if (!giorni.includes(siglaGiorno(ancora))) continue;
      const inizio = conOra(ancora, p.dalle);
      const fine = stessoGiorno ? conOra(ancora, p.alle) : conOra(spostaGiorni(ancora, 1), p.alle);
      if (ora >= inizio && ora < fine) return { tipo: 'in_corso', minuti: minutiTra(ora, fine), fine: p.alle };
      if (ancora.getTime() === oggi.getTime() && ora < inizio) {
        return { tipo: 'prima', minuti: minutiTra(ora, inizio), inizio: p.dalle };
      }
      if (fine <= ora && fine >= oggi) finitaOggi = true;
    }
    return finitaOggi ? { tipo: 'finita' } : { tipo: 'non_oggi' };
  }

  function testoMomento(momento) {
    if (!momento) return null;
    switch (momento.tipo) {
      case 'prima': return 'mancano ' + durata(momento.minuti) + ' alle ' + momento.inizio;
      case 'in_corso': return 'sei nella fascia che ti sei imposto, fino alle ' + momento.fine;
      case 'finita': return 'per oggi la fascia è finita';
      case 'non_oggi': return 'oggi la fascia non c\'è';
      default: return null;
    }
  }

  // --- Giorni e orari detti all'italiana ---------------------------------------

  function due(n) {
    return (n < 10 ? '0' : '') + n;
  }

  function isoGiorno(d) {
    return d.getFullYear() + '-' + due(d.getMonth() + 1) + '-' + due(d.getDate());
  }

  function giornoDaIso(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  function spostaIso(iso, n) {
    const d = giornoDaIso(iso);
    return d ? isoGiorno(spostaGiorni(d, n)) : iso;
  }

  /** "Oggi" nel fuso del patto (quello che usa il server per le dichiarazioni). */
  function oggiNelFuso(fuso, adesso) {
    const ora = adesso ? new Date(adesso) : new Date();
    if (fuso) {
      try {
        const parti = new Intl.DateTimeFormat('en-US', {
          timeZone: fuso, year: 'numeric', month: '2-digit', day: '2-digit',
        }).formatToParts(ora);
        const v = {};
        for (const parte of parti) v[parte.type] = parte.value;
        if (v.year && v.month && v.day) return v.year + '-' + v.month + '-' + v.day;
      } catch (e) {
        // Fuso sconosciuto: si ripiega su quello del computer.
      }
    }
    return isoGiorno(ora);
  }

  const MESI = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio',
    'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre'];
  const GIORNI_SETTIMANA = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato'];

  /** "oggi", "ieri", "18/09" (l'anno solo se non è quello di oggi). */
  function giornoBreve(iso, oggiIso) {
    const d = giornoDaIso(iso);
    const o = giornoDaIso(oggiIso) || inizioGiorno(new Date());
    if (!d) return String(iso || '');
    if (d.getTime() === o.getTime()) return 'oggi';
    if (d.getTime() === spostaGiorni(o, -1).getTime()) return 'ieri';
    return due(d.getDate()) + '/' + due(d.getMonth() + 1) + (d.getFullYear() === o.getFullYear() ? '' : '/' + d.getFullYear());
  }

  /** "Oggi", "Ieri", "Lunedì 21 settembre". */
  function giornoEsteso(iso, oggiIso) {
    const d = giornoDaIso(iso);
    const o = giornoDaIso(oggiIso) || inizioGiorno(new Date());
    if (!d) return String(iso || '');
    if (d.getTime() === o.getTime()) return 'Oggi';
    if (d.getTime() === spostaGiorni(o, -1).getTime()) return 'Ieri';
    const testo = GIORNI_SETTIMANA[d.getDay()] + ' ' + d.getDate() + ' ' + MESI[d.getMonth()] +
      (d.getFullYear() === o.getFullYear() ? '' : ' ' + d.getFullYear());
    return testo.charAt(0).toUpperCase() + testo.slice(1);
  }

  function istante(ts) {
    if (ts == null || ts === '') return null;
    const d = ts instanceof Date ? ts : new Date(ts);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  function orario(d) {
    return due(d.getHours()) + ':' + due(d.getMinutes());
  }

  function dataCorta(d, rispetto) {
    return due(d.getDate()) + '/' + due(d.getMonth() + 1) + (d.getFullYear() === rispetto.getFullYear() ? '' : '/' + d.getFullYear());
  }

  function differenzaGiorni(d, adesso) {
    return Math.round((inizioGiorno(d) - inizioGiorno(adesso)) / 86400000);
  }

  /** "alle 14:32", "ieri alle 14:32", "il 21/09 alle 14:32": l'età di un dato. */
  function quando(ts, adesso) {
    const d = istante(ts);
    if (!d) return null;
    const ora = adesso ? new Date(adesso) : new Date();
    const giorni = differenzaGiorni(d, ora);
    if (giorni === 0) return 'alle ' + orario(d);
    if (giorni === -1) return 'ieri alle ' + orario(d);
    return 'il ' + dataCorta(d, ora) + ' alle ' + orario(d);
  }

  /** "da oggi alle 14:30", "da domani alle 14:30", "dal 25/09 alle 14:30". */
  function dalQuando(ts, adesso) {
    const d = istante(ts);
    if (!d) return null;
    const ora = adesso ? new Date(adesso) : new Date();
    const giorni = differenzaGiorni(d, ora);
    if (giorni === 0) return 'da oggi alle ' + orario(d);
    if (giorni === 1) return 'da domani alle ' + orario(d);
    return 'dal ' + dataCorta(d, ora) + ' alle ' + orario(d);
  }

  /** "oggi alle 20:14", "ieri alle 20:14", "21/09 alle 20:14". */
  function dataOraBreve(ts, adesso) {
    const d = istante(ts);
    if (!d) return null;
    const ora = adesso ? new Date(adesso) : new Date();
    const giorni = differenzaGiorni(d, ora);
    if (giorni === 0) return 'oggi alle ' + orario(d);
    if (giorni === -1) return 'ieri alle ' + orario(d);
    return dataCorta(d, ora) + ' alle ' + orario(d);
  }

  /** Un'attesa in parole dai secondi del 409 di blocco: "3 giorni e 4 ore". */
  function testoAttesa(secondi) {
    const totali = Math.max(1, Math.ceil((numero(secondi) || 0) / 60));
    const giorni = Math.floor(totali / 1440);
    const ore = Math.floor((totali % 1440) / 60);
    const minuti = totali % 60;
    const g = giorni === 1 ? 'un giorno' : giorni + ' giorni';
    const o = (n) => (n === 1 ? 'un\'ora' : n + ' ore');
    const m = (n) => (n === 1 ? 'un minuto' : n + ' minuti');
    if (giorni > 0 && ore > 0) return g + ' e ' + o(ore);
    if (giorni > 0) return g;
    if (ore > 0 && minuti > 0) return o(ore) + ' e ' + m(minuti);
    if (ore > 0) return o(ore);
    return m(minuti);
  }

  // --- Siti: dal testo scritto al nome del sito -----------------------------------
  //
  // Le stesse regole del telefono (app-figlio, siti/Domini.kt): si tiene il
  // dominio registrabile in minuscolo, con i suffissi a due livelli e gli
  // alias dei marchi. Se qualcuno incolla un indirizzo intero, di lui resta
  // solo il nome del sito: il resto non si mostra e non si salva.

  const SUFFISSI_DOPPI = new Set([
    'co.uk', 'org.uk', 'ac.uk', 'gov.uk', 'me.uk', 'net.uk', 'sch.uk', 'ltd.uk', 'plc.uk',
    'com.au', 'net.au', 'org.au', 'edu.au', 'gov.au', 'id.au',
    'co.nz', 'net.nz', 'org.nz', 'ac.nz', 'govt.nz',
    'com.br', 'net.br', 'org.br', 'gov.br', 'edu.br',
    'com.ar', 'com.mx', 'com.co', 'com.pe', 'com.uy', 'com.ve', 'com.ec',
    'co.jp', 'ne.jp', 'or.jp', 'ac.jp', 'go.jp',
    'co.kr', 'or.kr', 'ne.kr',
    'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn',
    'com.tw', 'com.hk', 'com.sg', 'com.my', 'com.ph', 'com.vn',
    'co.in', 'net.in', 'org.in', 'co.id', 'or.id',
    'co.il', 'com.tr', 'com.ua', 'com.ru', 'com.pl', 'com.es', 'com.pt',
    'co.za', 'com.sa', 'com.eg', 'gov.it', 'edu.it',
  ]);

  const ALIAS = {
    'cdninstagram.com': 'instagram.com', 'fbcdn.net': 'facebook.com', 'facebook.net': 'facebook.com',
    'fbsbx.com': 'facebook.com', 'whatsapp.net': 'whatsapp.com', 'ytimg.com': 'youtube.com',
    'googlevideo.com': 'youtube.com', 'youtu.be': 'youtube.com', 'youtube-nocookie.com': 'youtube.com',
    'tiktokcdn.com': 'tiktok.com', 'tiktokcdn-us.com': 'tiktok.com', 'tiktokv.com': 'tiktok.com',
    'byteoversea.com': 'tiktok.com', 'ibytedtos.com': 'tiktok.com', 'twimg.com': 'x.com', 't.co': 'x.com',
    'twitter.com': 'x.com', 'redd.it': 'reddit.com', 'redditmedia.com': 'reddit.com',
    'redditstatic.com': 'reddit.com', 'discordapp.com': 'discord.com', 'discordapp.net': 'discord.com',
    'discord.media': 'discord.com', 'discord.gg': 'discord.com', 'scdn.co': 'spotify.com',
    'spotifycdn.com': 'spotify.com', 'licdn.com': 'linkedin.com', 'pinimg.com': 'pinterest.com',
    'sc-cdn.net': 'snapchat.com', 'snapchat.net': 'snapchat.com', 'snap.com': 'snapchat.com',
    'ttvnw.net': 'twitch.tv', 'jtvnw.net': 'twitch.tv', 'twitchcdn.net': 'twitch.tv',
    'nflxvideo.net': 'netflix.com', 'nflxso.net': 'netflix.com', 'nflximg.net': 'netflix.com',
    'media-amazon.com': 'amazon.com', 'ssl-images-amazon.com': 'amazon.com', 'primevideo.com': 'amazon.com',
    'rbxcdn.com': 'roblox.com', 'steamstatic.com': 'steampowered.com', 'steamcontent.com': 'steampowered.com',
    'steamcommunity.com': 'steampowered.com', 'epicgames.dev': 'epicgames.com',
    'cdn-telegram.org': 'telegram.org', 't.me': 'telegram.org', 'telegram.me': 'telegram.org',
    'wp.com': 'wordpress.com', 'wikimedia.org': 'wikipedia.org', 'githubusercontent.com': 'github.com',
    'githubassets.com': 'github.com', 'openai.com': 'chatgpt.com', 'oaistatic.com': 'chatgpt.com',
    'shopifycdn.com': 'shopify.com', 'pstatic.net': 'naver.com',
  };

  const FUORI_INTERNET = ['.local', '.lan', '.home', '.internal', '.localdomain', '.arpa',
    '.onion', '.test', '.invalid', '.example', '.localhost'];

  /**
   * Il pezzo di testo che può essere un nome di sito: toglie lo schema
   * ("https://"), tutto quello che viene dopo il nome (percorso, parametri,
   * frammento), le credenziali e la porta. Serve anche mentre si scrive.
   */
  function ripulisciIndirizzo(testo) {
    let s = String(testo || '').trim();
    const schema = s.indexOf('://');
    if (schema >= 0) s = s.slice(schema + 3);
    s = s.split(/[/?#\\\s]/)[0];
    const chiocciola = s.lastIndexOf('@');
    if (chiocciola >= 0) s = s.slice(chiocciola + 1);
    return s.replace(/:\d*$/, '');
  }

  /** Il nome del sito (dominio registrabile) da un testo qualsiasi, o null. */
  function dominioDaTesto(testo) {
    let host = ripulisciIndirizzo(testo).toLowerCase().replace(/\.+$/, '');
    if (!host) return null;
    if (typeof URL === 'function' && /[^\x00-\x7f]/.test(host)) {
      try {
        host = new URL('http://' + host).hostname;
      } catch (e) {
        return null;
      }
    }
    if (host.length > 253 || !host.includes('.') || host.includes('..')) return null;
    if (!/^[a-z0-9._-]+$/.test(host)) return null;
    if (/^[\d.]+$/.test(host)) return null;
    if (FUORI_INTERNET.some((s) => host.endsWith(s))) return null;
    const parti = host.split('.').filter(Boolean);
    if (parti.length < 2) return null;
    const ultima = parti[parti.length - 1];
    if (!/^([a-z]{2,63}|xn--[a-z0-9-]+)$/.test(ultima)) return null;
    let registrabile = parti.slice(-2).join('.');
    if (SUFFISSI_DOPPI.has(registrabile)) {
      if (parti.length < 3) return null;
      registrabile = parti.slice(-3).join('.');
    }
    return ALIAS[registrabile] || registrabile;
  }

  /**
   * L'indirizzo del server come lo vuole il motore: schema, nome, porta ed
   * eventuale percorso; niente credenziali, parametri o frammenti. Senza
   * schema si intende https. null se non è un indirizzo.
   */
  function normalizzaServer(testo) {
    const s = String(testo || '').trim();
    if (!s || /\s/.test(s)) return null;
    const conSchema = s.includes('://') ? s : 'https://' + s;
    let u;
    try {
      u = new URL(conSchema);
    } catch (e) {
      return null;
    }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
    if (!u.hostname) return null;
    return u.protocol + '//' + u.host + u.pathname.replace(/\/+$/, '');
  }

  /** Solo il nome del server, per mostrarlo: "pactum.esempio.it". */
  function nomeServer(indirizzo) {
    if (!indirizzo) return null;
    try {
      return new URL(String(indirizzo)).host || null;
    } catch (e) {
      return ripulisciIndirizzo(indirizzo) || null;
    }
  }

  // --- Risposte del server ----------------------------------------------------------

  /**
   * Il dettaglio di un errore del server: FastAPI lo mette in {"detail": {...}},
   * il motore a volte lo gira così com'è. null se non c'è un `errore` leggibile
   * (per esempio la lista dei 422).
   */
  function erroreDi(dati) {
    if (!dati || typeof dati !== 'object') return null;
    const d = dati.detail && typeof dati.detail === 'object' && !Array.isArray(dati.detail) ? dati.detail : dati;
    return typeof d.errore === 'string' ? d : null;
  }

  /** Uguaglianza fra valori JSON (parametri di una regola). */
  function uguali(a, b) {
    if (a === b) return true;
    if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && uguali(a[k], b[k]));
  }

  // --- Dichiarazioni e proposte ------------------------------------------------------

  /** Lo stato di una dichiarazione raccontato dal punto di vista del figlio. */
  function testoStatoDichiarazione(dichiarazione, arbitro) {
    const chi = arbitro || '?';
    switch (dichiarazione && dichiarazione.stato) {
      case 'in_attesa': return 'L\'hai fatto. Manca la firma di ' + chi + '.';
      case 'registrata': return 'Non riuscito — creduto sulla parola';
      case 'confermata': return 'Successo confermato';
      case 'confermata_per_conto': return 'Confermato dal genitore per conto di ' + chi;
      case 'ribaltata': return 'Non confermata — conta come non riuscito';
      default: return String((dichiarazione && dichiarazione.stato) || '');
    }
  }

  function eEliminazione(proposta) {
    if (!proposta) return false;
    const p = proposta.parametri_proposti;
    return proposta.direzione === 'elimina' || Boolean(p && typeof p === 'object' && p.azione === 'elimina');
  }

  function etichettaDirezione(direzione) {
    return ({ stringe: 'stringe', allenta: 'allenta', elimina: 'eliminazione' })[direzione] || null;
  }

  function etichettaStatoProposta(stato) {
    return ({
      pendente: 'In attesa',
      accettata: 'Accettata',
      rifiutata: 'Rifiutata',
      annullata: 'Annullata',
    })[stato] || String(stato || '');
  }

  const T = {
    plurale, numero, durata, visite,
    segnale, contaGiorni, fraseConteggio, fraseStriscia, conteggioBreve, fraseRiepilogo,
    serieDiGiorni, testoSerie, rigaDispositivo,
    CATEGORIE, APP_TELEFONO, nomeBersaglio, tipoBersaglio,
    GIORNI, GIORNI_INTERI, testoGiorni, descrizioneRegola, etichettaTipo, ordinaRegole,
    oraValida, momentoFascia, testoMomento,
    isoGiorno, giornoDaIso, spostaIso, oggiNelFuso, giornoBreve, giornoEsteso,
    istante, orario, quando, dalQuando, dataOraBreve, testoAttesa,
    ripulisciIndirizzo, dominioDaTesto, normalizzaServer, nomeServer,
    erroreDi, uguali,
    testoStatoDichiarazione, eEliminazione, etichettaDirezione, etichettaStatoProposta,
  };

  if (typeof module === 'object' && module.exports) module.exports = T;
  else radice.PactumTesti = T;
})(typeof window !== 'undefined' ? window : globalThis);
