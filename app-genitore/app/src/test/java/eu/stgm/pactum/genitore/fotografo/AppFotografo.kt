package eu.stgm.pactum.genitore.fotografo

import android.app.Application

/**
 * L'Application del fotografo: quella vera (PactumApp) pianifica la vedetta in
 * WorkManager, che girerebbe da sola e manderebbe notifiche durante le foto.
 * Qui non parte niente in sottofondo: si disegnano solo le schermate.
 */
class AppFotografo : Application()
