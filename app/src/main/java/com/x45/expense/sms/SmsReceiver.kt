package com.x45.expense.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.x45.expense.Notify
import com.x45.expense.data.Db
import com.x45.expense.data.Places
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        // A long SMS arrives in several parts; join them.
        val sender = parts[0].displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val ts = System.currentTimeMillis()

        val pending = goAsync()
        thread {
            try {
                val id = Ingest.handle(ctx, sender, body, ts) ?: return@thread
                Notify.newSpend(ctx, id)
                Notify.budgetCheck(ctx)
                // Where you were when you paid. Android kills a receiver after ~10 s, so keep this short;
                // the place name is looked up later by the app (Places.fillNames).
                Places.current(ctx, timeoutSec = 6)?.let { (lat, lng) -> Db.get(ctx).setLocation(id, lat, lng, null) }
            } finally {
                pending.finish()
            }
        }
    }
}
