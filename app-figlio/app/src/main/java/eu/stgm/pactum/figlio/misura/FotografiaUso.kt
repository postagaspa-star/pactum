package eu.stgm.pactum.figlio.misura

import android.content.Context
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.TipiEvento
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * La fotografia cumulativa dell'uso di un giorno (evento `uso_giornaliero`).
 * La fa il worker a ogni giro e (0.9) la sentinella quando nasce uno
 * sforamento, dalla stessa lettura che l'ha fatto scattare: accanto allo
 * sforamento il genitore vede i minuti giusti. Il server, ricevendo più
 * fotografie dello stesso giorno, tiene la più alta: idempotente per design.
 *
 * (v2.2) La fotografia porta anche `nomi` (etichette leggibili: solo il
 * telefono del figlio può risolvere i pacchetti) e `uso_categorie` (totali
 * per categoria col mapping interno di CatalogoApp — LO STESSO che
 * SentinellaPatto dà in pasto al valutatore, così "categoria:social" nella
 * finestra e nel valutatore contano le stesse app).
 */
object FotografiaUso {

    /** La fotografia di [giorno], leggendo gli eventi. */
    fun evento(context: Context, giorno: LocalDate): Evento =
        evento(context, UsoContato.di(context, UsageStatsReader(context).leggiGiorno(giorno)))

    /** La fotografia da una lettura già fatta: niente seconda lettura degli eventi. */
    fun evento(context: Context, lettura: LetturaGiorno): Evento =
        evento(context, UsoContato.di(context, lettura))

    /** La fotografia da un uso già contato (stesso filtro e stesso totale della sentinella). */
    fun evento(context: Context, uso: UsoContato): Evento = Evento(
        tipo = TipiEvento.USO_GIORNALIERO,
        tsDevice = System.currentTimeMillis(),
        dettagli = buildJsonObject {
            put("giorno", uso.giorno.toString())
            put("uso_minuti", buildJsonObject {
                uso.perApp.forEach { put(it.pacchetto, JsonPrimitive(it.millisPrimoPiano / 60_000)) }
            })
            // Totale del giorno dai millisecondi veri (contratto-api.md:
            // totale_minuti), mai sotto quello già visto oggi. È anche il numero
            // della regola "Tutto il telefono".
            put("totale_minuti", uso.totaleMinuti)
            // Solo etichette risolte per i pacchetti presenti in uso_minuti:
            // se il pacchetto non si risolve, il server ripiega da solo sul
            // nome pacchetto (contratto: uso_recente).
            put("nomi", buildJsonObject {
                uso.perApp.forEach {
                    val etichetta = CatalogoApp.etichettaValore(context, it.pacchetto)
                    if (etichetta != it.pacchetto) put(it.pacchetto, JsonPrimitive(etichetta))
                }
            })
            // Per categoria: somma dei minuti arrotondati per app, così il
            // totale di una categoria torna con le sue app in uso_minuti
            // (stesso arrotondamento per-app di SentinellaPatto).
            put("uso_categorie", buildJsonObject {
                uso.perApp.groupBy { CatalogoApp.categoriaDiPacchetto(context, it.pacchetto) }
                    .forEach { (categoria, usi) ->
                        put(categoria, JsonPrimitive(usi.sumOf { it.millisPrimoPiano / 60_000 }))
                    }
            })
        },
    )
}
