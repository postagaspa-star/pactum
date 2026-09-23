/*
 * Pactum per il computer: l'interfaccia.
 *
 * La stessa esperienza dell'app del telefono, in una finestra: il figlio si
 * dà le regole, niente viene bloccato, il genitore vede e può solo proporre.
 * Parla SOLO col motore, attraverso api.js (/locale/* e /server/*).
 *
 * Principi (docs/redesign-tavola-rotonda.md, parte 2):
 *  - un solo protagonista per schermata (in Oggi: la serie);
 *  - un solo rosso, dentro i quadretti della striscia e da nessun'altra parte;
 *  - i fatti sono gli stessi che vede il genitore; serie e record restano qui.
 * Tutto il testo che arriva da fuori entra nella pagina come testo, mai come
 * codice. Nessun indirizzo completo di pagina viene mostrato o salvato.
 */
(function () {
  'use strict';

  const T = window.PactumTesti;
  const Api = window.PactumApi;

  const OGNI_MINUTO = 60000;
  const SEZIONI = [
    { id: 'oggi', titolo: 'Oggi', icona: 'oggi', gruppo: 1 },
    { id: 'regole', titolo: 'Le mie regole', icona: 'regole', gruppo: 1 },
    { id: 'proposte', titolo: 'Proposte', icona: 'proposte', gruppo: 1 },
    { id: 'diario', titolo: 'Diario', icona: 'diario', gruppo: 1 },
    { id: 'siti', titolo: 'Siti', icona: 'siti', gruppo: 2 },
    { id: 'cosa-vede', titolo: 'Cosa vede tuo padre', icona: 'occhio', gruppo: 2 },
    { id: 'impostazioni', titolo: 'Impostazioni', icona: 'impostazioni', gruppo: 2 },
  ];

  // --- Stato -------------------------------------------------------------------------

  const S = {
    modalita: null,          // 'vera' | 'prova' | 'attesa' (motore che non risponde)
    motore: null,            // GET /locale/stato
    oggi: null,              // GET /locale/oggi
    serie: null,             // GET /locale/serie
    visti: null,             // GET /locale/visti
    patto: null,             // GET /server/api/patto
    pattoOkAlle: null,       // quando il patto è arrivato l'ultima volta
    pattoNonAggiornato: false,
    revocato: false,         // il server non riconosce più questo computer (401)
    regoleFiglio: null,      // GET /server/api/regole: tutte le regole del figlio
    proposte: null,          // GET /server/api/proposte
    dichiarazioni: null,     // GET /server/api/dichiarazioni
    dichiarazioniOttimiste: [],
    sezione: 'oggi',
    bonus: null,             // { regolaId, minuti, invio } mentre il pannello del bonus è aperto
    bozze: {},               // quello che si sta scrivendo nei campi, per chiave
    motivazioniAperte: {},
    messaggi: {},            // note in linea (errori spiegati) per chiave
    invii: {},               // azioni in corso per chiave
    aggiornamento: null,     // promessa del giro di lettura in corso
    aggiornoAdesso: false,
    ricollega: false,
    fuocoDopo: null,
    fuocoTitolo: false,
    dom: null,
  };

  // --- Costruire la pagina senza HTML da stringhe ---------------------------------------

  function h(tag, attributi, ...figli) {
    const el = document.createElement(tag);
    if (attributi) {
      for (const [nome, valore] of Object.entries(attributi)) {
        if (valore === null || valore === undefined || valore === false) continue;
        if (nome === 'class') el.className = valore;
        else if (nome === 'chiave') el.dataset.chiave = valore;
        else if (nome.startsWith('on') && typeof valore === 'function') el.addEventListener(nome.slice(2), valore);
        else if (valore === true) el.setAttribute(nome, '');
        else el.setAttribute(nome, String(valore));
      }
    }
    aggiungi(el, figli);
    return el;
  }

  function aggiungi(el, figli) {
    for (const figlio of figli.flat(Infinity)) {
      if (figlio === null || figlio === undefined || figlio === false) continue;
      el.append(figlio instanceof Node ? figlio : document.createTextNode(String(figlio)));
    }
    return el;
  }

  const SVG = 'http://www.w3.org/2000/svg';
  const ICONE = {
    oggi: [['rect', { x: 3.5, y: 5, width: 17, height: 15.5, rx: 3 }], ['path', { d: 'M3.5 10h17M8 3v4M16 3v4' }], ['circle', { cx: 12, cy: 15, r: 1.7, class: 'pieno' }]],
    regole: [['path', { d: 'M10 7h10M10 12h10M10 17h10' }], ['path', { d: 'M3.8 7l1.4 1.4L7.8 5.8M3.8 12l1.4 1.4 2.6-2.6M3.8 17l1.4 1.4 2.6-2.6' }]],
    proposte: [['path', { d: 'M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 4v-4A2.5 2.5 0 0 1 4 13.5z' }], ['path', { d: 'M8.5 8.5h7M8.5 11.5h4.5' }]],
    diario: [['path', { d: 'M5 4.5h11.5A2.5 2.5 0 0 1 19 7v12.5H7.5A2.5 2.5 0 0 1 5 17z' }], ['path', { d: 'M5 17a2.5 2.5 0 0 1 2.5-2.5H19M9 8.5h6' }]],
    siti: [['circle', { cx: 12, cy: 12, r: 8.5 }], ['path', { d: 'M3.5 12h17M12 3.5c2.4 2.6 3.6 5.4 3.6 8.5s-1.2 5.9-3.6 8.5M12 3.5C9.6 6.1 8.4 8.9 8.4 12s1.2 5.9 3.6 8.5' }]],
    occhio: [['path', { d: 'M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z' }], ['circle', { cx: 12, cy: 12, r: 3 }]],
    impostazioni: [['path', { d: 'M4 7h8M17 7h3M4 17h3M12 17h8' }], ['circle', { cx: 14.5, cy: 7, r: 2.3 }], ['circle', { cx: 9.5, cy: 17, r: 2.3 }]],
    aggiorna: [['path', { d: 'M19.5 12a7.5 7.5 0 1 1-2.3-5.4' }], ['path', { d: 'M19.5 4.5v4.2h-4.2' }]],
    spunta: [['path', { d: 'M5 12.5l4.2 4.2L19 7' }]],
    info: [['circle', { cx: 12, cy: 12, r: 8.5 }], ['path', { d: 'M12 11v5.5M12 7.9v.1' }]],
    piu: [['path', { d: 'M12 5v14M5 12h14' }]],
    freccia: [['path', { d: 'M5 12h14M13 6l6 6-6 6' }]],
    chiudi: [['path', { d: 'M6 6l12 12M18 6L6 18' }]],
    computer: [['rect', { x: 3, y: 4.5, width: 18, height: 12, rx: 2 }], ['path', { d: 'M8 20h8M12 16.5V20' }]],
    telefono: [['rect', { x: 7, y: 3, width: 10, height: 18, rx: 2.5 }], ['path', { d: 'M11 18h2' }]],
    persona: [['circle', { cx: 12, cy: 7.5, r: 3.5 }], ['path', { d: 'M5 20a7 7 0 0 1 14 0' }]],
    rete: [['path', { d: 'M4.5 9.5a11 11 0 0 1 15 0M7.3 12.7a7 7 0 0 1 9.4 0M10.1 15.8a3 3 0 0 1 3.8 0' }], ['circle', { cx: 12, cy: 18.6, r: 1.1, class: 'pieno' }]],
  };

  function icona(nome, classe) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('class', 'icona' + (classe ? ' ' + classe : ''));
    for (const [tipo, attributi] of ICONE[nome] || []) {
      const parte = document.createElementNS(SVG, tipo);
      for (const [k, v] of Object.entries(attributi)) parte.setAttribute(k, String(v));
      svg.append(parte);
    }
    return svg;
  }

  function cerca(chiave) {
    if (!chiave) return null;
    const sicura = window.CSS && CSS.escape ? CSS.escape(chiave) : String(chiave).replace(/"/g, '\\"');
    return document.querySelector('[data-chiave="' + sicura + '"]');
  }

  /**
   * Ridisegna senza perdere il posto: il campo in cui si sta scrivendo resta
   * a fuoco (col cursore dov'era) e la pagina non salta in cima.
   */
  function conservaFuoco(ridisegna) {
    const attivo = document.activeElement;
    const chiave = attivo && attivo.dataset ? attivo.dataset.chiave : null;
    let selezione = null;
    if (chiave && typeof attivo.selectionStart === 'number') {
      try {
        selezione = [attivo.selectionStart, attivo.selectionEnd];
      } catch (e) {
        selezione = null;
      }
    }
    const scorrimento = S.dom ? S.dom.principale.scrollTop : 0;
    ridisegna();
    if (S.dom) S.dom.principale.scrollTop = scorrimento;
    if (chiave && !(attivo && attivo.isConnected)) {
      const nuovo = cerca(chiave);
      if (nuovo && !nuovo.disabled) {
        nuovo.focus({ preventScroll: true });
        if (selezione && typeof nuovo.setSelectionRange === 'function') {
          try {
            nuovo.setSelectionRange(selezione[0], selezione[1]);
          } catch (e) {
            // Non tutti i campi hanno un cursore.
          }
        }
      }
    }
  }

  // --- Pezzi comuni ------------------------------------------------------------------------

  function sopratitolo(testo, id) {
    return h('h2', { class: 'sopratitolo', id }, testo);
  }

  function titoloSezione(testo, id) {
    return h('h2', { class: 'titolo-sezione', id }, testo);
  }

  function rigaVuota(nomeIcona, testo) {
    return h('p', { class: 'riga-vuota' }, icona(nomeIcona), h('span', null, testo));
  }

  function caricamento(testo) {
    return h('p', { class: 'caricamento', role: 'status' }, h('span', { class: 'rotella', 'aria-hidden': 'true' }), testo);
  }

  /** Una nota che spiega qualcosa andato storto: mai rossa, si legge e basta. */
  function notaMessaggio(testo, id) {
    return h('p', { class: 'messaggio', role: 'alert', id }, icona('info'), h('span', null, testo));
  }

  function chip(testo, variante) {
    return h('span', { class: 'chip chip-' + (variante || 'secondario') }, testo);
  }

  /**
   * La striscia dei giorni, dal più vecchio a oggi (oggi in coda, con
   * l'anello). Grande: numero del giorno dentro il quadretto; piccola: senza.
   * Per chi usa un lettore di schermo vale la frase, non i singoli quadretti.
   */
  function striscia(giorni, opzioni) {
    const o = opzioni || {};
    const lista = Array.isArray(giorni) ? giorni : [];
    const el = h('div', { class: 'striscia' + (o.piccola ? ' striscia-piccola' : ''), role: 'img', 'aria-label': o.descrizione || T.fraseStriscia(lista) });
    lista.forEach((giorno, i) => {
      const segnale = T.segnale(giorno && giorno.stato);
      const eOggi = i === lista.length - 1;
      const data = String((giorno && giorno.data) || '');
      const significato = segnale === 'mantenuta' ? 'dentro le regole' : segnale === 'fuori' ? 'fuori regola' : 'nessun dato';
      el.append(h('span', {
        class: 'giorno giorno-' + segnale + (eOggi ? ' giorno-oggi' : ''),
        title: (eOggi ? 'Oggi' : T.giornoEsteso(data, oggiPatto())) + ': ' + significato,
      }, h('span', { class: 'quadretto' }, !o.piccola && segnale !== 'nessuno' ? data.slice(-2) : null)));
    });
    return el;
  }

  /** La barra sul limite efficace: oltre il limite resta piena e verde, mai rossa. */
  function barra(minuti, limite) {
    const frazione = Math.max(0, Math.min(1, (Number(minuti) || 0) / Math.max(1, Number(limite) || 1)));
    const pieno = h('span', { class: 'barra-pieno' });
    pieno.style.width = (frazione * 100).toFixed(1) + '%';
    return h('span', { class: 'barra', 'aria-hidden': 'true' }, pieno);
  }

  function righeValore(voci) {
    return h('ul', { class: 'lista-righe' }, voci.map(([nome, valore]) =>
      h('li', { class: 'riga-valore' }, h('span', { class: 'riga-nome' }, nome), h('span', { class: 'riga-numero' }, valore))));
  }

  function avviso(testo) {
    const zona = document.getElementById('avvisi');
    if (!zona) return;
    while (zona.children.length >= 3) zona.firstChild.remove();
    const el = h('div', { class: 'avviso' });
    const chiudi = h('button', { type: 'button', class: 'avviso-chiudi', 'aria-label': 'Chiudi l\'avviso', onclick: () => el.remove() }, icona('chiudi'));
    el.append(h('span', { class: 'avviso-testo' }, testo), chiudi);
    zona.append(el);
    setTimeout(() => el.remove(), Math.max(5000, testo.length * 75));
  }

  function capitale(testo) {
    const s = String(testo || '');
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  // --- Dati derivati ----------------------------------------------------------------------

  function oggiPatto() {
    return T.oggiNelFuso(S.patto && S.patto.fuso);
  }

  /** I nomi leggibili dei programmi, da quello che il motore ha visto. */
  function nomiProgrammi() {
    const nomi = new Map();
    const aggiungiNome = (voce) => {
      if (voce && voce.chiave && voce.nome) nomi.set(String(voce.chiave).toLowerCase(), voce.nome);
    };
    ((S.visti && S.visti.programmi) || []).forEach(aggiungiNome);
    ((S.oggi && S.oggi.programmi) || []).forEach(aggiungiNome);
    return nomi;
  }

  /** I nomi dei programmi, più quello che il server manda con la regola (es. "Minecraft"), se c'è. */
  function nomiPer(regola) {
    const nomi = nomiProgrammi();
    const chiave = regola && regola.parametri && regola.parametri.app_o_categoria;
    if (regola && typeof regola.nome === 'string' && regola.nome.trim() && chiave) {
      nomi.set(String(chiave).toLowerCase(), regola.nome.trim());
    }
    return nomi;
  }

  function descrizione(regola) {
    return T.descrizioneRegola(regola, { nomi: nomiPer(regola), tipoDispositivo: 'computer' });
  }

  function attive(regole) {
    return (regole || []).filter((r) => r && r.attiva !== false);
  }

  /**
   * Le regole che valgono su QUESTO computer: le sue e quelle di vita reale.
   * Il server manda già solo queste; il filtro è una cintura in più, come sul
   * telefono: una regola del telefono mostrata qui confonderebbe i conti.
   */
  function regoleDiQuesto(patto) {
    const questo = questoDispositivo();
    return attive(patto && patto.regole).filter((r) => {
      if (r.tipo === 'vita_reale') return true;
      const id = r.dispositivo_id != null ? r.dispositivo_id : r.dispositivo ? r.dispositivo.id : null;
      return id == null || !questo || id === questo.id;
    });
  }

  function regolaPerId(id) {
    return attive(S.regoleFiglio).find((r) => r.id === id) ||
      attive(S.patto && S.patto.regole).find((r) => r.id === id) || null;
  }

  function questoDispositivo() {
    return (S.patto && S.patto.dispositivo) || (S.motore && S.motore.dispositivo) || null;
  }

  function pendenti() {
    const perId = new Map();
    ((S.patto && S.patto.proposte_pendenti) || []).forEach((p) => perId.set(p.id, p));
    (S.proposte || []).forEach((p) => {
      if (p.stato === 'pendente') perId.set(p.id, p);
      else perId.delete(p.id);
    });
    return [...perId.values()].sort((a, b) => String(b.ts_server || '').localeCompare(String(a.ts_server || '')));
  }

  function tutteDichiarazioni() {
    const perId = new Map();
    ((S.patto && S.patto.dichiarazioni_in_attesa) || []).forEach((d) => perId.set(d.id, d));
    (S.dichiarazioni || []).forEach((d) => perId.set(d.id, d));
    S.dichiarazioniOttimiste.forEach((d) => {
      const giaArrivata = [...perId.values()].some((x) => x.regola_id === d.regola_id && x.giorno === d.giorno);
      if (!giaArrivata) perId.set(d.id, d);
    });
    return [...perId.values()].sort((a, b) =>
      String(b.giorno || '').localeCompare(String(a.giorno || '')) ||
      String(b.ts_server || '').localeCompare(String(a.ts_server || '')));
  }

  /**
   * Il patto letto OGGI (nel fuso del patto)? I suoi numeri "di oggi" sui bonus
   * valgono solo allora: dopo una notte senza rete sarebbero quelli di ieri
   * (come sul telefono, bonusValidiOggi).
   */
  function pattoDiOggi() {
    if (!S.patto || !S.pattoOkAlle) return false;
    return T.oggiNelFuso(S.patto.fuso, S.pattoOkAlle) === oggiPatto();
  }

  function bonusOggiDi(regola) {
    if (!pattoDiOggi()) return 0;
    return T.numero(S.patto.bonus_oggi_per_regola && S.patto.bonus_oggi_per_regola[String(regola.id)]) || 0;
  }

  function residuoBonus() {
    if (!pattoDiOggi()) return null;
    const b = S.patto && S.patto.bonus;
    if (!b || !b.giorno || !b.settimana) return null;
    const g = T.numero(b.giorno.residui);
    const s = T.numero(b.settimana.residui);
    if (g === null || s === null) return null;
    return Math.max(0, Math.min(g, s));
  }

  function arbitroDi(regola) {
    return (regola && regola.parametri && regola.parametri.arbitro_nome) || '?';
  }

  /** Quanti minuti su quale limite, per una regola di tempo di questo computer. */
  function misuraRegola(regola) {
    const p = regola.parametri || {};
    const limiteBase = (T.numero(p.minuti_al_giorno) || 0) + bonusOggiDi(regola);
    const dalMotore = S.oggi && S.oggi.regole && S.oggi.regole[String(regola.id)];
    if (dalMotore && T.numero(dalMotore.minuti) !== null) {
      const limite = T.numero(dalMotore.limite_efficace) !== null ? T.numero(dalMotore.limite_efficace) : limiteBase;
      const oltre = T.numero(dalMotore.oltre) !== null ? T.numero(dalMotore.oltre) : Math.max(0, dalMotore.minuti - limite);
      return { minuti: T.numero(dalMotore.minuti), limite, oltre };
    }
    // Il motore non l'ha ancora misurata (regola appena creata): per un
    // programma o un sito i minuti sono quelli di oggi; per una categoria no.
    const chiave = String(p.app_o_categoria || '').toLowerCase();
    let minuti = null;
    if (S.oggi && chiave.startsWith('exe:')) {
      minuti = (S.oggi.programmi || []).filter((x) => String(x.chiave).toLowerCase() === chiave)
        .reduce((somma, x) => somma + (T.numero(x.minuti) || 0), 0);
    } else if (S.oggi && chiave.startsWith('sito:')) {
      minuti = (S.oggi.siti || []).filter((x) => x.dominio === chiave.slice(5))
        .reduce((somma, x) => somma + (T.numero(x.minuti) || 0), 0);
    }
    return { minuti, limite: limiteBase, oltre: minuti === null ? 0 : Math.max(0, minuti - limiteBase) };
  }

  function momentoDi(regola) {
    const dalMotore = S.oggi && S.oggi.fasce && S.oggi.fasce[String(regola.id)];
    if (dalMotore && dalMotore.attiva_ora === true) {
      return { tipo: 'in_corso', fine: dalMotore.fine || (regola.parametri || {}).alle };
    }
    return T.momentoFascia(regola.parametri, new Date());
  }

  /** "Concordata": i parametri di adesso sono quelli di una proposta accettata. */
  function eConcordata(regola) {
    return (S.proposte || []).some((p) => p.stato === 'accettata' && p.usata && p.regola_id === regola.id &&
      T.uguali(p.parametri_proposti, regola.parametri));
  }

  function totaleRegoleFiglio() {
    return S.regoleFiglio ? attive(S.regoleFiglio).length : null;
  }

  // --- Copia locale del patto (per aprire la finestra anche senza rete) --------------------

  function chiaveCopia() {
    const d = S.motore && S.motore.dispositivo;
    return d && d.id != null ? 'pactum.copia.' + d.id : null;
  }

  function salvaCopia() {
    const chiave = chiaveCopia();
    if (!chiave || S.modalita !== 'vera' || !S.patto) return;
    try {
      localStorage.setItem(chiave, JSON.stringify({
        alle: S.pattoOkAlle ? S.pattoOkAlle.toISOString() : null,
        patto: S.patto,
        regoleFiglio: S.regoleFiglio,
        proposte: S.proposte,
        dichiarazioni: S.dichiarazioni,
      }));
    } catch (e) {
      // Spazio pieno o archivio non disponibile: si vive senza copia.
    }
  }

  function leggiCopia() {
    const chiave = chiaveCopia();
    if (!chiave || S.modalita !== 'vera' || S.patto) return;
    try {
      const copia = JSON.parse(localStorage.getItem(chiave) || 'null');
      if (!copia || !copia.patto) return;
      S.patto = copia.patto;
      S.regoleFiglio = copia.regoleFiglio || null;
      S.proposte = copia.proposte || null;
      S.dichiarazioni = copia.dichiarazioni || null;
      S.pattoOkAlle = T.istante(copia.alle);
    } catch (e) {
      // Copia rovinata: si ignora.
    }
  }

  // --- Leggere i dati ---------------------------------------------------------------------

  /** Un giro di lettura completo. Mai due insieme. */
  function aggiornaTutto() {
    if (S.aggiornamento) return S.aggiornamento;
    S.aggiornamento = (async () => {
      try {
        const stato = await Api.get('/locale/stato');
        if (stato.ok && stato.dati && typeof stato.dati.abbinato === 'boolean') S.motore = stato.dati;
        if (!S.motore || S.motore.abbinato === false) return;

        const [oggi, serie, patto, proposte, dichiarazioni, regole] = await Promise.all([
          Api.get('/locale/oggi'),
          Api.get('/locale/serie'),
          Api.get('/server/api/patto'),
          Api.get('/server/api/proposte'),
          Api.get('/server/api/dichiarazioni'),
          Api.get('/server/api/regole'),
        ]);
        if (oggi.ok && oggi.dati && typeof oggi.dati === 'object') S.oggi = oggi.dati;
        if (serie.ok && serie.dati && typeof serie.dati === 'object') S.serie = serie.dati;

        if (patto.ok && patto.dati && typeof patto.dati === 'object') {
          S.patto = patto.dati;
          S.pattoOkAlle = new Date();
          S.pattoNonAggiornato = false;
          S.revocato = false;
          S.dichiarazioniOttimiste = [];
        } else {
          S.pattoNonAggiornato = true;
          S.revocato = patto.stato === 401;
        }
        if (proposte.ok && proposte.dati && Array.isArray(proposte.dati.proposte)) S.proposte = proposte.dati.proposte;
        if (dichiarazioni.ok && dichiarazioni.dati && Array.isArray(dichiarazioni.dati.dichiarazioni)) {
          S.dichiarazioni = dichiarazioni.dati.dichiarazioni;
        }
        if (regole.ok && regole.dati && Array.isArray(regole.dati.regole)) S.regoleFiglio = regole.dati.regole;
        if (patto.ok) salvaCopia();
      } finally {
        S.aggiornamento = null;
        S.ultimoGiro = Date.now();
        render();
      }
    })();
    return S.aggiornamento;
  }

  async function caricaVisti() {
    const r = await Api.get('/locale/visti');
    if (r.ok && r.dati && typeof r.dati === 'object') {
      S.visti = {
        programmi: Array.isArray(r.dati.programmi) ? r.dati.programmi : [],
        siti: Array.isArray(r.dati.siti) ? r.dati.siti.filter((s) => typeof s === 'string') : [],
      };
    }
    return S.visti;
  }

  async function aggiornaAdesso() {
    if (S.aggiornoAdesso) return;
    S.aggiornoAdesso = true;
    render();
    const r = await Api.post('/locale/aggiorna');
    await aggiornaTutto();
    S.aggiornoAdesso = false;
    render();
    const fatto = r.ok && r.dati && r.dati.ok === true;
    avviso(fatto
      ? 'Fatto: dati inviati e patto riletto.'
      : 'Adesso il server non risponde: i dati restano sul computer e partono da soli appena torna la rete.');
  }

  // --- Disegnare ---------------------------------------------------------------------------

  function render() {
    if (!S.dom) return;
    if (S.modalita === 'attesa') {
      mostraSolo(schermataAttesa());
      return;
    }
    if (!S.motore) return;
    if (S.motore.abbinato === false || S.ricollega) {
      mostraSolo(schermataCollega());
      return;
    }
    S.dom.app.classList.remove('solo');
    S.dom.laterale.hidden = false;
    conservaFuoco(() => {
      S.dom.laterale.replaceChildren(...barraLaterale());
      S.dom.contenuto.replaceChildren(...contenutoSezione());
    });
    document.title = (S.modalita === 'prova' ? 'Pactum (prova) · ' : 'Pactum · ') + definizione(S.sezione).titolo;
    dopoRender();
  }

  function mostraSolo(nodo) {
    S.dom.app.classList.add('solo');
    S.dom.laterale.hidden = true;
    conservaFuoco(() => S.dom.contenuto.replaceChildren(nodo));
    document.title = 'Pactum';
    dopoRender();
  }

  function dopoRender() {
    S.dom.app.removeAttribute('aria-busy');
    if (S.fuocoTitolo) {
      S.fuocoTitolo = false;
      const titolo = document.getElementById('titolo-pagina');
      if (titolo) titolo.focus({ preventScroll: true });
    }
    if (S.fuocoDopo) {
      const el = cerca(S.fuocoDopo);
      S.fuocoDopo = null;
      if (el && !el.disabled) el.focus();
    }
  }

  function definizione(id) {
    return SEZIONI.find((s) => s.id === id) || SEZIONI[0];
  }

  function barraLaterale() {
    const figlio = (S.patto && S.patto.figlio) || (S.motore && S.motore.figlio);
    const dispositivo = questoDispositivo();
    const sotto = [figlio && figlio.nome, dispositivo && dispositivo.nome].filter(Boolean).join(' · ');
    const voci = (gruppo) => h('ul', { class: 'voci' }, SEZIONI.filter((s) => s.gruppo === gruppo).map(voceMenu));
    const parti = [
      h('div', { class: 'marchio' }, h('span', { class: 'marchio-nome' }, 'Pactum'), sotto ? h('span', { class: 'marchio-sotto' }, sotto) : null),
      h('nav', { class: 'menu', 'aria-label': 'Sezioni' }, voci(1), h('hr', { class: 'separatore' }), voci(2)),
      h('div', { class: 'spazio' }),
      statoLaterale(),
    ];
    if (S.modalita === 'prova') parti.push(pannelloProva());
    return parti;
  }

  function voceMenu(def) {
    const attiva = S.sezione === def.id;
    const a = h('a', {
      href: '#' + def.id,
      class: 'voce' + (attiva ? ' attiva' : ''),
      chiave: 'menu-' + def.id,
      'aria-current': attiva ? 'page' : null,
    }, icona(def.icona), h('span', { class: 'voce-testo' }, def.titolo));
    if (def.id === 'proposte') {
      const n = pendenti().length;
      if (n > 0) {
        a.append(h('span', { class: 'contatore' }, h('span', { 'aria-hidden': 'true' }, String(n)),
          h('span', { class: 'solo-lettori' }, ', ' + n + ' da decidere')));
      }
    }
    return h('li', null, a);
  }

  function statoLaterale() {
    if (S.revocato) return h('p', { class: 'stato-laterale' }, h('span', { class: 'punto punto-spento' }), 'Computer non più collegato');
    if (S.pattoNonAggiornato) return h('p', { class: 'stato-laterale' }, h('span', { class: 'punto punto-spento' }), 'Dati non aggiornati');
    if (!S.pattoOkAlle) return h('p', { class: 'stato-laterale' }, h('span', { class: 'punto punto-spento' }), 'Sto leggendo il patto…');
    return h('p', { class: 'stato-laterale' }, h('span', { class: 'punto' }), 'Collegato al patto');
  }

  function pannelloProva() {
    const id = 'prova-rete';
    return h('div', { class: 'pannello-prova' },
      h('p', { class: 'pannello-prova-titolo' }, 'Modalità di prova'),
      h('p', { class: 'pannello-prova-testo' }, 'Dati finti: niente arriva a un server.'),
      h('label', { class: 'interruttore', for: id },
        h('input', {
          type: 'checkbox', id, chiave: id, checked: window.PactumProva && !window.PactumProva.rete,
          onchange: (e) => {
            window.PactumProva.rete = !e.target.checked;
            aggiornaTutto();
          },
        }),
        h('span', null, 'Simula rete assente')));
  }

  function contenutoSezione() {
    const def = definizione(S.sezione);
    const parti = [testa(def)];
    const riga = rigaDatiNonAggiornati();
    if (riga) parti.push(riga);
    if (S.revocato) parti.push(bloccoRevocato());
    switch (def.id) {
      case 'oggi': parti.push(...sezioneOggi()); break;
      case 'regole': parti.push(...sezioneRegole()); break;
      case 'proposte': parti.push(...sezioneProposte()); break;
      case 'diario': parti.push(...sezioneDiario()); break;
      case 'siti': parti.push(...sezioneSiti()); break;
      case 'cosa-vede': parti.push(...sezioneCosaVede()); break;
      case 'impostazioni': parti.push(...sezioneImpostazioni()); break;
      default: break;
    }
    return parti;
  }

  function testa(def) {
    const azioni = [];
    if (def.id === 'regole' && S.patto) {
      azioni.push(h('button', { type: 'button', class: 'bottone primario con-icona', chiave: 'nuova-regola', onclick: () => dialogoRegola(null) },
        icona('piu'), 'Nuova regola'));
    }
    // Cosa vede non ha dati da aggiornare; Impostazioni ha già "Aggiorna adesso".
    if (def.id !== 'cosa-vede' && def.id !== 'impostazioni') {
      azioni.push(h('button', {
        type: 'button', class: 'bottone testo con-icona', chiave: 'aggiorna-' + def.id,
        disabled: S.aggiornoAdesso, onclick: aggiornaAdesso,
      }, icona('aggiorna'), S.aggiornoAdesso ? 'Aggiorno…' : 'Aggiorna'));
    }
    return h('header', { class: 'testa' },
      h('h1', { id: 'titolo-pagina', chiave: 'titolo-pagina', tabindex: '-1' }, def.titolo),
      azioni.length ? h('div', { class: 'azioni-testa' }, azioni) : null);
  }

  /** Dati vecchi: è un'età, non un errore. Una riga grigia, mai rossa. */
  function rigaDatiNonAggiornati() {
    if (!S.pattoNonAggiornato || S.revocato) return null;
    const testo = S.patto && S.pattoOkAlle
      ? 'Dati non aggiornati: ultimo aggiornamento ' + T.quando(S.pattoOkAlle) + '.'
      : S.patto
        ? 'Dati non aggiornati: questi sono gli ultimi ricevuti.'
        : 'Dati non aggiornati: il patto non è ancora arrivato su questo computer.';
    // Niente regione "viva": si ridisegna ogni minuto e un lettore di schermo la ripeterebbe.
    return h('p', { class: 'riga-grigia' }, icona('rete'), h('span', null, testo));
  }

  function bloccoRevocato() {
    // Non è un'età dei dati: il computer va ricollegato, e si dice come.
    return h('section', { class: 'card card-avviso' },
      h('p', null, 'Questo computer non è più collegato al patto: chiedi al genitore un codice nuovo.'),
      h('p', { class: 'secondario' }, 'Succede quando il genitore toglie il computer dalla sua app, o lo collega di nuovo con un altro codice.'),
      h('div', { class: 'azioni' }, h('button', {
        type: 'button', class: 'bottone primario', chiave: 'ricollega',
        onclick: () => { S.ricollega = true; S.fuocoTitolo = true; render(); },
      }, 'Collega con un codice nuovo')));
  }

  // --- Oggi --------------------------------------------------------------------------------

  function sezioneOggi() {
    const parti = [];
    const patto = S.patto;
    if (patto && Array.isArray(patto.striscia) && patto.striscia.length) parti.push(schedaPatto(patto));
    else if (!patto && !S.pattoNonAggiornato) parti.push(caricamento('Sto leggendo il patto…'));

    if (patto) parti.push(bloccoRegoleOggi(patto));
    parti.push(bloccoTempo());
    return parti;
  }

  /** L'eroe: la serie. Sotto la striscia, identica a quella del genitore. */
  function schedaPatto(patto) {
    const serie = S.serie && T.numero(S.serie.serie) !== null ? T.numero(S.serie.serie) : T.serieDiGiorni(patto.striscia);
    const record = S.serie && T.numero(S.serie.record) !== null ? T.numero(S.serie.record) : 0;
    const scheda = h('section', { class: 'scheda-eroe', 'aria-label': 'Il tuo patto' });
    if (serie > 0) {
      scheda.append(h('p', { class: 'eroe' }, T.testoSerie(serie)), h('p', { class: 'eroe-sotto' }, 'dentro le tue regole'));
    } else {
      // Serie a zero: il numero grande non deve dare torto al ragazzo.
      scheda.append(h('p', { class: 'eroe' }, record > 0 ? 'Si riparte da oggi' : 'Si comincia da oggi'));
    }
    if (record > 0) scheda.append(h('p', { class: 'record' }, 'il tuo record: ' + record));

    const frase = T.fraseStriscia(patto.striscia);
    scheda.append(striscia(patto.striscia, { descrizione: frase }), h('p', { class: 'frase-striscia' }, frase));
    const riepilogo = T.fraseRiepilogo(patto.riepilogo);
    if (riepilogo) scheda.append(h('p', { class: 'riepilogo' }, riepilogo));

    // (v3) Una riga per dispositivo, dalla sua striscia, con le parole del telefono: "Telefono: 6 su 7".
    const dispositivi = Array.isArray(patto.dispositivi) ? patto.dispositivi : [];
    if (dispositivi.length > 1) {
      const questo = questoDispositivo();
      const idQuesto = questo ? questo.id : null;
      const ordinati = dispositivi.slice().sort((a, b) =>
        (b.id === idQuesto ? 1 : 0) - (a.id === idQuesto ? 1 : 0) || (T.numero(a.id) || 0) - (T.numero(b.id) || 0));
      scheda.append(h('ul', { class: 'righe-dispositivi', 'aria-label': 'Per dispositivo' },
        ordinati.map((d) => h('li', null, T.rigaDispositivo(d, idQuesto)))));
    }
    return scheda;
  }

  /** (v3) Il figlio usa Pactum anche su altri dispositivi: certi numeri sono solo di questo computer. */
  function altriDispositivi() {
    const questo = questoDispositivo();
    const dispositivi = (S.patto && Array.isArray(S.patto.dispositivi)) ? S.patto.dispositivi : [];
    return dispositivi.some((d) => !questo || d.id !== questo.id);
  }

  function bloccoRegoleOggi(patto) {
    const regole = T.ordinaRegole(regoleDiQuesto(patto));
    const sezione = h('section', { class: 'blocco', 'aria-labelledby': 'titolo-regole-oggi' },
      sopratitolo('LE TUE REGOLE OGGI', 'titolo-regole-oggi'));
    if (!regole.length) {
      sezione.append(
        h('p', { class: 'secondario' }, 'Su questo computer non ci sono ancora regole. Le scrivi tu: un limite su un programma o su un sito, una fascia senza computer, o un impegno di vita reale.'),
        h('div', { class: 'azioni' }, h('button', {
          type: 'button', class: 'bottone primario con-icona', chiave: 'prima-regola',
          onclick: () => { vaiA('regole'); dialogoRegola(null); },
        }, icona('piu'), 'Scrivi una regola')));
      return sezione;
    }
    sezione.append(h('ul', { class: 'righe-regole' }, regole.map((r) => rigaRegolaOggi(r, patto))));
    const b = patto.bonus;
    if (pattoDiOggi() && b && b.giorno && b.settimana && regole.some((r) => r.tipo === 'limite_tempo')) {
      // I tetti valgono per dispositivo: con altri dispositivi lo si dice.
      sezione.append(h('p', { class: 'piccolo secondario' },
        (altriDispositivi() ? 'Bonus su questo computer: ' : 'Bonus: ') +
        'oggi ancora ' + b.giorno.residui + ' min su ' + b.giorno.tetto +
        ' · questa settimana ' + b.settimana.residui + ' su ' + b.settimana.tetto));
    }
    return sezione;
  }

  function rigaRegolaOggi(regola, patto) {
    if (regola.tipo === 'limite_tempo') return rigaTempo(regola, patto);
    if (regola.tipo === 'fascia_oraria') {
      return h('li', { class: 'riga-regola' },
        h('p', { class: 'riga-titolo' }, descrizione(regola)),
        h('p', { class: 'secondario' }, T.testoMomento(momentoDi(regola)) || ''));
    }
    if (regola.tipo === 'vita_reale') {
      const oggi = oggiPatto();
      const diOggi = tutteDichiarazioni().find((d) => d.regola_id === regola.id && d.giorno === oggi);
      return h('li', { class: 'riga-regola' },
        h('p', { class: 'riga-titolo' }, descrizione(regola)),
        diOggi
          ? h('p', { class: 'secondario' }, 'Oggi: ' + (diOggi.verdetto && diOggi.verdetto.registro
            ? diOggi.verdetto.registro
            : T.testoStatoDichiarazione(diOggi, arbitroDi(regola))))
          : h('a', { href: '#diario', class: 'link-freccia', chiave: 'diario-da-oggi-' + regola.id }, 'Segna nel Diario', icona('freccia')));
    }
    return h('li', { class: 'riga-regola' }, h('p', { class: 'riga-titolo' }, descrizione(regola)));
  }

  function rigaTempo(regola, patto) {
    const p = regola.parametri || {};
    const nome = T.nomeBersaglio(p.app_o_categoria, nomiPer(regola));
    const misura = misuraRegola(regola);
    const residuo = residuoBonus();
    const bonusOggi = bonusOggiDi(regola);
    const aperto = S.bonus && S.bonus.regolaId === regola.id;
    const chiaveMessaggio = 'bonus-' + regola.id;

    const valore = misura.minuti === null
      ? 'limite ' + T.durata(misura.limite) + ' · misura in arrivo'
      : T.durata(misura.minuti) + ' su ' + T.durata(misura.limite);
    const riga = h('li', { class: 'riga-regola' },
      h('div', { class: 'riga-testa' }, h('p', { class: 'riga-titolo' }, nome), h('p', { class: 'riga-numero' }, valore)),
      barra(misura.minuti || 0, misura.limite));
    if (misura.oltre > 0) riga.append(h('p', { class: 'riga-chip' }, chip(T.durata(misura.oltre) + ' oltre', 'secondario')));

    const bottoni = h('div', { class: 'bottoni-bonus', role: 'group', 'aria-label': 'Bonus su ' + nome });
    for (const minuti of [5, 15, 30]) {
      const scelto = aperto && S.bonus.minuti === minuti;
      bottoni.append(h('button', {
        type: 'button',
        class: 'bottone tonale compatto' + (scelto ? ' scelto' : ''),
        chiave: 'bonus-' + regola.id + '-' + minuti,
        disabled: (residuo !== null && minuti > residuo) || (S.bonus && S.bonus.invio) || false,
        'aria-pressed': aperto ? String(scelto) : null,
        'aria-label': 'Ti dai ' + minuti + ' minuti di bonus su ' + nome,
        onclick: () => apriBonus(regola, minuti),
      }, '+' + minuti + ' min'));
    }
    riga.append(bottoni);

    let testoBonus = null;
    if (bonusOggi > 0 && residuo !== null) testoBonus = 'oggi ti sei già dato ' + bonusOggi + ' min · restano ' + residuo;
    else if (bonusOggi > 0) testoBonus = 'oggi ti sei già dato ' + bonusOggi + ' min';
    else if (residuo !== null) testoBonus = 'bonus di oggi: restano ' + residuo + ' min';
    if (testoBonus) riga.append(h('p', { class: 'piccolo secondario' }, testoBonus));

    if (aperto) riga.append(pannelloBonus(regola, nome));
    else if (S.messaggi[chiaveMessaggio]) riga.append(notaMessaggio(S.messaggi[chiaveMessaggio]));
    return riga;
  }

  function apriBonus(regola, minuti) {
    delete S.messaggi['bonus-' + regola.id];
    S.bonus = { regolaId: regola.id, minuti, invio: false };
    S.fuocoDopo = 'bonus-perche-' + regola.id;
    render();
  }

  function chiudiBonus() {
    const regolaId = S.bonus && S.bonus.regolaId;
    const minuti = S.bonus && S.bonus.minuti;
    S.bonus = null;
    if (regolaId) S.fuocoDopo = 'bonus-' + regolaId + '-' + minuti;
    render();
  }

  /** Il perché, facoltativo, si apre dopo il tocco: arriva al genitore col bonus. */
  function pannelloBonus(regola, nome) {
    const minuti = S.bonus.minuti;
    const chiaveCampo = 'bonus-perche-' + regola.id;
    const titoloId = 'titolo-bonus-' + regola.id;
    const campo = h('input', {
      type: 'text', id: chiaveCampo, class: 'campo', chiave: chiaveCampo, maxlength: 200, autocomplete: 'off',
      value: S.bozze[chiaveCampo] || '',
      oninput: (e) => { S.bozze[chiaveCampo] = e.target.value; },
    });
    const messaggio = S.messaggi['bonus-' + regola.id];
    return h('form', {
      class: 'pannello', 'aria-labelledby': titoloId,
      onsubmit: (e) => { e.preventDefault(); daiBonus(regola, nome, minuti, campo.value); },
      onkeydown: (e) => { if (e.key === 'Escape' && !S.bonus.invio) { e.preventDefault(); chiudiBonus(); } },
    },
    h('p', { class: 'pannello-titolo', id: titoloId }, 'Ti dai ' + minuti + ' minuti su ' + nome),
    h('p', { class: 'secondario' }, 'Il perché arriva al genitore insieme al bonus. Puoi anche non scriverlo.'),
    h('label', { class: 'etichetta-campo', for: chiaveCampo }, 'Il tuo perché (facoltativo)'),
    campo,
    messaggio ? notaMessaggio(messaggio) : null,
    h('div', { class: 'azioni' },
      h('button', { type: 'submit', class: 'bottone primario', chiave: 'bonus-manda-' + regola.id, disabled: S.bonus.invio },
        S.bonus.invio ? 'Invio…' : 'Mi do ' + minuti + ' minuti'),
      h('button', { type: 'button', class: 'bottone testo', chiave: 'bonus-annulla-' + regola.id, disabled: S.bonus.invio, onclick: chiudiBonus }, 'Annulla')));
  }

  async function daiBonus(regola, nome, minuti, motivo) {
    if (!S.bonus || S.bonus.invio) return;
    const chiaveMessaggio = 'bonus-' + regola.id;
    delete S.messaggi[chiaveMessaggio];
    S.bonus.invio = true;
    render();
    const corpo = { regola_id: regola.id, minuti };
    const perche = String(motivo || '').trim();
    if (perche) corpo.motivo = perche;
    const r = await Api.post('/locale/bonus', corpo);
    if (r.ok && r.dati && r.dati.ok === true) {
      S.bonus = null;
      delete S.bozze['bonus-perche-' + regola.id];
      S.fuocoDopo = 'bonus-' + regola.id + '-' + minuti;
      avviso('Ti sei dato ' + minuti + ' minuti su ' + nome + '.');
      await aggiornaTutto();
      return;
    }
    const errore = (r.dati && r.dati.errore) || (r.rete ? 'rete' : null);
    S.messaggi[chiaveMessaggio] = testoErroreBonus(errore, r.dati && r.dati.dettagli, minuti);
    if (errore === 'rete') {
      // Il bonus non è partito: il pannello resta aperto per riprovare.
      S.bonus.invio = false;
      S.fuocoDopo = 'bonus-manda-' + regola.id;
    } else {
      S.bonus = null;
      S.fuocoDopo = 'bonus-' + regola.id + '-5';
      if (errore === 'tetto_superato' || errore === 'regola_non_valida') aggiornaTutto();
    }
    render();
  }

  /** Gli esiti del bonus nelle parole del patto: una scelta già fatta, non una violazione. */
  function testoErroreBonus(errore, dettagli, minuti) {
    if (errore === 'tetto_superato') {
      const d = T.erroreDi(dettagli) || dettagli || {};
      const b = (S.patto && S.patto.bonus) || {};
      const giorno = T.numero(d.residuo_giorno) !== null ? T.numero(d.residuo_giorno) : T.numero(b.giorno && b.giorno.residui);
      const settimana = T.numero(d.residuo_settimana) !== null ? T.numero(d.residuo_settimana) : T.numero(b.settimana && b.settimana.residui);
      if (giorno !== null && giorno >= minuti && settimana !== null) return 'Questa settimana ti restano ' + settimana + ' minuti di bonus.';
      if (giorno !== null && giorno > 0) return 'Oggi ti restano ' + giorno + ' minuti di bonus.';
      const tetto = T.numero(b.giorno && b.giorno.tetto);
      return tetto ? 'Ti sei già dato i ' + tetto + ' minuti di oggi.' : 'Per oggi i minuti di bonus sono finiti.';
    }
    if (errore === 'regola_non_valida') return 'Questa regola non vale più per un bonus: aggiorna e riprova.';
    if (errore === 'rete') return 'Niente rete: il bonus non è partito. Riprova quando torna la connessione.';
    return 'Non sono riuscito a darti il bonus: riprova.';
  }

  function bloccoTempo() {
    const o = S.oggi;
    const sezione = h('section', { class: 'blocco', 'aria-labelledby': 'titolo-tempo' },
      h('div', { class: 'testa-blocco' },
        // Con altri dispositivi: questi minuti sono solo del computer.
        sopratitolo(altriDispositivi() ? 'DOVE È FINITO IL TEMPO SUL COMPUTER' : 'DOVE È FINITO IL TEMPO', 'titolo-tempo'),
        o ? h('p', { class: 'totale' }, T.durata(o.totale_minuti)) : null));
    if (!o) {
      sezione.append(caricamento('Sto leggendo l\'uso di oggi…'));
      return sezione;
    }
    const nomi = nomiProgrammi();
    const programmi = (o.programmi || []).filter((p) => (T.numero(p.minuti) || 0) >= 1)
      .sort((a, b) => b.minuti - a.minuti);
    const siti = (o.siti || []).filter((s) => (T.numero(s.minuti) || 0) >= 1)
      .sort((a, b) => b.minuti - a.minuti || String(a.dominio).localeCompare(String(b.dominio)));
    if (!programmi.length && !siti.length) {
      sezione.append(rigaVuota('spunta', 'Nessun uso registrato finora. Oggi il registro è pulito.'));
    }
    if (programmi.length) {
      sezione.append(h('h3', { class: 'sottotitolo' }, 'Programmi'),
        righeValore(programmi.map((p) => [p.nome || T.nomeBersaglio(p.chiave, nomi), T.durata(p.minuti)])));
    }
    if (siti.length) {
      sezione.append(h('h3', { class: 'sottotitolo' }, 'Siti'),
        h('p', { class: 'piccolo secondario' }, 'Il tempo sui siti è già compreso in quello del browser.'),
        righeValore(siti.map((s) => [s.dominio, T.durata(s.minuti) + ' · ' + T.visite(s.visite)])));
    }
    if (o.siti_non_leggibili) {
      sezione.append(h('p', { class: 'nota-onesta' }, 'Per una parte di oggi il programma non è riuscito a leggere i siti: quelli di quel periodo non si vedono.'));
    }
    sezione.append(h('p', null, h('a', { href: '#siti', class: 'link-freccia', chiave: 'link-siti' }, 'Tutti i siti degli ultimi 8 giorni', icona('freccia'))));
    return sezione;
  }

  // --- Le mie regole -------------------------------------------------------------------

  function sezioneRegole() {
    const parti = [h('p', { class: 'intro' }, 'Le regole di questo computer e i tuoi impegni di vita reale. Le scrivi tu: il genitore le vede e può solo proporre modifiche. Quelle del telefono stanno nell\'app del telefono.')];
    const patto = S.patto;
    if (!patto) {
      if (!S.pattoNonAggiornato) parti.push(caricamento('Sto leggendo il patto…'));
      return parti;
    }
    const regole = T.ordinaRegole(regoleDiQuesto(patto));
    const delComputer = regole.filter((r) => r.tipo !== 'vita_reale');
    const vitaReale = regole.filter((r) => r.tipo === 'vita_reale');
    if (totaleRegoleFiglio() === 1) {
      parti.push(rigaVuota('info', 'Questa è l\'unica regola del patto: puoi modificarla, ma non eliminarla.'));
    }
    parti.push(sopratitolo('SU QUESTO COMPUTER'));
    if (!delComputer.length) parti.push(rigaVuota('info', 'Nessuna regola su questo computer, per ora. Scrivine una con «Nuova regola».'));
    delComputer.forEach((r) => parti.push(cardRegola(r)));
    parti.push(sopratitolo('VITA REALE'));
    if (!vitaReale.length) parti.push(rigaVuota('info', 'Nessun impegno di vita reale, per ora.'));
    vitaReale.forEach((r) => parti.push(cardRegola(r)));
    return parti;
  }

  function cardRegola(regola) {
    const idDescrizione = 'regola-' + regola.id;
    const card = h('article', { class: 'card', 'aria-labelledby': idDescrizione },
      h('div', { class: 'card-testa' },
        h('span', { class: 'etichetta-tipo' }, T.etichettaTipo(regola.tipo)),
        eConcordata(regola) ? chip('concordata', 'secondario') : null),
      h('p', { class: 'descrizione', id: idDescrizione }, descrizione(regola)));
    const sblocco = T.istante(regola.allentabile_dal);
    if (sblocco && sblocco > new Date()) {
      card.append(h('p', { class: 'piccolo secondario' }, 'Allentabile ' + T.dalQuando(sblocco)));
    }
    if (Array.isArray(regola.semaforo) && regola.semaforo.length) {
      card.append(h('div', { class: 'riga-striscia' },
        striscia(regola.semaforo, { piccola: true, descrizione: T.fraseConteggio(regola.semaforo, 'dentro questa regola') }),
        h('span', { class: 'conteggio', 'aria-hidden': 'true' }, T.conteggioBreve(regola.semaforo))));
    }
    card.append(h('div', { class: 'azioni-card' },
      h('button', { type: 'button', class: 'bottone testo', chiave: 'modifica-' + regola.id, 'aria-describedby': idDescrizione, onclick: () => dialogoRegola(regola) }, 'Modifica'),
      h('button', { type: 'button', class: 'bottone testo', chiave: 'elimina-' + regola.id, 'aria-describedby': idDescrizione, onclick: () => dialogoElimina(regola) }, 'Elimina')));
    return card;
  }

  function sbloccoDa(errore, regola) {
    if (errore && errore.sblocco_ts && T.istante(errore.sblocco_ts)) return T.istante(errore.sblocco_ts);
    if (errore && T.numero(errore.secondi_rimanenti) !== null) return new Date(Date.now() + errore.secondi_rimanenti * 1000);
    return regola ? T.istante(regola.allentabile_dal) : null;
  }

  function testoErroreRegola(r, contesto) {
    const eliminazione = Boolean(contesto && contesto.eliminazione);
    if (r.rete) {
      return eliminazione
        ? 'Niente rete: la regola non è stata eliminata. Riprova quando torna la connessione.'
        : 'Niente rete: la regola non è stata salvata. Riprova quando torna la connessione.';
    }
    const e = T.erroreDi(r.dati);
    if (r.stato === 409 && e && e.errore === 'lock_attivo') {
      const sblocco = sbloccoDa(e, contesto && contesto.regola);
      const quando = sblocco ? T.dalQuando(sblocco) : 'tra ' + T.testoAttesa(e.secondi_rimanenti);
      return eliminazione
        ? 'Eliminare è l\'allentamento massimo: potrai farlo ' + quando + '.'
        : 'Questa modifica allenta la regola: potrai allentarla ' + quando + '. Stringerla invece si può sempre.';
    }
    if (r.stato === 409 && e && e.errore === 'ultima_regola') {
      return 'Il patto vuole almeno una regola: creane un\'altra prima di eliminare questa.';
    }
    if (r.stato === 422) {
      return JSON.stringify(r.dati || '').includes('app_o_categoria')
        ? 'Questo limite non va bene per un computer: scegli un programma, un sito o una categoria.'
        : 'Il server non ha accettato la regola: controlla i campi e riprova.';
    }
    if (r.stato === 404) return 'Questa regola non c\'è più nel patto: la lista si aggiorna da sola.';
    if (r.stato === 401) return 'Il server non riconosce più questo computer: serve un codice nuovo per ricollegarlo.';
    return eliminazione
      ? 'Non sono riuscito a eliminare la regola: riprova.'
      : 'Non sono riuscito a salvare: controlla i campi e la connessione, poi riprova.';
  }

  // --- Proposte ---------------------------------------------------------------------------

  function sezioneProposte() {
    const parti = [h('p', { class: 'intro' }, 'Il genitore non cambia le tue regole: può solo proporre. Decidi tu, e la tua risposta arriva nella sua app.')];
    if (!S.patto && !S.proposte) {
      if (!S.pattoNonAggiornato) parti.push(caricamento('Sto leggendo le proposte…'));
      return parti;
    }
    const daDecidere = pendenti();
    parti.push(titoloSezione('Da decidere'));
    if (!daDecidere.length) parti.push(rigaVuota('spunta', 'Nessuna proposta in attesa della tua risposta.'));
    daDecidere.forEach((p) => parti.push(cardPropostaPendente(p)));

    const storia = (S.proposte || []).filter((p) => p.stato !== 'pendente');
    parti.push(titoloSezione('Storia'));
    if (!storia.length) parti.push(rigaVuota('info', S.proposte ? 'Nessuna proposta ancora.' : 'La storia delle proposte arriva appena il server risponde.'));
    storia.forEach((p) => parti.push(cardPropostaStorica(p)));
    return parti;
  }

  function chipDirezione(direzione) {
    const testo = T.etichettaDirezione(direzione);
    if (!testo) return null;
    const variante = direzione === 'stringe' ? 'stringe' : direzione === 'allenta' ? 'allenta' : 'elimina';
    return chip(testo, variante);
  }

  function doveVale(regola) {
    if (!regola) return null;
    if (regola.tipo === 'vita_reale') return 'Impegno di vita reale';
    const dispositivo = regola.dispositivo ||
      ((S.patto && S.patto.dispositivi) || []).find((d) => d.id === regola.dispositivo_id) || null;
    const questo = questoDispositivo();
    if (!dispositivo) return null;
    if (questo && dispositivo.id === questo.id) return 'Su questo computer';
    if (dispositivo.tipo === 'telefono') return dispositivo.nome && dispositivo.nome !== 'Telefono' ? 'Sul telefono «' + dispositivo.nome + '»' : 'Sul telefono';
    return 'Sul computer «' + (dispositivo.nome || 'Computer') + '»';
  }

  function iconaDispositivo(regola) {
    if (!regola || regola.tipo === 'vita_reale') return icona('persona');
    const tipo = (regola.dispositivo && regola.dispositivo.tipo) ||
      (((S.patto && S.patto.dispositivi) || []).find((d) => d.id === regola.dispositivo_id) || {}).tipo;
    return icona(tipo === 'telefono' ? 'telefono' : 'computer');
  }

  function descriviPerProposta(regola, parametri) {
    return T.descrizioneRegola({ tipo: regola.tipo, parametri, dispositivo: regola.dispositivo }, { nomi: nomiProgrammi(), tipoDispositivo: 'computer' });
  }

  function cardPropostaPendente(p) {
    const regola = regolaPerId(p.regola_id);
    const chiave = 'proposta-' + p.id;
    const chiaveMotivazione = 'motivazione-' + p.id;
    const inInvio = Boolean(S.invii[chiave]);
    const idTitolo = 'proposta-titolo-' + p.id;
    const eliminazione = T.eEliminazione(p);

    const card = h('article', { class: 'card', 'aria-labelledby': idTitolo },
      h('div', { class: 'card-testa' }, h('span', { class: 'etichetta-tipo' }, 'PROPOSTA DEL GENITORE'), chipDirezione(p.direzione)));
    if (eliminazione && regola) {
      card.append(h('p', { class: 'confronto', id: idTitolo }, 'Propone di togliere la regola: ' + descrizione(regola)));
    } else {
      card.append(h('p', { class: 'confronto', id: idTitolo }, p.confronto || 'Proposta di modifica'));
    }
    const dove = doveVale(regola);
    if (dove) card.append(h('p', { class: 'dove' }, iconaDispositivo(regola), dove));
    if (regola && !eliminazione) {
      card.append(h('p', null, 'Ora: ' + descrizione(regola)));
      const dopo = p.parametri_proposti;
      if (dopo && typeof dopo === 'object' && Object.keys(dopo).length && !T.uguali(dopo, regola.parametri)) {
        card.append(h('p', null, 'Se accetti: ' + descriviPerProposta(regola, dopo)));
      }
    }
    if (p.motivazione) card.append(h('p', { class: 'secondario' }, 'Il genitore dice: ' + p.motivazione));
    const quando = T.dataOraBreve(p.ts_server);
    if (quando) card.append(h('p', { class: 'piccolo secondario' }, capitale(quando)));

    if (S.motivazioniAperte[p.id]) {
      card.append(
        h('label', { class: 'etichetta-campo', for: chiaveMotivazione }, 'La tua motivazione (facoltativa)'),
        h('input', {
          type: 'text', class: 'campo', id: chiaveMotivazione, chiave: chiaveMotivazione, maxlength: 300, autocomplete: 'off',
          value: S.bozze[chiaveMotivazione] || '', disabled: inInvio,
          oninput: (e) => { S.bozze[chiaveMotivazione] = e.target.value; },
        }));
    } else {
      // Chiusa di partenza: un campo sempre aperto farebbe credere che serva giustificarsi.
      card.append(h('p', null, h('button', {
        type: 'button', class: 'bottone collegamento', chiave: 'apri-motivazione-' + p.id,
        onclick: () => { S.motivazioniAperte[p.id] = true; S.fuocoDopo = chiaveMotivazione; render(); },
      }, 'aggiungi una motivazione')));
    }
    if (S.messaggi[chiave]) card.append(notaMessaggio(S.messaggi[chiave]));
    card.append(h('div', { class: 'azioni azioni-pari' },
      h('button', { type: 'button', class: 'bottone primario', chiave: 'accetta-' + p.id, disabled: inInvio, 'aria-describedby': idTitolo, onclick: () => rispondiProposta(p, 'accetta') }, 'Accetto'),
      h('button', { type: 'button', class: 'bottone contorno', chiave: 'rifiuta-' + p.id, disabled: inInvio, 'aria-describedby': idTitolo, onclick: () => rispondiProposta(p, 'rifiuta') }, 'Rifiuto')));
    return card;
  }

  function cardPropostaStorica(p) {
    const regola = regolaPerId(p.regola_id);
    const card = h('article', { class: 'card card-quieta' },
      h('div', { class: 'card-testa' }, h('span', { class: 'etichetta-tipo' }, T.etichettaStatoProposta(p.stato).toUpperCase()), chipDirezione(p.direzione)));
    if (p.confronto) card.append(h('p', { class: 'descrizione' }, p.confronto));
    const dove = doveVale(regola);
    if (dove) card.append(h('p', { class: 'dove' }, iconaDispositivo(regola), dove));
    if (regola) card.append(h('p', null, 'Regola: ' + descrizione(regola)));
    else if (p.stato === 'annullata') card.append(h('p', { class: 'secondario' }, 'La regola era già stata tolta dal patto.'));
    if (p.motivazione) card.append(h('p', { class: 'secondario' }, 'Il genitore dice: ' + p.motivazione));
    if (p.risposta) {
      card.append(h('p', null, p.risposta.esito === 'accetta' ? 'Hai accettato' : 'Hai rifiutato'));
      if (p.risposta.motivazione) card.append(h('p', { class: 'secondario' }, 'Hai detto: ' + p.risposta.motivazione));
    }
    const quando = T.dataOraBreve(p.ts_server);
    if (quando) card.append(h('p', { class: 'piccolo secondario' }, capitale(quando)));
    return card;
  }

  async function rispondiProposta(p, esito) {
    const chiave = 'proposta-' + p.id;
    if (S.invii[chiave]) return;
    S.invii[chiave] = true;
    delete S.messaggi[chiave];
    render();
    const corpo = { esito };
    const motivazione = String(S.bozze['motivazione-' + p.id] || '').trim();
    if (motivazione) corpo.motivazione = motivazione;
    const r = await Api.post('/server/api/proposte/' + p.id + '/risposta', corpo);
    if (r.ok) {
      delete S.bozze['motivazione-' + p.id];
      delete S.motivazioniAperte[p.id];
      avviso(esito === 'accetta' ? 'Proposta accettata: la regola è già aggiornata.' : 'Proposta rifiutata. Il genitore riceve la tua risposta.');
      await aggiornaTutto();
      delete S.invii[chiave];
      S.fuocoTitolo = true;
      render();
      return;
    }
    delete S.invii[chiave];
    const e = T.erroreDi(r.dati);
    if (r.rete) S.messaggi[chiave] = 'Niente rete: la risposta non è partita. Riprova quando torna la connessione.';
    else if (e && e.errore === 'proposta_non_pendente') S.messaggi[chiave] = 'Questa proposta non è più in attesa.';
    else if (e && e.errore === 'ultima_regola') S.messaggi[chiave] = 'Il patto vuole almeno una regola: questa non si può togliere finché è l\'unica.';
    else S.messaggi[chiave] = 'Non sono riuscito a inviare la risposta: riprova.';
    S.fuocoDopo = (esito === 'accetta' ? 'accetta-' : 'rifiuta-') + p.id;
    render();
    if (e && e.errore === 'proposta_non_pendente') aggiornaTutto();
  }

  // --- Diario ------------------------------------------------------------------------------

  function sezioneDiario() {
    const parti = [h('p', { class: 'intro' }, 'Com\'è andata con i tuoi impegni di vita reale, detto a viso aperto. Un successo aspetta la firma dell\'arbitro; un "non ce l\'ho fatta" è creduto sulla parola.')];
    const patto = S.patto;
    if (!patto) {
      if (!S.pattoNonAggiornato) parti.push(caricamento('Sto leggendo le dichiarazioni…'));
      return parti;
    }
    const oggi = oggiPatto();
    const regole = T.ordinaRegole(regoleDiQuesto(patto)).filter((r) => r.tipo === 'vita_reale');
    const dichiarazioni = tutteDichiarazioni();

    parti.push(titoloSezione('Le tue regole di vita reale'));
    if (!regole.length) parti.push(rigaVuota('info', 'Nessuna regola di vita reale nel patto. Puoi crearne una in Le mie regole.'));
    const giaMostrate = new Set();
    regole.forEach((r) => {
      const diOggi = dichiarazioni.find((d) => d.regola_id === r.id && d.giorno === oggi);
      if (diOggi && diOggi.esito === 'successo' && ['in_attesa', 'confermata', 'confermata_per_conto'].includes(diOggi.stato)) {
        giaMostrate.add(diOggi.id);
        parti.push(cardFatto(diOggi, r, oggi));
      } else {
        parti.push(cardVitaReale(r, diOggi));
      }
    });

    parti.push(titoloSezione('Le tue dichiarazioni'));
    const restanti = dichiarazioni.filter((d) => !giaMostrate.has(d.id));
    if (!dichiarazioni.length) parti.push(rigaVuota('info', 'Nessuna dichiarazione ancora.'));
    restanti.filter((d) => d.stato === 'in_attesa').forEach((d) => parti.push(cardFatto(d, regolaPerId(d.regola_id), oggi)));
    const risolte = restanti.filter((d) => d.stato !== 'in_attesa');
    if (risolte.length) parti.push(h('ul', { class: 'lista-dichiarazioni' }, risolte.map((d) => rigaDichiarazione(d, oggi))));
    return parti;
  }

  function cardVitaReale(regola, diOggi) {
    const id = 'vita-' + regola.id;
    const card = h('article', { class: 'card', 'aria-labelledby': id }, h('p', { class: 'descrizione', id }, descrizione(regola)));
    const messaggio = S.messaggi['diario-' + regola.id];
    if (diOggi) {
      card.append(h('p', { class: 'secondario' }, 'Per oggi hai già dichiarato: si torna domani.'));
    } else {
      card.append(h('div', { class: 'azioni azioni-pari' },
        // "Ce l'ho fatta" è l'azione principale: pieno. L'altra col bordo.
        h('button', { type: 'button', class: 'bottone primario', chiave: 'successo-' + regola.id, 'aria-describedby': id, onclick: () => dialogoDichiarazione(regola, 'successo') }, 'Ce l\'ho fatta'),
        h('button', { type: 'button', class: 'bottone contorno', chiave: 'fallimento-' + regola.id, 'aria-describedby': id, onclick: () => dialogoDichiarazione(regola, 'fallimento') }, 'Non ce l\'ho fatta')));
    }
    if (messaggio) card.append(notaMessaggio(messaggio));
    return card;
  }

  /** Il successo riconosciuto subito: spunta e "L'hai fatto. Manca la firma di…". */
  function cardFatto(dichiarazione, regola, oggi) {
    const registro = dichiarazione.verdetto && dichiarazione.verdetto.registro;
    const card = h('article', { class: 'card card-fatto' });
    if (regola) card.append(h('p', { class: 'card-titolo' }, descrizione(regola)));
    card.append(h('p', { class: 'fatto' }, icona('spunta'),
      h('span', null, registro ? capitale(registro) : T.testoStatoDichiarazione(dichiarazione, arbitroDi(regola)))));
    if (dichiarazione.giorno) card.append(h('p', { class: 'piccolo' }, 'Giorno: ' + T.giornoBreve(dichiarazione.giorno, oggi)));
    if (dichiarazione.nota) card.append(h('p', null, 'La tua nota: ' + dichiarazione.nota));
    if (dichiarazione.ottimista) card.append(h('p', { class: 'piccolo' }, 'Sto mandando la dichiarazione…'));
    return card;
  }

  /** Una dichiarazione chiusa: una riga, con la frase congelata dal server. */
  function rigaDichiarazione(dichiarazione, oggi) {
    const regola = regolaPerId(dichiarazione.regola_id);
    const verdetto = dichiarazione.verdetto;
    const riga = h('li', { class: 'riga-dichiarazione' });
    if (regola) riga.append(h('p', { class: 'riga-titolo' }, descrizione(regola)));
    if (verdetto && verdetto.registro) {
      riga.append(h('p', null, h('span', { class: 'secondario' }, 'Esito della tua dichiarazione: '), verdetto.registro));
    } else {
      riga.append(h('p', null, T.testoStatoDichiarazione(dichiarazione, arbitroDi(regola))));
    }
    if (dichiarazione.giorno) riga.append(h('p', { class: 'piccolo secondario' }, 'Giorno: ' + T.giornoBreve(dichiarazione.giorno, oggi)));
    if (dichiarazione.nota) riga.append(h('p', { class: 'secondario' }, 'La tua nota: ' + dichiarazione.nota));
    if (verdetto && verdetto.nota) riga.append(h('p', { class: 'secondario' }, 'Nota sull\'esito: ' + verdetto.nota));
    return riga;
  }

  async function dichiara(regola, esito, giorno, nota, dialogo) {
    const oggi = oggiPatto();
    const corpo = { regola_id: regola.id, esito };
    if (nota) corpo.nota = nota;
    // Oggi è il valore del server: si manda il giorno solo se è un altro.
    if (giorno && giorno !== oggi) corpo.giorno = giorno;
    const chiaveMessaggio = 'diario-' + regola.id;
    delete S.messaggi[chiaveMessaggio];

    let provvisoria = null;
    if (esito === 'successo') {
      // Il riconoscimento non aspetta il server: la card cambia subito.
      provvisoria = {
        id: 'in-arrivo-' + Date.now(), regola_id: regola.id, giorno: giorno || oggi, esito,
        nota: nota || null, stato: 'in_attesa', verdetto: null, ottimista: true, ts_server: new Date().toISOString(),
      };
      S.dichiarazioniOttimiste.push(provvisoria);
      dialogo.chiudi();
      render();
    } else {
      dialogo.occupato = true;
      dialogo.aggiorna();
    }

    const r = await Api.post('/server/api/dichiarazioni', corpo);
    if (provvisoria) S.dichiarazioniOttimiste = S.dichiarazioniOttimiste.filter((d) => d !== provvisoria);
    if (r.ok) {
      if (r.dati && r.dati.id != null) S.dichiarazioni = [r.dati].concat((S.dichiarazioni || []).filter((d) => d.id !== r.dati.id));
      if (esito === 'successo') {
        avviso('Segnato. Manca solo la firma di ' + arbitroDi(regola) + '.');
      } else {
        dialogo.occupato = false;
        dialogo.chiudi();
        avviso('Registrato, creduto sulla parola.');
      }
      render();
      aggiornaTutto();
      return;
    }
    const e = T.erroreDi(r.dati);
    let testo;
    if (e && e.errore === 'gia_dichiarato') testo = 'Per quel giorno hai già dichiarato su questa regola.';
    else if (e && e.errore === 'giorno_non_valido') testo = 'Si può dichiarare solo per oggi e per i 7 giorni prima.';
    else if (e && e.errore === 'regola_non_valida') testo = 'Questa regola non è più nel patto: la lista si aggiorna da sola.';
    else if (r.rete) testo = esito === 'successo'
      ? 'Niente rete: non sono riuscito a segnarlo. Riprova quando torna la connessione.'
      : 'Niente rete: la dichiarazione non è partita. Riprova quando torna la connessione.';
    else testo = esito === 'successo'
      ? 'Non sono riuscito a segnarlo: la dichiarazione non è arrivata. Riprova.'
      : 'Non sono riuscito a inviare la dichiarazione: riprova.';
    if (esito === 'successo') {
      S.messaggi[chiaveMessaggio] = testo;
      render();
    } else {
      dialogo.occupato = false;
      dialogo.errore(testo);
    }
    if (e) aggiornaTutto();
  }

  // --- Siti ---------------------------------------------------------------------------------

  function sezioneSiti() {
    const parti = [
      h('section', { class: 'card card-onesta', 'aria-label': 'Come funziona' },
        h('p', null, 'Si legge l\'indirizzo della pagina solo per ricavarne il nome del sito, che è l\'unica cosa registrata. Pactum non blocca nessun sito.'),
        h('p', { class: 'secondario' }, 'Questa è esattamente la lista che vede il genitore: nella sua app non c\'è nessun sito che tu non veda qui. Per ogni giorno: il nome del sito, quanti minuti ci hai passato e quante volte ci sei tornato. Nessun orario, nessuna pagina.')),
    ];
    const patto = S.patto;
    if (!patto) {
      if (!S.pattoNonAggiornato) parti.push(caricamento('Sto leggendo la lista dei siti…'));
      return parti;
    }
    const giorni = Array.isArray(patto.siti_recenti) ? patto.siti_recenti.slice().reverse() : [];
    const oggi = oggiPatto();
    const locali = S.oggi && Array.isArray(S.oggi.siti) ? S.oggi.siti.length : 0;
    const diOggi = giorni.find((g) => g.giorno === oggi);
    const arrivati = diOggi && Array.isArray(diOggi.domini) ? diOggi.domini.length : 0;
    if (locali > arrivati) {
      // Quello che il computer ha già visto ma non ancora mandato: il figlio vede sempre almeno quanto il genitore.
      parti.push(h('p', { class: 'piccolo secondario' }, 'Oggi su questo computer finora: ' + locali + ' ' + T.plurale(locali, 'sito', 'siti') +
        '. Il genitore li vede dal prossimo invio, che parte ogni 5 minuti.'));
    }
    if (!giorni.length) {
      parti.push(rigaVuota('info', 'Nessun dato sui siti è ancora arrivato al patto.'));
      return parti;
    }
    giorni.forEach((g) => parti.push(cardGiornoSiti(g, oggi)));
    return parti;
  }

  function cardGiornoSiti(giorno, oggi) {
    const domini = Array.isArray(giorno.domini) ? giorno.domini : [];
    const totale = T.numero(giorno.totale_domini);
    let testoTotale;
    if (totale === null) testoTotale = 'Nessun dato ricevuto';
    else if (totale > domini.length) testoTotale = totale + ' siti diversi (qui i primi ' + domini.length + ')';
    else testoTotale = totale + ' ' + T.plurale(totale, 'sito', 'siti diversi');
    const titoloId = 'siti-' + giorno.giorno;
    const card = h('section', { class: 'card card-quieta', 'aria-labelledby': titoloId },
      h('div', { class: 'testa-blocco' },
        h('h3', { class: 'card-titolo', id: titoloId }, T.giornoEsteso(giorno.giorno, oggi)),
        h('p', { class: 'piccolo secondario' }, testoTotale)));
    if (giorno.dns_cifrato) {
      card.append(h('p', { class: 'nota-onesta' }, 'Per una parte di questo giorno il programma non è riuscito a leggere i siti: quelli di quel periodo non si vedono.'));
    }
    if (totale === null) {
      card.append(h('p', { class: 'secondario' }, 'Nessun dato ricevuto per questo giorno: non vuol dire zero siti, vuol dire che non è arrivato niente.'));
    } else if (!domini.length && !giorno.dns_cifrato) {
      card.append(h('p', { class: 'secondario' }, 'Nessun sito visitato.'));
    }
    if (domini.length) {
      card.append(righeValore(domini.map((d) => {
        const minuti = T.numero(d.minuti);
        return [d.dominio, (minuti !== null ? T.durata(minuti) + ' · ' : '') + T.visite(d.visite)];
      })));
    }
    return card;
  }

  // --- Cosa vede tuo padre --------------------------------------------------------------------

  function sezioneCosaVede() {
    const blocco = (titolo, voci) => h('section', { class: 'card', 'aria-label': titolo },
      h('p', { class: 'etichetta-tipo' }, titolo),
      h('ul', { class: 'elenco-puntato' }, voci.map((v) => h('li', null, v))));
    return [
      h('p', { class: 'intro' }, 'Qui c\'è tutto quello che da questo computer arriva nella sua app, detto per intero. Niente di più, niente di nascosto.'),
      blocco('COSA VEDE', [
        'Ogni tuo dispositivo per conto suo: questo computer e, se usi Pactum anche lì, il telefono. Ognuno con le sue regole, i suoi tempi, i suoi bonus e i suoi siti.',
        'Quali dispositivi sono collegati al patto, come si chiamano e con quale versione di Pactum.',
        'Il tempo passato in ogni programma, giorno per giorno negli ultimi 8 giorni, anche per categoria, con la media della settimana e del mese.',
        'I siti: solo il nome del sito, quanti minuti ci hai passato e quante volte ci sei tornato, giorno per giorno. E se per una parte della giornata il programma non è riuscito a leggerli.',
        'Quando il computer è acceso e quando è spento, in sospensione o sei uscito dall\'account. Un computer spento la sera è normale: non è un\'interruzione.',
        'Le interruzioni nella registrazione, e quando: Pactum chiuso mentre il computer era acceso, l\'ora o il fuso orario del computer cambiati a mano, un browser da cui il programma non riesce a leggere i siti.',
        'Le tue regole, anche quelle che hai tolto: come sono cambiate, se le avete concordate e da quando le potrai allentare.',
        'La striscia degli ultimi 8 giorni, la stessa che vedi tu in Oggi, anche per dispositivo e regola per regola.',
        'I giorni fuori regola: su quale regola, di quanti minuti sei andato oltre, o se hai usato il computer in una fascia che ti sei imposto.',
        'I bonus che ti dai: quanti minuti, su quale regola e il perché, se lo scrivi. E quanti minuti di bonus ti sei dato in ciascuno degli ultimi 8 giorni.',
        'Le tue dichiarazioni nel Diario, con la tua nota, e se sono state confermate.',
        'Le tue risposte alle sue proposte, con la tua motivazione se la scrivi.',
        'Quando il programma ha mandato l\'ultimo aggiornamento, e se da più di tre quarti d\'ora non ne manda mentre il computer è acceso.',
        'Un avviso quando crei, cambi o togli una regola, ti dai un bonus, vai oltre una regola, dichiari qualcosa nel Diario, rispondi a una sua proposta, o quando c\'è un\'interruzione nella registrazione.',
      ]),
      blocco('COSA RESTA FUORI', [
        'Gli indirizzi completi delle pagine. Il programma legge l\'indirizzo della pagina aperta per un istante, ne ricava il nome del sito e butta via il resto: non lo salva, non lo manda, non lo mostra. Il genitore vede youtube.com, mai quale video.',
        'I titoli delle pagine e delle finestre: non vengono mai letti.',
        'Quello che guardi, leggi, scrivi o cerchi dentro i programmi e i siti.',
        'I tuoi file, le foto e i video.',
        'I messaggi e le chat.',
        'Gli orari e l\'ordine delle visite ai siti: solo il totale del giorno.',
        'Dove sei: la posizione non viene mai letta.',
      ]),
      // Le stesse tre frasi dell'app del telefono (cosa_vede_computer): stessi fatti, stesse parole.
      blocco('I SITI SUL COMPUTER', [
        'Il programma legge l\'indirizzo nella barra del browser e tiene solo il nome del sito: l\'indirizzo completo non viene mai salvato né mandato.',
        'È diverso dal telefono: sul telefono Pactum l\'indirizzo delle pagine non lo vede proprio. Sul computer il programma lo vede per un istante, ne tiene il nome del sito e butta il resto.',
        'In cambio sul computer si contano anche i minuti passati su ogni sito, non solo quante volte ci vai. Sempre per giorno, mai l\'ora.',
        'Pactum non blocca niente: né programmi né siti.',
      ]),
      blocco('SOLO TUO', [
        'La serie di giorni di fila e il tuo record: si calcolano su questo computer e non partono mai.',
      ]),
    ];
  }

  // --- Impostazioni -------------------------------------------------------------------------

  function sezioneImpostazioni() {
    const m = S.motore || {};
    const figlio = (S.patto && S.patto.figlio) || m.figlio;
    const dispositivo = questoDispositivo();
    const voce = (nome, valore) => [h('dt', null, nome), h('dd', null, valore)];
    const retePresente = m.rete_ok !== false && !S.pattoNonAggiornato;
    return [
      h('section', { class: 'card', 'aria-labelledby': 'imp-collegamento' },
        h('h2', { class: 'card-titolo', id: 'imp-collegamento' }, 'Collegamento al patto'),
        h('dl', { class: 'dettagli' },
          voce('Server', T.nomeServer(m.server) || '—'),
          voce('Figlio', (figlio && figlio.nome) || '—'),
          voce('Questo computer', (dispositivo && dispositivo.nome) || '—'),
          voce('Versione del programma', m.versione || '—'))),
      h('section', { class: 'card', 'aria-labelledby': 'imp-stato' },
        h('h2', { class: 'card-titolo', id: 'imp-stato' }, 'Stato'),
        h('dl', { class: 'dettagli' },
          voce('Rete', retePresente ? 'Collegato' : 'Senza rete: i dati restano sul computer e partono da soli quando torna'),
          voce('Ultimo invio riuscito', capitale(T.dataOraBreve(m.ultimo_invio_ok) || 'mai')),
          voce('Patto letto l\'ultima volta', capitale(T.dataOraBreve(S.pattoOkAlle || m.patto_aggiornato) || 'mai'))),
        h('p', { class: 'secondario' }, 'Il programma manda i dati e rilegge il patto da solo ogni 5 minuti. Se vuoi farlo subito:'),
        h('div', { class: 'azioni' }, h('button', {
          type: 'button', class: 'bottone primario con-icona', chiave: 'aggiorna-adesso',
          disabled: S.aggiornoAdesso, onclick: aggiornaAdesso,
        }, icona('aggiorna'), S.aggiornoAdesso ? 'Aggiorno…' : 'Aggiorna adesso'))),
      h('section', { class: 'card', 'aria-labelledby': 'imp-cosa-vede' },
        h('h2', { class: 'card-titolo', id: 'imp-cosa-vede' }, 'Cosa vede tuo padre'),
        h('p', null, 'Tutto quello che arriva nella sua app, e quello che resta fuori.'),
        h('p', null, h('a', { href: '#cosa-vede', class: 'link-freccia', chiave: 'imp-link-cosa-vede' }, 'Guarda l\'elenco', icona('freccia')))),
      h('section', { class: 'card', 'aria-labelledby': 'imp-chiudere' },
        h('h2', { class: 'card-titolo', id: 'imp-chiudere' }, 'Chiudere Pactum'),
        h('p', null, 'Pactum si chiude dall\'icona vicino all\'orologio. Si può fare, ma il genitore vedrà un\'interruzione nella registrazione.')),
    ];
  }

  // --- Collega questo computer --------------------------------------------------------------

  function schermataCollega() {
    const bozzaServer = S.bozze['collega-server'] !== undefined ? S.bozze['collega-server'] : (S.motore && S.motore.server) || '';
    const bozzaCodice = S.bozze['collega-codice'] || '';
    const messaggio = S.messaggi.collega;
    const inInvio = Boolean(S.invii.collega);
    const campoServer = h('input', {
      type: 'text', id: 'collega-server', class: 'campo', chiave: 'collega-server', value: bozzaServer,
      autocomplete: 'off', spellcheck: 'false', inputmode: 'url', placeholder: 'https://pactum.esempio.it',
      'aria-describedby': 'aiuto-server' + (messaggio && messaggio.campo === 'server' ? ' messaggio-collega' : ''),
      'aria-invalid': messaggio && messaggio.campo === 'server' ? 'true' : null,
      disabled: inInvio,
      oninput: (e) => { S.bozze['collega-server'] = e.target.value; },
    });
    const campoCodice = h('input', {
      type: 'text', id: 'collega-codice', class: 'campo campo-codice', chiave: 'collega-codice', value: bozzaCodice,
      autocomplete: 'one-time-code', inputmode: 'numeric', maxlength: 6, pattern: '[0-9]{6}', placeholder: '000000',
      'aria-describedby': 'aiuto-codice' + (messaggio && messaggio.campo === 'codice' ? ' messaggio-collega' : ''),
      'aria-invalid': messaggio && messaggio.campo === 'codice' ? 'true' : null,
      disabled: inInvio,
      oninput: (e) => {
        const cifre = e.target.value.replace(/\D/g, '').slice(0, 6);
        if (cifre !== e.target.value) e.target.value = cifre;
        S.bozze['collega-codice'] = cifre;
      },
    });
    const modulo = h('form', {
      class: 'modulo', novalidate: true,
      onsubmit: (e) => { e.preventDefault(); collega(campoServer.value, campoCodice.value); },
    },
    h('div', { class: 'campo-gruppo' },
      h('label', { class: 'etichetta-campo', for: 'collega-server' }, 'Indirizzo del server'),
      campoServer,
      h('p', { class: 'aiuto', id: 'aiuto-server' }, 'Te lo dà il genitore, insieme al codice.')),
    h('div', { class: 'campo-gruppo' },
      h('label', { class: 'etichetta-campo', for: 'collega-codice' }, 'Codice di 6 cifre'),
      campoCodice,
      h('p', { class: 'aiuto', id: 'aiuto-codice' }, 'Il genitore crea il codice dalla sua app: 6 cifre, vale 15 minuti e si usa una volta sola.')),
    messaggio ? notaMessaggio(messaggio.testo, 'messaggio-collega') : null,
    h('div', { class: 'azioni' },
      h('button', { type: 'submit', class: 'bottone primario largo', chiave: 'collega-invia', disabled: inInvio }, inInvio ? 'Collego…' : 'Collega')),
    S.ricollega && S.motore && S.motore.abbinato
      ? h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'bottone testo', chiave: 'collega-annulla', onclick: () => { S.ricollega = false; delete S.messaggi.collega; render(); } }, 'Annulla'))
      : null);

    // Era già collegato (il motore ricorda il server) ma il server non lo riconosce più.
    const scollegato = S.motore && S.motore.abbinato === false && Boolean(S.motore.server);
    return h('div', { class: 'collega' },
      h('p', { class: 'marchio-nome' }, 'Pactum'),
      h('h1', { class: 'eroe', id: 'titolo-pagina', chiave: 'titolo-pagina', tabindex: '-1' }, 'Collega questo computer'),
      scollegato || S.ricollega
        ? h('p', { class: 'riga-grigia' }, icona('info'), h('span', null, 'Questo computer non è più collegato al patto: chiedi al genitore un codice nuovo.'))
        : null,
      h('p', { class: 'intro' }, 'Il computer entra nel tuo patto come un dispositivo tuo, con le sue regole. Pactum misura e racconta, non blocca niente.'),
      S.modalita === 'prova' ? h('p', { class: 'piccolo secondario' }, 'Modalità di prova: il codice 123456 funziona, 000000 no, 999999 fa scattare «troppi tentativi».') : null,
      modulo,
      h('p', null, h('button', { type: 'button', class: 'bottone collegamento', chiave: 'collega-cosa-vede', onclick: dialogoCosaVede },
        'Prima di collegarlo: cosa vedrà tuo padre')));
  }

  async function collega(testoServer, testoCodice) {
    if (S.invii.collega) return;
    const server = T.normalizzaServer(testoServer);
    const codice = String(testoCodice || '').replace(/\D/g, '');
    if (!String(testoServer || '').trim()) {
      S.messaggi.collega = { testo: 'Scrivi l\'indirizzo del server: te lo dà il genitore.', campo: 'server' };
      S.fuocoDopo = 'collega-server';
      render();
      return;
    }
    if (!server) {
      S.messaggi.collega = { testo: 'L\'indirizzo del server non è giusto: controlla com\'è scritto (per esempio https://pactum.esempio.it).', campo: 'server' };
      S.fuocoDopo = 'collega-server';
      render();
      return;
    }
    if (codice.length !== 6) {
      S.messaggi.collega = { testo: 'Il codice è fatto di 6 cifre.', campo: 'codice' };
      S.fuocoDopo = 'collega-codice';
      render();
      return;
    }
    S.invii.collega = true;
    delete S.messaggi.collega;
    render();
    const r = await Api.post('/locale/abbina', { server, codice });
    delete S.invii.collega;
    if (r.ok && r.dati && r.dati.ok === true) {
      S.bozze = {};
      S.ricollega = false;
      S.revocato = false;
      S.patto = null;
      S.pattoOkAlle = null;
      S.pattoNonAggiornato = false;
      const figlio = r.dati.figlio && r.dati.figlio.nome;
      const dispositivo = r.dati.dispositivo && r.dati.dispositivo.nome;
      avviso(figlio && dispositivo ? 'Collegato come: ' + dispositivo + ' di ' + figlio + '.' : 'Collegato al patto.');
      S.sezione = 'oggi';
      if (location.hash !== '#oggi') history.replaceState(null, '', '#oggi');
      S.fuocoTitolo = true;
      await aggiornaTutto();
      caricaVisti().then(render);
      return;
    }
    const errore = (r.dati && r.dati.errore) || (r.rete ? 'motore' : null);
    const attesa = T.numero(r.dati && (r.dati.riprova_tra_secondi || (r.dati.dettagli && r.dati.dettagli.riprova_tra_secondi)));
    // Le stesse frasi del telefono (collega_codice_non_valido, collega_troppi_tentativi).
    const testi = {
      codice_non_valido: { testo: 'Codice non valido: forse è sbagliato, è scaduto (vale 15 minuti) o è già stato usato. Chiedine uno nuovo al genitore.', campo: 'codice' },
      tipo_non_corrispondente: { testo: 'Questo codice è per un telefono, non per questo computer: chiedi il codice giusto.', campo: 'codice' },
      troppi_tentativi: { testo: 'Troppi codici sbagliati: il server ne accetta di nuovo tra ' + (attesa ? T.testoAttesa(attesa) : 'una decina di minuti') + ', anche quello giusto. Riprova più tardi.', campo: 'codice' },
      rete: { testo: 'Niente rete: il computer non riesce a raggiungere il server. Controlla la connessione e riprova.', campo: null },
      indirizzo_non_valido: { testo: 'L\'indirizzo del server non è giusto: controlla com\'è scritto (per esempio https://pactum.esempio.it).', campo: 'server' },
      motore: { testo: 'Il programma non ha risposto: riprova tra qualche secondo.', campo: null },
    };
    S.messaggi.collega = testi[errore] || { testo: 'Non sono riuscito a collegare il computer: riprova.', campo: null };
    S.fuocoDopo = S.messaggi.collega.campo ? 'collega-' + S.messaggi.collega.campo : 'collega-invia';
    render();
  }

  function schermataAttesa() {
    return h('div', { class: 'collega' },
      h('p', { class: 'marchio-nome' }, 'Pactum'),
      h('h1', { class: 'eroe', id: 'titolo-pagina', chiave: 'titolo-pagina', tabindex: '-1' }, 'Un momento'),
      h('p', { class: 'intro' }, 'Il programma non risponde ancora. Riprovo da solo tra qualche secondo.'),
      caricamento('In attesa del programma…'));
  }

  // --- Dialoghi ----------------------------------------------------------------------------------

  let numeroDialogo = 0;

  /**
   * Un dialogo modale vero (<dialog>): tiene il fuoco dentro, si chiude con
   * Esc, e alla chiusura il fuoco torna al pulsante che l'ha aperto.
   */
  function apriDialogo(opzioni) {
    const n = ++numeroDialogo;
    const attivo = document.activeElement;
    const chiaveInvocatore = attivo && attivo.dataset ? attivo.dataset.chiave : null;
    const titoloId = 'dialogo-' + n + '-titolo';
    const dialogo = h('dialog', { class: 'dialogo', 'aria-labelledby': titoloId });
    const corpo = h('div', { class: 'dialogo-corpo' });
    const messaggio = h('div', { class: 'dialogo-messaggio' });
    dialogo.append(h('h2', { class: 'dialogo-titolo', id: titoloId }, opzioni.titolo), corpo);
    document.body.append(dialogo);

    let chiuso = false;
    /** Una volta sola, qualunque sia la strada (pulsante, Esc): via dal DOM e fuoco a chi l'ha aperto. */
    const congeda = () => {
      if (chiuso) return;
      chiuso = true;
      dialogo.remove();
      const invocatore = cerca(chiaveInvocatore);
      if (invocatore && !invocatore.disabled) invocatore.focus();
      else {
        const titolo = document.getElementById('titolo-pagina');
        if (titolo) titolo.focus();
      }
    };
    const d = {
      corpo,
      messaggio,
      occupato: false,
      chiudi() {
        if (dialogo.open) dialogo.close();
        congeda();
      },
      /** Cerca un campo DENTRO questo dialogo. */
      cerca(selettore) {
        return dialogo.querySelector(selettore);
      },
      errore(testo) {
        messaggio.replaceChildren(notaMessaggio(testo));
        d.aggiorna();
      },
      pulisci() {
        messaggio.replaceChildren();
      },
      aggiorna() {
        if (opzioni.aggiorna) opzioni.aggiorna(d);
      },
    };
    // Esc: si chiude da qui (non si aspetta l'evento "close"), ma mai a metà di un invio.
    dialogo.addEventListener('cancel', (e) => {
      e.preventDefault();
      if (!d.occupato) d.chiudi();
    });
    dialogo.addEventListener('close', congeda);
    opzioni.costruisci(d);
    dialogo.showModal();
    const primo = opzioni.fuoco ? dialogo.querySelector(opzioni.fuoco) : null;
    if (primo) primo.focus();
    return d;
  }

  function bottoniDialogo(annulla, conferma) {
    return h('div', { class: 'azioni-dialogo' }, annulla, conferma);
  }

  /** Nuova regola o modifica. In modifica il tipo resta quello (il contratto non prevede di cambiarlo). */
  function dialogoRegola(regola) {
    const nuova = !regola;
    const p = (regola && regola.parametri) || {};
    const f = {
      tipo: regola ? regola.tipo : 'limite_tempo',
      bersaglio: 'programma',
      programma: '',
      sito: '',
      categoria: '',
      minuti: T.numero(p.minuti_al_giorno) !== null ? String(p.minuti_al_giorno) : '',
      dalle: p.dalle || '',
      alle: p.alle || '',
      giorni: new Set(Array.isArray(p.giorni) ? p.giorni : []),
      descrizione: p.descrizione || '',
      arbitro: p.arbitro_nome || '',
      frequenza: p.frequenza || '',
    };
    if (regola && regola.tipo === 'limite_tempo') {
      const chiave = String(p.app_o_categoria || '');
      const tipo = T.tipoBersaglio(chiave);
      if (tipo === 'programma') { f.bersaglio = 'programma'; f.programma = chiave.toLowerCase(); }
      if (tipo === 'sito') { f.bersaglio = 'sito'; f.sito = chiave.slice('sito:'.length); }
      if (tipo === 'categoria') { f.bersaglio = 'categoria'; f.categoria = chiave.toLowerCase(); }
    }

    apriDialogo({
      titolo: nuova ? 'Nuova regola' : 'Modifica la regola',
      fuoco: nuova ? 'input[name="tipo"]:checked' : '.campi input, .campi select',
      costruisci(d) {
        const zonaTipo = h('div');
        const zonaCampi = h('div', { class: 'campi' });
        const salva = h('button', { type: 'submit', class: 'bottone primario' }, 'Salva');
        const annulla = h('button', { type: 'button', class: 'bottone testo', onclick: () => d.chiudi() }, 'Annulla');
        const modulo = h('form', { class: 'modulo', novalidate: true, onsubmit: (e) => { e.preventDefault(); invia(); } },
          zonaTipo, zonaCampi, d.messaggio, bottoniDialogo(annulla, salva));
        d.corpo.append(modulo);
        d.aggiorna = () => {
          salva.disabled = d.occupato;
          salva.textContent = d.occupato ? 'Salvo…' : 'Salva';
          annulla.disabled = d.occupato;
        };

        if (nuova) zonaTipo.append(sceltaTipo());
        else zonaTipo.append(h('p', { class: 'etichetta-tipo' }, T.etichettaTipo(regola.tipo)));
        disegnaCampi();

        function sceltaTipo() {
          const gruppo = h('fieldset', { class: 'gruppo-scelte' }, h('legend', { class: 'etichetta-campo' }, 'Che regola vuoi darti?'));
          [
            ['limite_tempo', 'Limite di tempo', 'Al massimo tanti minuti al giorno su un programma, un sito o una categoria.'],
            ['fascia_oraria', 'Fascia oraria', 'Niente computer in certe ore.'],
            ['vita_reale', 'Vita reale', 'Un impegno fuori dallo schermo, con un arbitro che lo conferma.'],
          ].forEach(([valore, titolo, spiegazione]) => {
            const id = 'tipo-' + valore;
            gruppo.append(h('label', { class: 'scelta', for: id },
              h('input', {
                type: 'radio', name: 'tipo', id, value: valore, checked: f.tipo === valore,
                onchange: () => { f.tipo = valore; d.pulisci(); disegnaCampi(); },
              }),
              h('span', { class: 'scelta-testi' }, h('span', { class: 'scelta-titolo' }, titolo), h('span', { class: 'scelta-sotto' }, spiegazione))));
          });
          return gruppo;
        }

        function disegnaCampi() {
          const parti = [];
          if (!nuova) {
            const sblocco = T.istante(regola.allentabile_dal);
            if (sblocco && sblocco > new Date()) {
              parti.push(h('p', { class: 'nota-blocco' }, icona('info'),
                h('span', null, 'Stringerla si può sempre; allentarla si potrà ' + T.dalQuando(sblocco) + '.')));
            }
          }
          if (f.tipo === 'limite_tempo') parti.push(...campiLimite());
          if (f.tipo === 'fascia_oraria') parti.push(...campiFascia());
          if (f.tipo === 'vita_reale') parti.push(...campiVitaReale());
          zonaCampi.replaceChildren(...parti);
        }

        function campiLimite() {
          const zonaBersaglio = h('div');
          const gruppo = h('fieldset', { class: 'gruppo-scelte gruppo-in-riga' }, h('legend', { class: 'etichetta-campo' }, 'Su cosa vale il limite?'));
          [['programma', 'Un programma'], ['sito', 'Un sito'], ['categoria', 'Una categoria']].forEach(([valore, testo]) => {
            const id = 'bersaglio-' + valore;
            gruppo.append(h('label', { class: 'scelta-breve', for: id },
              h('input', {
                type: 'radio', name: 'bersaglio', id, value: valore, checked: f.bersaglio === valore,
                onchange: () => { f.bersaglio = valore; d.pulisci(); disegnaBersaglio(); },
              }), h('span', null, testo)));
          });
          function disegnaBersaglio() {
            if (f.bersaglio === 'programma') zonaBersaglio.replaceChildren(...campoProgramma());
            if (f.bersaglio === 'sito') zonaBersaglio.replaceChildren(...campoSito());
            if (f.bersaglio === 'categoria') zonaBersaglio.replaceChildren(campoCategoria());
          }
          disegnaBersaglio();
          if (!S.visti) caricaVisti().then(() => { if (zonaBersaglio.isConnected && f.bersaglio !== 'categoria') disegnaBersaglio(); });

          const aiutoMinuti = h('p', { class: 'aiuto', id: 'aiuto-minuti', 'aria-live': 'polite' }, testoMinuti(f.minuti));
          const minuti = h('input', {
            type: 'number', id: 'campo-minuti', class: 'campo campo-corto', min: 1, max: 1440, step: 1, inputmode: 'numeric',
            value: f.minuti, 'aria-describedby': 'aiuto-minuti',
            oninput: (e) => { f.minuti = e.target.value; aiutoMinuti.textContent = testoMinuti(f.minuti); e.target.removeAttribute('aria-invalid'); },
          });
          return [gruppo, zonaBersaglio,
            h('div', { class: 'campo-gruppo' }, h('label', { class: 'etichetta-campo', for: 'campo-minuti' }, 'Minuti al giorno'), minuti, aiutoMinuti)];
        }

        function testoMinuti(valore) {
          const n = Number(valore);
          if (!valore || !Number.isInteger(n) || n < 1 || n > 1440) return 'Da 1 a 1440 minuti.';
          return n >= 60 ? 'Cioè ' + T.durata(n) + ' al giorno.' : 'Al giorno.';
        }

        function campoProgramma() {
          const programmi = ((S.visti && S.visti.programmi) || []).slice();
          if (f.programma && !programmi.some((x) => String(x.chiave).toLowerCase() === f.programma)) {
            programmi.push({ chiave: f.programma, nome: T.nomeBersaglio(f.programma, nomiProgrammi()) });
          }
          if (!programmi.length) {
            return [rigaVuota('info', S.visti
              ? 'Nessun programma visto finora su questo computer. Usalo un po\' e torna qui, oppure scegli una categoria.'
              : 'Sto leggendo i programmi usati su questo computer…')];
          }
          programmi.sort((a, b) => String(a.nome || a.chiave).localeCompare(String(b.nome || b.chiave), 'it'));
          const scelta = h('select', {
            id: 'campo-programma', class: 'campo', 'aria-describedby': 'aiuto-programma',
            onchange: (e) => { f.programma = e.target.value; e.target.removeAttribute('aria-invalid'); },
          }, h('option', { value: '' }, 'Scegli un programma…'),
          programmi.map((x) => {
            const chiave = String(x.chiave).toLowerCase();
            const file = chiave.replace(/^exe:/, '');
            const nome = x.nome && x.nome.toLowerCase() !== file ? x.nome + ' (' + file + ')' : file;
            return h('option', { value: chiave, selected: chiave === f.programma }, nome);
          }));
          return [h('div', { class: 'campo-gruppo' },
            h('label', { class: 'etichetta-campo', for: 'campo-programma' }, 'Programma'), scelta,
            h('p', { class: 'aiuto', id: 'aiuto-programma' }, 'I programmi usati su questo computer negli ultimi 30 giorni.'))];
        }

        function campoSito() {
          const vale = h('p', { class: 'aiuto', id: 'aiuto-sito', 'aria-live': 'polite' }, testoSito(f.sito));
          const suggerimenti = h('datalist', { id: 'siti-visti' }, ((S.visti && S.visti.siti) || []).map((s) => h('option', { value: s })));
          const campo = h('input', {
            type: 'text', id: 'campo-sito', class: 'campo', list: 'siti-visti', value: f.sito, placeholder: 'youtube.com',
            autocomplete: 'off', spellcheck: 'false', inputmode: 'url', 'aria-describedby': 'aiuto-sito',
            oninput: (e) => {
              // Solo il nome del sito: tutto quello che viene dopo si toglie subito.
              const pulito = T.ripulisciIndirizzo(e.target.value);
              if (pulito !== e.target.value) e.target.value = pulito;
              f.sito = e.target.value;
              vale.textContent = testoSito(f.sito);
              e.target.removeAttribute('aria-invalid');
            },
            onpaste: (e) => {
              const testo = e.clipboardData ? e.clipboardData.getData('text') : '';
              if (!testo) return;
              e.preventDefault();
              const nome = T.dominioDaTesto(testo) || T.ripulisciIndirizzo(testo);
              e.target.value = nome;
              f.sito = nome;
              vale.textContent = testoSito(f.sito);
            },
            onchange: (e) => {
              const nome = T.dominioDaTesto(e.target.value);
              if (nome) {
                e.target.value = nome;
                f.sito = nome;
                vale.textContent = testoSito(f.sito);
              }
            },
          });
          return [h('div', { class: 'campo-gruppo' },
            h('label', { class: 'etichetta-campo', for: 'campo-sito' }, 'Sito'), campo, suggerimenti, vale)];
        }

        function testoSito(valore) {
          const nome = T.dominioDaTesto(valore);
          if (!String(valore || '').trim()) return 'Scrivi solo il nome del sito, per esempio youtube.com: vale per tutte le sue pagine.';
          if (!nome) return 'Non sembra il nome di un sito: per esempio youtube.com.';
          return 'Vale per ' + nome + ', tutte le pagine.';
        }

        function campoCategoria() {
          const gruppo = h('fieldset', { class: 'gruppo-scelte' }, h('legend', { class: 'etichetta-campo' }, 'Categoria'));
          T.CATEGORIE.forEach((c) => {
            const id = 'categoria-' + c.chiave.slice('categoria:'.length);
            gruppo.append(h('label', { class: 'scelta', for: id },
              h('input', {
                type: 'radio', name: 'categoria', id, value: c.chiave, checked: f.categoria === c.chiave,
                onchange: () => { f.categoria = c.chiave; },
              }),
              h('span', { class: 'scelta-testi' }, h('span', { class: 'scelta-titolo' }, c.nome), h('span', { class: 'scelta-sotto' }, c.esempi))));
          });
          return gruppo;
        }

        function campiFascia() {
          const ora = (id, etichetta, campo) => h('div', { class: 'campo-gruppo campo-ora' },
            h('label', { class: 'etichetta-campo', for: id }, etichetta),
            h('input', {
              type: 'time', id, class: 'campo', step: 60, value: f[campo],
              oninput: (e) => { f[campo] = e.target.value; e.target.removeAttribute('aria-invalid'); },
            }));
          const caselle = [];
          const gruppo = h('fieldset', { class: 'gruppo-scelte' }, h('legend', { class: 'etichetta-campo' }, 'Giorni'));
          const fila = h('div', { class: 'fila-giorni' });
          T.GIORNI.forEach((g) => {
            const id = 'giorno-' + g;
            const casella = h('input', {
              type: 'checkbox', id, value: g, checked: f.giorni.has(g), 'aria-label': T.GIORNI_INTERI[g],
              onchange: (e) => { if (e.target.checked) f.giorni.add(g); else f.giorni.delete(g); },
            });
            caselle.push(casella);
            fila.append(h('label', { class: 'chip-scelta', for: id }, casella, h('span', { 'aria-hidden': 'true' }, g)));
          });
          const imposta = (giorni) => {
            f.giorni = new Set(giorni);
            caselle.forEach((c) => { c.checked = f.giorni.has(c.value); });
          };
          gruppo.append(fila, h('div', { class: 'azioni-piccole' },
            h('button', { type: 'button', class: 'bottone collegamento', onclick: () => imposta(T.GIORNI) }, 'Tutti i giorni'),
            h('button', { type: 'button', class: 'bottone collegamento', onclick: () => imposta(['lun', 'mar', 'mer', 'gio', 'ven']) }, 'Da lunedì a venerdì')));
          return [
            h('div', { class: 'fila-ore' }, ora('campo-dalle', 'Dalle', 'dalle'), ora('campo-alle', 'Alle', 'alle')),
            h('p', { class: 'aiuto' }, 'Se l\'ora di fine viene prima di quella di inizio, la fascia passa la mezzanotte: per esempio dalle 22:00 alle 07:00.'),
            gruppo,
          ];
        }

        function campiVitaReale() {
          const campo = (id, etichetta, chiaveF, esempio) => h('div', { class: 'campo-gruppo' },
            h('label', { class: 'etichetta-campo', for: id }, etichetta),
            h('input', {
              type: 'text', id, class: 'campo', value: f[chiaveF], maxlength: 120, autocomplete: 'off', placeholder: esempio,
              oninput: (e) => { f[chiaveF] = e.target.value; e.target.removeAttribute('aria-invalid'); },
            }));
          return [
            campo('campo-descrizione', 'Cosa ti impegni a fare', 'descrizione', 'per esempio: cammino un\'ora'),
            campo('campo-arbitro', 'Arbitro: chi lo conferma', 'arbitro', 'per esempio: la mamma'),
            campo('campo-frequenza', 'Quanto spesso', 'frequenza', 'per esempio: ogni giorno'),
          ];
        }

        /** I parametri pronti, oppure cosa manca (e in quale campo). */
        function parametri() {
          if (f.tipo === 'limite_tempo') {
            let chiave = null;
            if (f.bersaglio === 'programma') {
              if (!f.programma) return { manca: 'Scegli il programma.', campo: 'campo-programma' };
              chiave = f.programma;
            } else if (f.bersaglio === 'sito') {
              const nome = T.dominioDaTesto(f.sito);
              if (!nome) return { manca: 'Scrivi il nome del sito, per esempio youtube.com.', campo: 'campo-sito' };
              chiave = 'sito:' + nome;
            } else {
              if (!f.categoria) return { manca: 'Scegli la categoria.', campo: 'categoria-social' };
              chiave = f.categoria;
            }
            const minuti = Number(f.minuti);
            if (!Number.isInteger(minuti) || minuti < 1 || minuti > 1440) {
              return { manca: 'Scrivi quanti minuti al giorno, da 1 a 1440.', campo: 'campo-minuti' };
            }
            return { parametri: { app_o_categoria: chiave, minuti_al_giorno: minuti } };
          }
          if (f.tipo === 'fascia_oraria') {
            if (!T.oraValida(f.dalle)) return { manca: 'Scrivi l\'ora in cui comincia la fascia.', campo: 'campo-dalle' };
            if (!T.oraValida(f.alle)) return { manca: 'Scrivi l\'ora in cui finisce la fascia.', campo: 'campo-alle' };
            if (f.dalle === f.alle) return { manca: 'L\'inizio e la fine non possono essere alla stessa ora.', campo: 'campo-alle' };
            const giorni = T.GIORNI.filter((g) => f.giorni.has(g));
            if (!giorni.length) return { manca: 'Scegli almeno un giorno.', campo: 'giorno-lun' };
            return { parametri: { dalle: f.dalle, alle: f.alle, giorni } };
          }
          const descrizioneImpegno = f.descrizione.trim();
          const arbitro = f.arbitro.trim();
          const frequenza = f.frequenza.trim();
          if (!descrizioneImpegno) return { manca: 'Scrivi cosa ti impegni a fare.', campo: 'campo-descrizione' };
          if (!arbitro) return { manca: 'Scrivi chi fa da arbitro.', campo: 'campo-arbitro' };
          if (!frequenza) return { manca: 'Scrivi quanto spesso, per esempio ogni giorno.', campo: 'campo-frequenza' };
          return { parametri: { descrizione: descrizioneImpegno, arbitro_nome: arbitro, frequenza } };
        }

        async function invia() {
          if (d.occupato) return;
          const esito = parametri();
          if (esito.manca) {
            d.errore(esito.manca);
            const campo = d.cerca('#' + esito.campo);
            if (campo) {
              if (campo.tagName !== 'INPUT' || (campo.type !== 'radio' && campo.type !== 'checkbox')) campo.setAttribute('aria-invalid', 'true');
              campo.focus();
            }
            return;
          }
          if (!nuova && T.uguali(esito.parametri, regola.parametri)) {
            // Una "modifica" identica farebbe ripartire i 4 giorni per niente.
            d.chiudi();
            avviso('Nessuna modifica: la regola resta com\'era.');
            return;
          }
          d.pulisci();
          d.occupato = true;
          d.aggiorna();
          const r = nuova
            ? await Api.post('/server/api/regole', { tipo: f.tipo, parametri: esito.parametri })
            : await Api.patch('/server/api/regole/' + regola.id, { parametri: esito.parametri });
          d.occupato = false;
          d.aggiorna();
          if (r.ok) {
            d.chiudi();
            avviso('Regola salvata. Il genitore riceve una notifica.');
            aggiornaTutto();
            return;
          }
          d.errore(testoErroreRegola(r, { regola, eliminazione: false }));
          if (r.stato === 404 || r.stato === 401) aggiornaTutto();
        }
      },
    });
  }

  function dialogoElimina(regola) {
    apriDialogo({
      titolo: 'Eliminare la regola?',
      fuoco: '.azioni-dialogo .bottone.testo',
      costruisci(d) {
        const elimina = h('button', { type: 'button', class: 'bottone primario', onclick: conferma }, 'Elimina');
        const annulla = h('button', { type: 'button', class: 'bottone testo', onclick: () => d.chiudi() }, 'Annulla');
        d.aggiorna = () => {
          elimina.disabled = d.occupato;
          elimina.textContent = d.occupato ? 'Elimino…' : 'Elimina';
          annulla.disabled = d.occupato;
        };
        d.corpo.append(
          h('p', null, '«' + descrizione(regola) + '» uscirà dal patto. Il genitore riceverà una notifica.'),
          h('p', { class: 'secondario' }, 'Niente si cancella: la regola resta nella storia del patto, che il genitore vede.'),
          d.messaggio,
          bottoniDialogo(annulla, elimina));

        async function conferma() {
          if (d.occupato) return;
          d.pulisci();
          d.occupato = true;
          d.aggiorna();
          const r = await Api.elimina('/server/api/regole/' + regola.id);
          d.occupato = false;
          d.aggiorna();
          if (r.ok) {
            d.chiudi();
            avviso('Regola eliminata. Il genitore riceve una notifica.');
            aggiornaTutto();
            return;
          }
          d.errore(testoErroreRegola(r, { regola, eliminazione: true }));
          if (r.stato === 404) aggiornaTutto();
        }
      },
    });
  }

  function dialogoDichiarazione(regola, esito) {
    const oggi = oggiPatto();
    const dichiarati = new Set(tutteDichiarazioni().filter((x) => x.regola_id === regola.id).map((x) => x.giorno));
    let giorno = oggi;
    apriDialogo({
      titolo: esito === 'successo' ? 'Dichiari un successo?' : 'Dichiari che non è andata?',
      fuoco: 'input[name="giorno"]:checked',
      costruisci(d) {
        const conferma = h('button', { type: 'submit', class: 'bottone primario' }, 'Conferma');
        const annulla = h('button', { type: 'button', class: 'bottone testo', onclick: () => d.chiudi() }, 'Annulla');
        d.aggiorna = () => {
          conferma.disabled = d.occupato;
          conferma.textContent = d.occupato ? 'Invio…' : 'Conferma';
          annulla.disabled = d.occupato;
        };
        const giorni = h('fieldset', { class: 'gruppo-scelte' }, h('legend', { class: 'etichetta-campo' }, 'Per quale giorno?'));
        const fila = h('div', { class: 'fila-giorni' });
        for (let indietro = 0; indietro <= 7; indietro++) {
          const iso = T.spostaIso(oggi, -indietro);
          const occupato = dichiarati.has(iso);
          const id = 'dichiara-giorno-' + indietro;
          fila.append(h('label', { class: 'chip-scelta' + (occupato ? ' spento' : ''), for: id, title: occupato ? 'Già dichiarato' : null },
            h('input', {
              type: 'radio', name: 'giorno', id, value: iso, checked: iso === giorno, disabled: occupato,
              onchange: () => { giorno = iso; },
            }),
            h('span', null, capitale(T.giornoBreve(iso, oggi)))));
        }
        giorni.append(fila);
        const nota = h('input', { type: 'text', id: 'campo-nota', class: 'campo', maxlength: 300, autocomplete: 'off' });
        d.corpo.append(h('form', {
          class: 'modulo', novalidate: true,
          onsubmit: (e) => { e.preventDefault(); if (!d.occupato) dichiara(regola, esito, giorno, nota.value.trim(), d); },
        },
        h('p', { class: 'secondario' }, descrizione(regola)),
        h('p', null, esito === 'successo'
          ? 'Il successo resta in attesa finché l\'arbitro o il genitore lo confermano.'
          : 'Ti crediamo sulla parola: va a registro, senza conferme. Dirlo a viso aperto vale già qualcosa.'),
        giorni,
        h('div', { class: 'campo-gruppo' }, h('label', { class: 'etichetta-campo', for: 'campo-nota' }, 'Nota (facoltativa)'), nota),
        d.messaggio,
        bottoniDialogo(annulla, conferma)));
      },
    });
  }

  function dialogoCosaVede() {
    apriDialogo({
      titolo: 'Cosa vede tuo padre',
      fuoco: '.azioni-dialogo .bottone',
      costruisci(d) {
        d.corpo.append(h('div', { class: 'dialogo-scorre' }, sezioneCosaVede()),
          bottoniDialogo(null, h('button', { type: 'button', class: 'bottone primario', onclick: () => d.chiudi() }, 'Ho capito')));
      },
    });
  }

  // --- Navigazione -------------------------------------------------------------------------------

  function sezioneDaIndirizzo() {
    const id = decodeURIComponent(String(location.hash || '').replace(/^#/, ''));
    return SEZIONI.some((s) => s.id === id) ? id : 'oggi';
  }

  function vaiA(id) {
    if (location.hash === '#' + id) return;
    location.hash = '#' + id;
  }

  window.addEventListener('hashchange', () => {
    const nuova = sezioneDaIndirizzo();
    if (nuova === S.sezione) return;
    S.sezione = nuova;
    S.bonus = null;
    S.fuocoTitolo = true;
    if (S.dom) S.dom.principale.scrollTop = 0;
    render();
    if (nuova === 'regole' && !S.visti) caricaVisti();
  });

  // --- Avvio ---------------------------------------------------------------------------------------

  function caricaProva() {
    return new Promise((fatto, fallito) => {
      if (window.PactumProva) {
        fatto();
        return;
      }
      const script = document.createElement('script');
      script.src = 'prova.js';
      script.onload = () => fatto();
      script.onerror = () => fallito(new Error('prova.js non trovato'));
      document.head.append(script);
    });
  }

  /**
   * Motore vero, finto o in attesa. Il finto motore parte con ?prova=1, oppure
   * se /locale/stato non risponde in un browser qualsiasi. Nel programma vero
   * (https://pactum.locale) mai dati finti: se il motore tace si aspetta.
   */
  async function scegliModalita() {
    const parametri = new URLSearchParams(location.search);
    if (parametri.get('prova') === '1') return 'prova';
    const r = await Api.get('/locale/stato');
    if (r.ok && r.dati && typeof r.dati.abbinato === 'boolean') {
      S.motore = r.dati;
      return 'vera';
    }
    return location.hostname === 'pactum.locale' ? 'attesa' : 'prova';
  }

  async function avviaProva() {
    await caricaProva();
    const parametri = new URLSearchParams(location.search);
    window.PactumProva.configura({
      abbinato: parametri.get('collega') !== '1',
      rete: parametri.get('rete') !== '0',
      revocato: parametri.get('revocato') === '1',
    });
    Api.prova = true;
  }

  async function aspettaMotore() {
    for (;;) {
      await new Promise((fatto) => setTimeout(fatto, 5000));
      const r = await Api.get('/locale/stato');
      if (r.ok && r.dati && typeof r.dati.abbinato === 'boolean') {
        S.motore = r.dati;
        S.modalita = 'vera';
        return;
      }
    }
  }

  async function avvio() {
    const app = document.getElementById('app');
    S.dom = {
      app,
      laterale: h('aside', { class: 'laterale' }),
      principale: h('main', { class: 'principale', id: 'principale' }),
      contenuto: h('div', { class: 'contenuto' }),
    };
    S.dom.principale.append(S.dom.contenuto);
    app.replaceChildren(S.dom.laterale, S.dom.principale);
    S.sezione = sezioneDaIndirizzo();

    S.modalita = await scegliModalita();
    if (S.modalita === 'prova') await avviaProva();
    if (S.modalita === 'attesa') {
      render();
      await aspettaMotore();
    }
    document.documentElement.classList.toggle('modalita-prova', S.modalita === 'prova');

    if (!S.motore) {
      const r = await Api.get('/locale/stato');
      if (r.ok && r.dati && typeof r.dati.abbinato === 'boolean') S.motore = r.dati;
    }
    leggiCopia();
    render();
    await aggiornaTutto();
    caricaVisti().then(render);

    // Ogni minuto, e a ogni ritorno sulla finestra.
    setInterval(() => {
      if (!document.hidden) aggiornaTutto();
    }, OGNI_MINUTO);
    const alRitorno = () => {
      if (!document.hidden && Date.now() - (S.ultimoGiro || 0) > 5000) aggiornaTutto();
    };
    window.addEventListener('focus', alRitorno);
    document.addEventListener('visibilitychange', alRitorno);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', avvio);
  else avvio();
})();
