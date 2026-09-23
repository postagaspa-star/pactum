/*
 * Pactum per il computer: il FINTO motore della modalità di prova.
 *
 * Serve a guardare l'interfaccia in un browser qualsiasi, senza il motore:
 * risponde a /locale/* e /server/* con la stessa forma del motore vero
 * (docs/pc-programma.md) e del server (docs/contratto-api.md, v3), con dati
 * finti ma realistici. Tiene tutto in memoria: ricaricando la pagina si
 * riparte da capo, e niente di quello che succede qui arriva a un server.
 *
 * Si carica SOLO in modalità di prova (lo decide app.js). Come si usa, in
 * fondo all'indirizzo della pagina:
 *   ?prova=1              dati finti, computer già collegato
 *   ?prova=1&collega=1    si parte da "Collega questo computer":
 *                         123456 va bene, 000000 non è valido, 999999 fa
 *                         scattare "troppi tentativi"; un indirizzo con
 *                         "offline" dentro fa finta che manchi la rete
 *   ?prova=1&rete=0       il server non risponde fin dall'inizio
 *   ?prova=1&revocato=1   il genitore ha tolto il computer dalla sua app: come
 *                         il motore vero, "abbinato" diventa false ma il
 *                         server resta ricordato (si riparte da "Collega")
 * Nella barra a sinistra c'è anche l'interruttore "Simula rete assente".
 */
