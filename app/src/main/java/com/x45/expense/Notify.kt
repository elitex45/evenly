package com.x45.expense

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.x45.expense.data.Db
import com.x45.expense.data.KIND_SPEND
import com.x45.expense.data.KIND_TRANSFER
import com.x45.expense.ui.rupees
import com.x45.expense.ui.startOfMonth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Notify {
    private const val CH_SPEND = "spend"
    private const val CH_DAILY = "daily"
    private const val CH_BUDGET = "budget"
    const val EXTRA_OPEN = "open"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SPEND, "New spends", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DAILY, "Daily review reminder", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, req: Int, open: String? = null) = PendingIntent.getActivity(
        ctx, req, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (open != null) putExtra(EXTRA_OPEN, open) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** "₹499 to SWIGGY" with one-tap category buttons, so you can tag without opening the app. */
    fun newSpend(ctx: Context, id: Long) {
        if (!allowed(ctx)) return
        val db = Db.get(ctx)
        val t = db.txn(id) ?: return
        if (t.kind != KIND_SPEND && t.kind != KIND_TRANSFER) return
        val title = "${rupees(t.amountPaise)} to ${t.payee ?: "unknown"}"
        val b = Notification.Builder(ctx, CH_SPEND).setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
            .setContentIntent(openApp(ctx, id.toInt(), "spend:$id")).setAutoCancel(true)
        when {
            t.kind == KIND_TRANSFER -> b.setContentText("Looks like a card bill or own-account transfer. Not counted.")
            t.category != null -> b.setContentText("Tagged as ${t.category}")
            else -> {
                b.setContentText("Tag it now, or later in tonight's review")
                val suggest = (db.categoriesFor(t.payeeRaw) + db.topCategories(6) + listOf("Food", "Travel", "Shopping"))
                    .distinct().filter { c -> db.categories().any { it.name == c } }.take(3)
                suggest.forEachIndexed { i, cat ->
                    val pi = PendingIntent.getBroadcast(
                        ctx, (id * 10 + i).toInt(),
                        Intent(ctx, TagActionReceiver::class.java).putExtra("id", id).putExtra("category", cat),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), cat, pi).build())
                }
            }
        }
        ctx.getSystemService(NotificationManager::class.java).notify(id.toInt(), b.build())
    }

    /** Warn once a month at 80% and at 100% of the overall budget and of each category budget. */
    fun budgetCheck(ctx: Context) {
        if (!allowed(ctx)) return
        val db = Db.get(ctx)
        val from = startOfMonth(0)
        val spends = db.spendsBetween(from, startOfMonth(1))
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(from))
        val checks = buildList {
            // Budgets are for your own money: company spends never count.
            db.monthlyBudget?.let { add(Triple("overall", "this month", spends.filter { !it.company }.sumOf { it.amountPaise } to it)) }
            db.categories().filter { it.budgetPaise != null }.forEach { c ->
                add(Triple(c.name, c.name, spends.filter { it.category == c.name && !it.company }.sumOf { it.amountPaise } to c.budgetPaise!!))
            }
        }
        checks.forEach { (key, label, pair) ->
            val (spent, budget) = pair
            if (budget <= 0) return@forEach
            val level = when { spent >= budget -> 100; spent * 10 >= budget * 8 -> 80; else -> return@forEach }
            val flag = "alert:$month:$key:$level"
            if (db.setting(flag) != null) return@forEach
            db.putSetting(flag, "1")
            val text = if (level == 100) "You've used your whole ${rupees(budget)} budget for $label."
            else "You've used ${spent * 100 / budget}% of your ${rupees(budget)} budget for $label."
            ctx.getSystemService(NotificationManager::class.java).notify(
                key.hashCode() + level,
                Notification.Builder(ctx, CH_BUDGET).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(if (level == 100) "Budget used up" else "Heads up: ${level}% of budget")
                    .setContentText(text).setContentIntent(openApp(ctx, 3, "budgets")).setAutoCancel(true).build()
            )
        }
    }

    fun daily(ctx: Context) {
        if (!allowed(ctx)) return
        val db = Db.get(ctx)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val today = db.spendsBetween(cal.timeInMillis, cal.timeInMillis + 86_400_000L)
        val untagged = db.untagged().size
        if (today.isEmpty() && untagged == 0) return
        val title = if (today.isEmpty()) "No spends today" else "You spent ${rupees(today.sumOf { it.amountPaise })} today"
        val text = if (untagged > 0) "${com.x45.expense.ui.plural(untagged, "spend")} to review. Takes a minute." else "All tagged. Nice."
        ctx.getSystemService(NotificationManager::class.java).notify(
            999_999,
            Notification.Builder(ctx, CH_DAILY).setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
                .setContentText(text).setContentIntent(openApp(ctx, 2, if (untagged > 0) "review" else null)).setAutoCancel(true).build()
        )
    }

    /** Minutes after midnight for the daily reminder. Default 9 PM. */
    fun reminderMinutes(ctx: Context) = Db.get(ctx).setting("remind_at")?.toIntOrNull() ?: (21 * 60)

    /** Daily reminder. Inexact, so it needs no special alarm permission. */
    fun scheduleDaily(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, DailyReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val m = reminderMinutes(ctx)
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.cancel(pi)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Notify.scheduleDaily(ctx)
            else -> Notify.daily(ctx)
        }
    }
}

/** A category button on the "new spend" notification was tapped. */
class TagActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val cat = intent.getStringExtra("category") ?: return
        val db = Db.get(ctx)
        val t = db.txn(id) ?: return
        db.tag(id, cat, t.note)
        db.learn(t, cat)
        ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
        Notify.budgetCheck(ctx)
    }
}
