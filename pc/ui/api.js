/*
 * Pactum per il computer: le sole chiamate che l'interfaccia fa.
 *
 * L'accordo col motore (docs/pc-programma.md): la pagina è servita da
 * https://pactum.locale/ e chiama SOLO /locale/* e /server/*, con percorsi
 * relativi. Il motore le intercetta dentro WebView2: niente porte aperte,
 * e l'interfaccia non conosce il token né parla mai col server da sola.
 *
 * In modalità di prova le stesse chiamate vanno al finto motore (prova.js),
 * che risponde con la stessa forma. Qui non si decide niente sui dati:
 * si consegna a chi chiama { ok, stato, dati, rete }.
 */
(function (radice) {
  'use strict';

  const ATTESA_LOCALE_MS = 8000;   // il motore è sul computer: se tace, qualcosa non va
  const ATTESA_SERVER_MS = 25000;  // il motore gira la richiesta al server, che può essere lento

  const Api = {
    /** true = finto motore (prova.js). Lo decide app.js all'avvio. */
    prova: false,

    get(percorso) { return chiama('GET', percorso); },
    post(percorso, corpo) { return chiama('POST', percorso, corpo); },
    patch(percorso, corpo) { return chiama('PATCH', percorso, corpo); },
    elimina(percorso) { return chiama('DELETE', percorso); },
  };

  function percorsoAmmesso(percorso) {
    return typeof percorso === 'string' && /^\/(locale|server)\//.test(percorso);
  }

  async function chiama(metodo, percorso, corpo) {
    // L'accordo è stretto: nessun'altra chiamata, per nessun motivo.
    if (!percorsoAmmesso(percorso)) throw new Error('Chiamata fuori dall\'accordo: ' + percorso);

    if (Api.prova) {
      try {
        const r = await radice.PactumProva.gestisci(metodo, percorso, corpo);
        return normalizza(r.stato, r.dati);
      } catch (e) {
        return { ok: false, stato: 0, dati: null, rete: true };
      }
    }

    const controllo = typeof AbortController === 'function' ? new AbortController() : null;
    const limite = percorso.startsWith('/server/') ? ATTESA_SERVER_MS : ATTESA_LOCALE_MS;
    const timer = controllo ? setTimeout(() => controllo.abort(), limite) : null;
    try {
      const opzioni = {
        method: metodo,
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      };
      if (controllo) opzioni.signal = controllo.signal;
      if (corpo !== undefined) {
        opzioni.headers['Content-Type'] = 'application/json';
        opzioni.body = JSON.stringify(corpo);
      }
      const risposta = await fetch(percorso, opzioni);
      const testo = await risposta.text();
      let dati = null;
      if (testo) {
        try {
          dati = JSON.parse(testo);
        } catch (e) {
          dati = null;
        }
      }
      return normalizza(risposta.status, dati);
    } catch (e) {
      // Niente risposta: il motore non c'è (browser qualsiasi) o non ha risposto in tempo.
      return { ok: false, stato: 0, dati: null, rete: true };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /**
   * `rete` = la richiesta non è arrivata a destinazione: nessuna risposta, un
   * 502/503/504 del motore che non raggiunge il server, oppure un errore
   * "rete" dichiarato nel corpo.
   */
  function normalizza(stato, dati) {
    const codice = Number(stato) || 0;
    const corpo = dati === undefined ? null : dati;
    const ok = codice >= 200 && codice < 300;
    let errore = null;
    if (corpo && typeof corpo === 'object') {
      errore = corpo.errore || (corpo.detail && typeof corpo.detail === 'object' ? corpo.detail.errore : null) || null;
    }
    const rete = codice === 0 || codice === 502 || codice === 503 || codice === 504 || (!ok && errore === 'rete');
    return { ok, stato: codice, dati: corpo, rete };
  }

  radice.PactumApi = Api;
})(window);