(function (radice) {
  'use strict';

  const T = radice.PactumTesti;
  const MINUTI_GIORNO = 1440;
  const opzioni = { abbinato: true, rete: true, revocato: false };
  let S = null;

  // --- Attrezzi ------------------------------------------------------------------

  function copia(x) {
    return x === undefined ? null : JSON.parse(JSON.stringify(x));
  }

  function isoTs(d) {
    return new Date(d).toISOString().replace(/\.\d{3}Z$/, '+00:00');
  }

  function traMinuti(n) {
    return new Date(Date.now() + n * 60000);
  }

  function risposta(stato, dati) {
    return { stato, dati: copia(dati) };
  }

  /** Come FastAPI: gli errori del server stanno in {"detail": ...}. */
  function errore(stato, dettaglio) {
    return risposta(stato, { detail: dettaglio });
  }

  function attesa(ms) {
    return new Promise((fatto) => setTimeout(fatto, ms));
  }

  // --- I dati di partenza ---------------------------------------------------------
  //
  // Un figlio (Andrea) con un telefono e questo computer. Sul computer: un
  // limite su Minecraft (stretto due giorni fa, quindi ancora bloccato per
  // allentarlo), uno su youtube.com, una fascia 22:00–07:00; per il figlio,
  // la camminata con la nonna come arbitro. Una proposta del genitore in
  // attesa. Otto giorni di striscia: uno fuori regola, uno senza dati.

  function costruisci() {
    const oggi = T.isoGiorno(new Date());
    const g = (n) => T.spostaIso(oggi, -n);
    const giorni = [7, 6, 5, 4, 3, 2, 1, 0].map(g);
    const V = 'verde';
    const R = 'rosso';
    const G = 'grigio';
    const semaforo = (...stati) => giorni.map((data, i) => ({ data, stato: stati[i] }));

    const telefono = { id: 1, nome: 'Telefono', tipo: 'telefono' };
    const computer = { id: 2, nome: 'Computer di camera', tipo: 'computer' };

    const regola = (id, tipo, parametri, dispositivo, creataMin, modificaMin, luci) => ({
      id,
      tipo,
      parametri,
      attiva: true,
      dispositivo_id: dispositivo ? dispositivo.id : null,
      dispositivo: dispositivo ? Object.assign({}, dispositivo) : null,
      creata_ts: isoTs(traMinuti(creataMin)),
      ultima_modifica_ts: isoTs(traMinuti(modificaMin)),
      allentabile_dal: isoTs(traMinuti(modificaMin + 4 * MINUTI_GIORNO)),
      semaforo: luci,
    });

    const tuttiIGiorni = ['lun', 'mar', 'mer', 'gio', 'ven', 'sab', 'dom'];
    const regole = [
      regola(12, 'limite_tempo', { app_o_categoria: 'exe:minecraft.exe', minuti_al_giorno: 60 }, computer,
        -20 * MINUTI_GIORNO, -2 * MINUTI_GIORNO - 200, semaforo(V, R, V, G, V, V, V, V)),
      regola(13, 'fascia_oraria', { dalle: '22:00', alle: '07:00', giorni: tuttiIGiorni }, computer,
        -20 * MINUTI_GIORNO, -20 * MINUTI_GIORNO, semaforo(V, V, V, G, V, V, V, V)),
      regola(14, 'limite_tempo', { app_o_categoria: 'sito:youtube.com', minuti_al_giorno: 45 }, computer,
        -10 * MINUTI_GIORNO, -10 * MINUTI_GIORNO, semaforo(V, V, V, G, V, V, V, V)),
      regola(15, 'vita_reale', { descrizione: 'Camminare un\'ora', arbitro_nome: 'Nonna Lucia', frequenza: 'ogni giorno' }, null,
        -15 * MINUTI_GIORNO, -15 * MINUTI_GIORNO, semaforo(G, R, G, G, V, G, V, G)),
      // Le regole del telefono: il computer non le mostra fra le sue, ma servono
      // per raccontare le proposte e per il vincolo "almeno una regola".
      regola(3, 'limite_tempo', { app_o_categoria: 'com.instagram.android', minuti_al_giorno: 60 }, telefono,
        -60 * MINUTI_GIORNO, -9 * MINUTI_GIORNO, semaforo(V, V, V, G, V, V, V, V)),
      regola(4, 'fascia_oraria', { dalle: '23:00', alle: '07:00', giorni: ['dom', 'lun', 'mar', 'mer', 'gio'] }, telefono,
        -60 * MINUTI_GIORNO, -60 * MINUTI_GIORNO, semaforo(V, V, V, G, V, V, V, V)),
    ];

    const sito = (dominio, minuti, visite) => ({ dominio, minuti, visite });
    const giornoSiti = (giorno, domini, dnsCifrato) => ({
      giorno,
      totale_domini: domini.length,
      dns_cifrato: Boolean(dnsCifrato),
      aggiornato_ts: isoTs(giorno === oggi ? traMinuti(-4) : new Date(T.giornoDaIso(giorno).getTime() + 23 * 3600000)),
      domini: domini.slice().sort((a, b) => b.minuti - a.minuti || a.dominio.localeCompare(b.dominio)),
    });

    S = {
      abbinato: opzioni.abbinato && !opzioni.revocato,
      // Revocato: il motore vero ricorda il server anche quando il token non vale più.
      server: opzioni.abbinato || opzioni.revocato ? 'https://pactum.esempio.it' : null,
      figlio: { id: 1, nome: 'Andrea' },
      telefono,
      computer,
      ultimoId: 40,
      record: 9,
      interruzioni: 1,
      ultimoInvio: traMinuti(-3),
      pattoAggiornato: traMinuti(-2),
      bloccoFino: 0,
      giorni,
      regole,
      bonus: {
        giorno: { usati: 15, tetto: 30, residui: 15 },
        settimana: { usati: 40, tetto: 90, residui: 50 },
      },
      bonusOggi: { 12: 15 },
      proposte: [
        {
          id: 21, regola_id: 12,
          parametri_proposti: { app_o_categoria: 'exe:minecraft.exe', minuti_al_giorno: 45 },
          motivazione: 'Questa settimana hai tre verifiche: che ne dici di stare un po\' più corto?',
          confronto: '−15 min al giorno rispetto ad ora', direzione: 'stringe',
          stato: 'pendente', usata: false, ts_server: isoTs(traMinuti(-22 * 60)), risposta: null,
        },
        {
          id: 18, regola_id: 3,
          parametri_proposti: { app_o_categoria: 'com.instagram.android', minuti_al_giorno: 60 },
          motivazione: 'Per il gruppo della classe 45 minuti erano pochi.',
          confronto: '+15 min al giorno rispetto ad ora', direzione: 'allenta',
          stato: 'accettata', usata: true, ts_server: isoTs(traMinuti(-9 * MINUTI_GIORNO - 90)),
          risposta: { esito: 'accetta', motivazione: 'Grazie, così mi organizzo meglio.', ts_server: isoTs(traMinuti(-9 * MINUTI_GIORNO - 30)) },
        },
        {
          id: 16, regola_id: 13,
          parametri_proposti: { dalle: '21:30', alle: '07:00', giorni: tuttiIGiorni },
          motivazione: 'La sera al computer si fa tardi.',
          confronto: '+30 min di fascia al giorno rispetto ad ora', direzione: 'stringe',
          stato: 'rifiutata', usata: false, ts_server: isoTs(traMinuti(-14 * MINUTI_GIORNO - 300)),
          risposta: { esito: 'rifiuta', motivazione: 'Alle 21:30 spesso sto ancora finendo i compiti.', ts_server: isoTs(traMinuti(-14 * MINUTI_GIORNO - 200)) },
        },
      ],
      dichiarazioni: [
        {
          id: 31, regola_id: 15, giorno: g(1), esito: 'successo', nota: 'Giro fino al parco con la nonna',
          stato: 'confermata', ts_server: isoTs(traMinuti(-MINUTI_GIORNO - 120)),
          verdetto: { verdetto: 'conferma', nota: null, registro: 'confermato dal genitore', ts_server: isoTs(traMinuti(-MINUTI_GIORNO + 60)) },
        },
        {
          id: 29, regola_id: 15, giorno: g(3), esito: 'successo', nota: null,
          stato: 'confermata_per_conto', ts_server: isoTs(traMinuti(-3 * MINUTI_GIORNO - 100)),
          verdetto: {
            verdetto: 'conferma_per_conto', nota: 'Ho sentito la nonna al telefono',
            registro: 'confermato dal genitore per conto di Nonna Lucia', ts_server: isoTs(traMinuti(-3 * MINUTI_GIORNO + 30)),
          },
        },
        {
          id: 26, regola_id: 15, giorno: g(6), esito: 'fallimento', nota: 'Pioveva forte e non sono uscito',
          stato: 'registrata', ts_server: isoTs(traMinuti(-6 * MINUTI_GIORNO - 60)), verdetto: null,
        },
      ],
      siti: [
        giornoSiti(g(7), [sito('youtube.com', 44, 9), sito('chess.com', 22, 5), sito('google.com', 14, 11),
          sito('twitch.tv', 12, 2), sito('spaggiari.eu', 8, 3), sito('wikipedia.org', 6, 4)]),
        giornoSiti(g(6), [sito('youtube.com', 43, 8), sito('planetminecraft.com', 31, 6), sito('twitch.tv', 18, 3),
          sito('curseforge.com', 12, 4), sito('google.com', 10, 9), sito('reddit.com', 9, 4), sito('spaggiari.eu', 5, 2),
          sito('instagram.com', 4, 3), sito('wikipedia.org', 4, 3)]),
        giornoSiti(g(5), [sito('youtube.com', 39, 6), sito('google.com', 16, 13), sito('chess.com', 15, 4),
          sito('treccani.it', 7, 3), sito('spaggiari.eu', 6, 2)]),
        { giorno: g(4), totale_domini: null, dns_cifrato: false, aggiornato_ts: null, domini: [] },
        giornoSiti(g(3), [sito('youtube.com', 41, 7), sito('google.com', 13, 12), sito('wikipedia.org', 11, 6),
          sito('spaggiari.eu', 9, 4), sito('zanichelli.it', 8, 2), sito('chess.com', 6, 2), sito('instagram.com', 3, 2)]),
        giornoSiti(g(2), [sito('youtube.com', 36, 6), sito('google.com', 12, 9), sito('twitch.tv', 10, 2),
          sito('spaggiari.eu', 7, 3), sito('wikipedia.org', 5, 3), sito('reddit.com', 4, 2)], true),
        giornoSiti(g(1), [sito('youtube.com', 45, 8), sito('google.com', 15, 14), sito('planetminecraft.com', 14, 4),
          sito('spaggiari.eu', 8, 3), sito('wikipedia.org', 7, 4), sito('treccani.it', 4, 2), sito('chess.com', 4, 1),
          sito('instagram.com', 2, 2)]),
        giornoSiti(g(0), [sito('youtube.com', 38, 6), sito('google.com', 11, 10), sito('spaggiari.eu', 7, 3),
          sito('wikipedia.org', 5, 3), sito('planetminecraft.com', 3, 2)]),
      ],
      oggi: {
        giorno: oggi,
        totale_minuti: 131,
        programmi: [
          { chiave: 'exe:chrome.exe', nome: 'Google Chrome', categoria: 'altro', minuti: 70 },
          { chiave: 'exe:minecraft.exe', nome: 'Minecraft', categoria: 'giochi', minuti: 48 },
          { chiave: 'exe:discord.exe', nome: 'Discord', categoria: 'social', minuti: 9 },
          { chiave: 'exe:winword.exe', nome: 'Microsoft Word', categoria: 'altro', minuti: 4 },
        ],
        siti: [
          { dominio: 'youtube.com', minuti: 42, visite: 7 },
          { dominio: 'google.com', minuti: 12, visite: 11 },
          { dominio: 'spaggiari.eu', minuti: 7, visite: 3 },
          { dominio: 'wikipedia.org', minuti: 5, visite: 3 },
          { dominio: 'planetminecraft.com', minuti: 3, visite: 2 },
          { dominio: 'treccani.it', minuti: 1, visite: 1 },
        ],
        regole: {
          12: { minuti: 48, limite_efficace: 75, oltre: 0 },
          14: { minuti: 42, limite_efficace: 45, oltre: 0 },
        },
        siti_non_leggibili: false,
      },
      visti: {
        programmi: [
          { chiave: 'exe:chrome.exe', nome: 'Google Chrome' },
          { chiave: 'exe:minecraft.exe', nome: 'Minecraft' },
          { chiave: 'exe:discord.exe', nome: 'Discord' },
          { chiave: 'exe:winword.exe', nome: 'Microsoft Word' },
          { chiave: 'exe:steam.exe', nome: 'Steam' },
          { chiave: 'exe:spotify.exe', nome: 'Spotify' },
          { chiave: 'exe:msedge.exe', nome: 'Microsoft Edge' },
          { chiave: 'exe:explorer.exe', nome: 'Esplora file' },
          { chiave: 'exe:robloxplayerbeta.exe', nome: 'Roblox' },
          { chiave: 'exe:obs64.exe', nome: 'OBS Studio' },
          { chiave: 'exe:geogebra.exe', nome: 'GeoGebra' },
        ],
        siti: ['youtube.com', 'google.com', 'spaggiari.eu', 'wikipedia.org', 'planetminecraft.com', 'treccani.it',
          'chess.com', 'twitch.tv', 'reddit.com', 'instagram.com', 'curseforge.com', 'zanichelli.it'],
      },
    };
  }

  // --- Le strisce, ricavate dai semafori come fa il server ---------------------------

  function aggrega(regole) {
    return S.giorni.map((data, i) => {
      const stati = regole.map((r) => (r.semaforo[i] || {}).stato);
      const stato = stati.includes('rosso') ? 'rosso' : stati.includes('verde') ? 'verde' : 'grigio';
      return { data, stato };
    });
  }

  function striscia() {
    return aggrega(S.regole);
  }

  function strisciaDi(dispositivo) {
    return aggrega(S.regole.filter((r) => r.dispositivo && r.dispositivo.id === dispositivo.id));
  }

  function segnaGiorno(regola, giorno, stato) {
    const voce = regola.semaforo.find((x) => x.data === giorno);
    if (voce) voce.stato = stato;
  }

  function pubblica(regola) {
    return copia(regola);
  }

  // --- Il lock dei 4 giorni (come server/app/lock.py) ----------------------------------

  function copertura(dalle, alle) {
    const a = minuti(dalle);
    const b = minuti(alle);
    const set = new Set();
    if (a === b) return set;
    if (a < b) for (let m = a; m < b; m++) set.add(m);
    else {
      for (let m = a; m < 1440; m++) set.add(m);
      for (let m = 0; m < b; m++) set.add(m);
    }
    return set;
  }

  function minuti(orario) {
    const [o, m] = String(orario).split(':').map(Number);
    return o * 60 + m;
  }

  function allenta(tipo, prima, dopo) {
    if (tipo === 'limite_tempo') {
      if (prima.app_o_categoria !== dopo.app_o_categoria) return true;
      return dopo.minuti_al_giorno > prima.minuti_al_giorno;
    }
    if (tipo === 'fascia_oraria') {
      const vecchia = copertura(prima.dalle, prima.alle);
      const nuova = copertura(dopo.dalle, dopo.alle);
      const copreTutto = [...vecchia].every((m) => nuova.has(m));
      const giorniOk = prima.giorni.every((g) => dopo.giorni.includes(g));
      return !(copreTutto && giorniOk);
    }
    return true;
  }

  function bloccata(regola) {
    const sblocco = new Date(regola.allentabile_dal).getTime();
    if (Date.now() >= sblocco) return null;
    return errore(409, {
      errore: 'lock_attivo',
      secondi_rimanenti: Math.floor((sblocco - Date.now()) / 1000),
      sblocco_ts: regola.allentabile_dal,
    });
  }

  function tocca(regola) {
    const adesso = new Date();
    regola.ultima_modifica_ts = isoTs(adesso);
    regola.allentabile_dal = isoTs(new Date(adesso.getTime() + 4 * 86400000));
  }

  // --- Validazione come il server (422) ------------------------------------------------

  function valida(tipo, parametri) {
    const p = parametri || {};
    const no = (campo, msg) => ({ errori: [{ loc: ['body', 'parametri', campo], msg }] });
    if (tipo === 'limite_tempo') {
      const k = String(p.app_o_categoria || '').trim().toLowerCase();
      const buona = /^exe:[a-z0-9 ._()+-]+\.exe$/.test(k) ||
        /^sito:[a-z0-9.-]+\.[a-z0-9-]{2,}$/.test(k) ||
        /^categoria:(social|giochi|video|musica|altro)$/.test(k);
      if (!buona) return no('app_o_categoria', 'chiave non valida per un computer');
      const m = Number(p.minuti_al_giorno);
      if (!Number.isInteger(m) || m < 1 || m > 1440) return no('minuti_al_giorno', 'minuti non validi');
      return { parametri: { app_o_categoria: k, minuti_al_giorno: m } };
    }
    if (tipo === 'fascia_oraria') {
      if (!T.oraValida(p.dalle) || !T.oraValida(p.alle)) return no('dalle', 'orario non valido');
      const scelti = Array.isArray(p.giorni) ? p.giorni : [];
      const giorni = T.GIORNI.filter((g) => scelti.includes(g));
      if (!giorni.length || giorni.length !== scelti.length) return no('giorni', 'giorni non validi');
      return { parametri: { dalle: p.dalle, alle: p.alle, giorni } };
    }
    if (tipo === 'vita_reale') {
      const d = String(p.descrizione || '').trim();
      const a = String(p.arbitro_nome || '').trim();
      const f = String(p.frequenza || '').trim();
      if (!d || !a || !f) return no('descrizione', 'campi mancanti');
      return { parametri: { descrizione: d, arbitro_nome: a, frequenza: f } };
    }
    return { errori: [{ loc: ['body', 'tipo'], msg: 'tipo non valido' }] };
  }

  // --- La misura di oggi di una regola (come la darebbe il motore) ----------------------

  function misura(regola) {
    if (regola.tipo !== 'limite_tempo' || !regola.attiva) {
      delete S.oggi.regole[regola.id];
      return;
    }
    const k = regola.parametri.app_o_categoria;
    let minutiOggi = 0;
    if (k.startsWith('exe:')) {
      minutiOggi = S.oggi.programmi.filter((p) => p.chiave === k).reduce((s, p) => s + p.minuti, 0);
    } else if (k.startsWith('sito:')) {
      minutiOggi = S.oggi.siti.filter((s) => s.dominio === k.slice(5)).reduce((s, x) => s + x.minuti, 0);
    } else {
      const categoria = k.slice('categoria:'.length);
      const siti = { 'youtube.com': 'video', 'instagram.com': 'social', 'twitch.tv': 'video', 'reddit.com': 'social' };
      minutiOggi = S.oggi.programmi.filter((p) => p.categoria === categoria && p.chiave !== 'exe:chrome.exe')
        .reduce((s, p) => s + p.minuti, 0) +
        S.oggi.siti.filter((s) => siti[s.dominio] === categoria).reduce((s, x) => s + x.minuti, 0);
    }
    const limite = regola.parametri.minuti_al_giorno + (S.bonusOggi[regola.id] || 0);
    S.oggi.regole[regola.id] = { minuti: minutiOggi, limite_efficace: limite, oltre: Math.max(0, minutiOggi - limite) };
  }

  // --- Il confronto delle proposte pendenti, ricalcolato a ogni lettura -----------------

  function aggiornaConfronto(proposta) {
    if (proposta.stato !== 'pendente') return proposta;
    const regola = S.regole.find((r) => r.id === proposta.regola_id);
    if (!regola) return proposta;
    if (T.eEliminazione(proposta)) {
      proposta.confronto = 'propone di eliminare la regola';
      proposta.direzione = 'elimina';
    } else if (regola.tipo === 'limite_tempo') {
      const diff = proposta.parametri_proposti.minuti_al_giorno - regola.parametri.minuti_al_giorno;
      const cambiaBersaglio = proposta.parametri_proposti.app_o_categoria !== regola.parametri.app_o_categoria;
      proposta.confronto = diff === 0 ? 'stesso limite di adesso' : (diff > 0 ? '+' : '−') + Math.abs(diff) + ' min al giorno rispetto ad ora';
      proposta.direzione = cambiaBersaglio || diff > 0 ? 'allenta' : 'stringe';
    }
    return proposta;
  }

  // --- /locale/* --------------------------------------------------------------------------

  function statoMotore() {
    return {
      abbinato: S.abbinato,
      server: S.server,
      figlio: S.abbinato ? S.figlio : null,
      dispositivo: S.abbinato ? S.computer : null,
      versione: '0.8.0',
      ultimo_invio_ok: S.abbinato ? isoTs(S.ultimoInvio) : null,
      rete_ok: opzioni.rete,
      patto_aggiornato: S.abbinato ? isoTs(S.pattoAggiornato) : null,
    };
  }

  function oggiLocale() {
    const o = copia(S.oggi);
    o.fasce = {};
    for (const r of S.regole) {
      if (!r.attiva || r.tipo !== 'fascia_oraria' || !r.dispositivo || r.dispositivo.id !== S.computer.id) continue;
      const momento = T.momentoFascia(r.parametri, new Date());
      o.fasce[r.id] = {
        attiva_ora: Boolean(momento && momento.tipo === 'in_corso'),
        prossimo_inizio: r.parametri.dalle,
        fine: r.parametri.alle,
      };
    }
    return o;
  }

  function abbina(corpo) {
    const server = T.normalizzaServer(corpo && corpo.server);
    const codice = String((corpo && corpo.codice) || '').trim();
    const nome = server ? T.nomeServer(server) || '' : '';
    if (!server || (!nome.includes('.') && !nome.startsWith('localhost'))) {
      return risposta(200, { ok: false, errore: 'indirizzo_non_valido' });
    }
    if (!opzioni.rete || /offline/i.test(server)) return risposta(200, { ok: false, errore: 'rete' });
    if (S.bloccoFino && Date.now() < S.bloccoFino) {
      return risposta(200, { ok: false, errore: 'troppi_tentativi', riprova_tra_secondi: Math.ceil((S.bloccoFino - Date.now()) / 1000) });
    }
    if (codice === '999999') {
      S.bloccoFino = Date.now() + 10 * 60000;
      return risposta(200, { ok: false, errore: 'troppi_tentativi', riprova_tra_secondi: 600 });
    }
    if (codice !== '123456') return risposta(200, { ok: false, errore: 'codice_non_valido' });
    S.abbinato = true;
    S.server = server;
    S.ultimoInvio = new Date();
    S.pattoAggiornato = new Date();
    return risposta(200, { ok: true, figlio: S.figlio, dispositivo: S.computer });
  }

  function bonus(corpo) {
    const id = Number(corpo && corpo.regola_id);
    const minutiBonus = Number(corpo && corpo.minuti);
    const regola = S.regole.find((r) => r.id === id && r.attiva);
    const ammessa = regola && regola.tipo === 'limite_tempo' && regola.dispositivo &&
      regola.dispositivo.id === S.computer.id && [5, 15, 30].includes(minutiBonus);
    if (!ammessa) return risposta(200, { ok: false, errore: 'regola_non_valida', dettagli: {} });
    if (!opzioni.rete) return risposta(200, { ok: false, errore: 'rete', dettagli: {} });
    const g = S.bonus.giorno;
    const s = S.bonus.settimana;
    if (minutiBonus > g.residui || minutiBonus > s.residui) {
      return risposta(200, {
        ok: false, errore: 'tetto_superato',
        dettagli: { residuo_giorno: g.residui, residuo_settimana: s.residui },
      });
    }
    g.usati += minutiBonus; g.residui -= minutiBonus;
    s.usati += minutiBonus; s.residui -= minutiBonus;
    S.bonusOggi[id] = (S.bonusOggi[id] || 0) + minutiBonus;
    misura(regola);
    return risposta(200, { ok: true, bonus: S.bonus });
  }

  function locale(metodo, via, corpo) {
    switch (metodo + ' ' + via) {
      case 'GET /locale/stato': return risposta(200, statoMotore());
      case 'POST /locale/abbina': return abbina(corpo);
      case 'GET /locale/oggi': return S.abbinato ? risposta(200, oggiLocale()) : risposta(200, null);
      case 'GET /locale/visti': return risposta(200, S.visti);
      case 'POST /locale/bonus': return bonus(corpo);
      case 'GET /locale/serie': {
        const serie = T.serieDiGiorni(striscia());
        S.record = Math.max(S.record, serie);
        return risposta(200, { serie, record: S.record });
      }
      case 'POST /locale/aggiorna':
        if (opzioni.rete) {
          S.ultimoInvio = new Date();
          S.pattoAggiornato = new Date();
        }
        return risposta(200, { ok: opzioni.rete });
      default:
        return errore(404, 'Not Found');
    }
  }

  // --- /server/* (il server, come lo racconta il contratto v3) ---------------------------

  function patto() {
    const mie = S.regole.filter((r) => r.attiva &&
      (r.tipo === 'vita_reale' || (r.dispositivo && r.dispositivo.id === S.computer.id)));
    const aggregata = striscia();
    return {
      regole: mie.map(pubblica),
      bonus: S.bonus,
      bonus_oggi_per_regola: S.bonusOggi,
      proposte_pendenti: S.proposte.filter((p) => p.stato === 'pendente').map(aggiornaConfronto),
      dichiarazioni_in_attesa: S.dichiarazioni.filter((d) => d.stato === 'in_attesa'),
      siti_recenti: S.siti,
      striscia: aggregata,
      riepilogo: { giorni_fuori_regola: aggregata.filter((x) => x.stato === 'rosso').length, interruzioni: S.interruzioni },
      fuso: 'Europe/Rome',
      figlio: S.figlio,
      dispositivo: S.computer,
      striscia_dispositivo: strisciaDi(S.computer),
      dispositivi: [S.telefono, S.computer].map((d) => Object.assign({}, d, { striscia: strisciaDi(d) })),
    };
  }

  function piuRecenti(a, b) {
    return String(b.ts_server).localeCompare(String(a.ts_server)) || b.id - a.id;
  }

  function creaRegola(corpo) {
    const tipo = corpo && corpo.tipo;
    const esito = valida(tipo, corpo && corpo.parametri);
    if (esito.errori) return risposta(422, { detail: esito.errori });
    const dispositivo = tipo === 'vita_reale' ? null : S.computer;
    const adesso = new Date();
    const oggi = T.isoGiorno(adesso);
    const regola = {
      id: ++S.ultimoId,
      tipo,
      parametri: esito.parametri,
      attiva: true,
      dispositivo_id: dispositivo ? dispositivo.id : null,
      dispositivo: dispositivo ? Object.assign({}, dispositivo) : null,
      creata_ts: isoTs(adesso),
      ultima_modifica_ts: isoTs(adesso),
      allentabile_dal: isoTs(new Date(adesso.getTime() + 4 * 86400000)),
      // Prima della creazione nessun dato; oggi c'è la fotografia del computer.
      semaforo: S.giorni.map((data) => ({ data, stato: data === oggi && dispositivo ? 'verde' : 'grigio' })),
    };
    S.regole.push(regola);
    misura(regola);
    return risposta(201, pubblica(regola));
  }

  function modificaRegola(id, corpo) {
    const regola = S.regole.find((r) => r.id === id && r.attiva);
    if (!regola) return errore(404, 'regola non trovata');
    const esito = valida(regola.tipo, corpo && corpo.parametri);
    if (esito.errori) return risposta(422, { detail: esito.errori });
    if (allenta(regola.tipo, regola.parametri, esito.parametri)) {
      const blocco = bloccata(regola);
      if (blocco) return blocco;
    }
    regola.parametri = esito.parametri;
    tocca(regola);
    misura(regola);
    return risposta(200, pubblica(regola));
  }

  function eliminaRegola(id) {
    const regola = S.regole.find((r) => r.id === id && r.attiva);
    if (!regola) return errore(404, 'regola non trovata');
    if (S.regole.filter((r) => r.attiva).length <= 1) return errore(409, { errore: 'ultima_regola' });
    const blocco = bloccata(regola);
    if (blocco) return blocco;
    regola.attiva = false;
    misura(regola);
    for (const p of S.proposte) if (p.regola_id === id && p.stato === 'pendente') p.stato = 'annullata';
    return risposta(200, { id, eliminata: true });
  }

  function rispondi(id, corpo) {
    const proposta = S.proposte.find((p) => p.id === id);
    if (!proposta || proposta.stato !== 'pendente') return errore(409, { errore: 'proposta_non_pendente' });
    const esito = corpo && corpo.esito;
    if (esito !== 'accetta' && esito !== 'rifiuta') return risposta(422, { detail: [{ loc: ['body', 'esito'], msg: 'esito non valido' }] });
    aggiornaConfronto(proposta);
    let regolaRisultante;
    if (esito === 'accetta') {
      const regola = S.regole.find((r) => r.id === proposta.regola_id && r.attiva);
      if (!regola) return errore(409, { errore: 'proposta_non_pendente' });
      if (T.eEliminazione(proposta)) {
        if (S.regole.filter((r) => r.attiva).length <= 1) return errore(409, { errore: 'ultima_regola' });
        regola.attiva = false;
        misura(regola);
        regolaRisultante = null;
      } else {
        regola.parametri = copia(proposta.parametri_proposti);
        tocca(regola);
        misura(regola);
        regolaRisultante = pubblica(regola);
      }
      proposta.stato = 'accettata';
      proposta.usata = true;
    } else {
      proposta.stato = 'rifiutata';
    }
    const motivazione = corpo.motivazione ? String(corpo.motivazione).trim() : '';
    proposta.risposta = { esito, motivazione: motivazione || null, ts_server: isoTs(new Date()) };
    const fuori = copia(proposta);
    if (esito === 'accetta') fuori.regola = regolaRisultante;
    return risposta(200, fuori);
  }

  function dichiara(corpo) {
    const id = Number(corpo && corpo.regola_id);
    const regola = S.regole.find((r) => r.id === id && r.attiva && r.tipo === 'vita_reale');
    if (!regola) return errore(409, { errore: 'regola_non_valida' });
    const esito = corpo.esito;
    if (esito !== 'successo' && esito !== 'fallimento') {
      return risposta(422, { detail: [{ loc: ['body', 'esito'], msg: 'esito non valido' }] });
    }
    const oggi = T.oggiNelFuso('Europe/Rome');
    const giorno = corpo.giorno || oggi;
    if (!T.giornoDaIso(giorno)) return risposta(422, { detail: 'giorno non valido' });
    if (giorno > oggi || giorno < T.spostaIso(oggi, -7)) return errore(409, { errore: 'giorno_non_valido' });
    if (S.dichiarazioni.some((d) => d.regola_id === id && d.giorno === giorno)) return errore(409, { errore: 'gia_dichiarato' });
    const nota = corpo.nota ? String(corpo.nota).trim() : '';
    const dichiarazione = {
      id: ++S.ultimoId,
      regola_id: id,
      giorno,
      esito,
      nota: nota || null,
      stato: esito === 'successo' ? 'in_attesa' : 'registrata',
      ts_server: isoTs(new Date()),
      verdetto: null,
    };
    S.dichiarazioni.push(dichiarazione);
    // Il rosso di un fallimento dichiarato fotografa il fatto (contratto v2.1).
    if (esito === 'fallimento') segnaGiorno(regola, giorno, 'rosso');
    return risposta(200, dichiarazione);
  }

  function server(metodo, via, corpo) {
    if (metodo === 'GET' && via === '/api/patto') return risposta(200, patto());
    if (metodo === 'GET' && via === '/api/regole') {
      return risposta(200, { regole: S.regole.filter((r) => r.attiva).map(pubblica) });
    }
    if (metodo === 'GET' && via === '/api/proposte') {
      return risposta(200, { proposte: S.proposte.map(aggiornaConfronto).slice().sort(piuRecenti) });
    }
    if (metodo === 'GET' && via === '/api/dichiarazioni') {
      return risposta(200, { dichiarazioni: S.dichiarazioni.slice().sort(piuRecenti).slice(0, 50) });
    }
    if (metodo === 'POST' && via === '/api/regole') return creaRegola(corpo);
    let trovato = /^\/api\/regole\/(\d+)$/.exec(via);
    if (trovato && metodo === 'PATCH') return modificaRegola(Number(trovato[1]), corpo);
    if (trovato && metodo === 'DELETE') return eliminaRegola(Number(trovato[1]));
    trovato = /^\/api\/proposte\/(\d+)\/risposta$/.exec(via);
    if (trovato && metodo === 'POST') return rispondi(Number(trovato[1]), corpo);
    if (metodo === 'POST' && via === '/api/dichiarazioni') return dichiara(corpo);
    return errore(404, 'Not Found');
  }

  async function gestisci(metodo, percorso, corpo) {
    if (!S) costruisci();
    await attesa(120 + Math.random() * 230);
    const via = String(percorso).split('?')[0];
    const m = String(metodo || 'GET').toUpperCase();
    if (via.startsWith('/locale/')) return locale(m, via, corpo);
    if (via.startsWith('/server/')) {
      if (!S.abbinato) return errore(401, 'token assente');
      // Il motore non raggiunge il server: nessuna risposta utile.
      if (!opzioni.rete) return risposta(503, { errore: 'rete' });
      return server(m, via.slice('/server'.length), corpo);
    }
    return errore(404, 'Not Found');
  }

  radice.PactumProva = {
    gestisci,
    /** Da chiamare prima della prima richiesta: { abbinato, rete }. */
    configura(nuove) {
      Object.assign(opzioni, nuove || {});
      if (S) S.abbinato = S.abbinato || opzioni.abbinato;
    },
    get rete() { return opzioni.rete; },
    set rete(valore) { opzioni.rete = Boolean(valore); },
  };
})(window);
