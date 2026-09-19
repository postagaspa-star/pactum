package eu.stgm.pactum.design

import androidx.compose.ui.graphics.Color

/**
 * I colori del PATTO: gli unici fuori da `colorScheme`, perché il loro
 * significato non dipende dal ruolo Material ma dal patto. Identici nelle due
 * app. Solo le varianti chiare: Pactum va sempre su fondo chiaro (scelta di
 * Andrea, 31/07).
 *
 * Tre leggi:
 *  1. Mantenuta/FuoriRegola compaiono SOLO dentro la striscia degli 8 giorni.
 *  2. Il silenzio del canale non è mai rosso: nove volte su dieci è batteria.
 *  3. `error`/`errorContainer` restano solo per la validazione dei form.
 *
 * Il nome non è `Patto` perché nel figlio `Patto` è già il modello di
 * GET /api/patto: i due import si sarebbero pestati i piedi.
 */
object ColoriPatto {
    /** Pieno del giorno mantenuto: il quadretto più contrastato (6,38:1). */
    val Mantenuta = Color(0xFF1E6B33)

    /** Pieno del giorno fuori regola: si legge senza urlare (3,12:1). */
    val FuoriRegola = Color(0xFFC97C62)

    /** Il canale muto: grigio-blu, MAI rosso. */
    val Silenzio = Color(0xFF4C5A69)

    /** Numero bianco sul verde (6,55:1). */
    val InchiostroSuMantenuta = Color(0xFFFFFFFF)

    /** Inchiostro quasi nero sul terracotta (5,59:1): il bianco non si leggerebbe. */
    val InchiostroSuFuoriRegola = Color(0xFF10181C)

    /** Bianco sul grigio-blu del silenzio (7,06:1). */
    val InchiostroSuSilenzio = Color(0xFFFFFFFF)

    // NESSUN_DATO non ha colore: è surfaceVariant + bordo outline, dal tema.
}
