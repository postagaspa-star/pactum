package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.CellaMedia
import eu.stgm.pactum.design.GiornoGrafico
import eu.stgm.pactum.genitore.dati.MediaPeriodo
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.rete.PostinoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (0.16, contratto v3.8) I totali degli ultimi 7 e 30 giorni nel Tempo, i giorni
 * con dati, l'assenza del campo su un server vecchio, e il tempo scritto corto
 * sopra le barre degli 8 giorni.
 */
class TotaliTempoTest {

    private val p = ParoleDiProva

    @Test
    fun `col totale - ULTIMI 7 e 30 GIORNI in grande, la media piu piccola sotto`() {
        val celle = celleTempi(p, Medie(MediaPeriodo(171, 7, totale = 1197), MediaPeriodo(158, 30, totale = 4740)))
        assertEquals(
            listOf(
                CellaMedia("ULTIMI 7 GIORNI", "19 h 57 min", listOf("media al giorno: 2\u00A0h\u00A051\u00A0min"), grande = true),
                CellaMedia("ULTIMI 30 GIORNI", "79 h", listOf("media al giorno: 2\u00A0h\u00A038\u00A0min"), grande = true),
            ),
            celle,
        )
    }

    @Test
    fun `con meno giorni della finestra si dice quanti avevano dati`() {
        val celle = celleTempi(p, Medie(MediaPeriodo(162, 5, totale = 810), MediaPeriodo(140, 21, totale = 2940)))
        assertEquals(listOf("giorni con dati: 5 su 7", "media al giorno: 2\u00A0h\u00A042\u00A0min"), celle[0].sotto)
        assertEquals(listOf("giorni con dati: 21 su 30", "media al giorno: 2\u00A0h\u00A020\u00A0min"), celle[1].sotto)
        assertEquals("13 h 30 min", celle[0].valore)
        assertEquals("49 h", celle[1].valore)
    }

    @Test
    fun `senza totale (server prima della v3_8) restano le medie di prima`() {
        val celle = celleTempi(p, Medie(MediaPeriodo(171, 7), MediaPeriodo(158, 28)))
        assertEquals(
            listOf(
                CellaMedia("MEDIA SETTIMANA", "2 h 51 min", listOf("su 7 giorni")),
                CellaMedia("MEDIA MESE", "2 h 38 min", listOf("su 28 giorni")),
            ),
            celle,
        )
    }

    @Test
    fun `un periodo senza dati non ha cella, e i periodi si decidono uno per uno`() {
        assertEquals(emptyList<CellaMedia>(), celleTempi(p, Medie()))
        val soloMese = celleTempi(p, Medie(settimana = null, mese = MediaPeriodo(20, 3, totale = 60)))
        assertEquals(listOf("ULTIMI 30 GIORNI"), soloMese.map { it.etichetta })
        assertEquals("1 h", soloMese[0].valore)
        // La settimana col totale, il mese senza: ognuno come può.
        val misto = celleTempi(p, Medie(MediaPeriodo(10, 7, totale = 70), MediaPeriodo(10, 30)))
        assertEquals(listOf("ULTIMI 7 GIORNI", "MEDIA MESE"), misto.map { it.etichetta })
        // Un totale a zero è un dato vero (c'erano fotografie): si scrive.
        assertEquals("0 min", celleTempi(p, Medie(MediaPeriodo(0, 7, totale = 0))).single().valore)
    }

    @Test
    fun `il totale arriva dal JSON del server, e manca su un server vecchio`() {
        val json = PostinoClient.json
        val nuovo = json.decodeFromString(
            Medie.serializer(),
            """{"settimana":{"minuti":171,"giorni":7,"totale":1197},"mese":null}""",
        )
        assertEquals(1197, nuovo.settimana?.totale)
        assertNull(nuovo.mese)
        val vecchio = json.decodeFromString(Medie.serializer(), """{"settimana":{"minuti":171,"giorni":7}}""")
        assertNull(vecchio.settimana?.totale)
    }

    @Test
    fun `sopra le barre il tempo e scritto corto, per TalkBack per esteso`() {
        assertEquals("2h31", testoDurataBreve(p, 151))
        assertEquals("3h", testoDurataBreve(p, 180))
        assertEquals("10h05", testoDurataBreve(p, 605))
        assertEquals("45 min", testoDurataBreve(p, 45))
        assertEquals("0 min", testoDurataBreve(p, 0))
        assertEquals("03/10: 3 h 5 min", descrizioneGiorno(p, GiornoGrafico("2026-10-03", 185)))
        assertEquals("01/10: senza dati", descrizioneGiorno(p, GiornoGrafico("2026-10-01", null)))
    }
}
