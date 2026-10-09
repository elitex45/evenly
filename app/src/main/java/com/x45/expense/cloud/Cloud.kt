package com.x45.expense.cloud

import com.x45.expense.data.Db
import com.x45.expense.data.Txn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/** The server said no (wrong code, not allowed...). Not retried. */
class CloudError(val status: Int, msg: String) : Exception(msg)

data class Me(val id: String, val name: String, val email: String)

/**
 * V2 groups. Everything here is opt-in: nothing goes up unless you share a spend.
 * What goes up for a spend: amount, payee name, your note, time, and the split. Never the bank, card or text.
 */
object Cloud {
    /** The emulator's name for "this Mac". A real phone needs the real server address. */
    const val DEFAULT_SERVER = "https://evenlyapi.x45.in"

    fun server(db: Db) = db.setting("cloud_server") ?: DEFAULT_SERVER
    fun signedIn(db: Db) = db.setting("cloud_token") != null
    fun me(db: Db): Me? = db.setting("cloud_user")?.let { JSONObject(it) }?.let { Me(it.getString("id"), it.getString("name"), it.optString("email")) }

    fun groups(db: Db): List<Group> = db.setting("cloud_me")?.let { runCatching { parseGroups(JSONObject(it).getJSONArray("groups")) }.getOrNull() } ?: emptyList()
    fun group(db: Db, id: String) = groups(db).firstOrNull { it.id == id }

    /** Groups I asked to join and the admin has not answered yet. */
    fun pending(db: Db): List<Pending> = db.setting("cloud_me")?.let { s ->
        runCatching {
            val a = JSONObject(s).optJSONArray("pending") ?: return@runCatching emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { x -> Pending(x.getString("groupId"), x.getString("name"), x.optString("emoji", "👥"), x.getString("admin")) } }
        }.getOrNull()
    } ?: emptyList()

    /** Last group screen we fetched (members, balances, activity), for showing offline. */
    fun groupDetail(db: Db, id: String): JSONObject? = db.setting("cloud_group_$id")?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ---------- http ----------

    private fun call(db: Db, method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = URL(server(db).trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 6000
            c.readTimeout = 10000
            c.setRequestProperty("content-type", "application/json")
            db.setting("cloud_token")?.let { c.setRequestProperty("authorization", "Bearer $it") }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code == 401 && path != "/auth/dev" && path != "/auth/google") forget(db)
            if (code >= 400) throw CloudError(code, json.optString("error").ifBlank { "Server error ($code)" })
            return json
        } finally {
            c.disconnect()
        }
    }

    /** Turns any failure into words for a toast. */
    fun why(e: Throwable): String = when (e) {
        is CloudError -> e.message ?: "Server said no"
        is IOException -> "Can't reach the server. Check the internet."
        else -> e.message ?: "Something went wrong"
    }

    // ---------- sign in ----------

    fun config(db: Db): JSONObject = call(db, "GET", "/config")

    fun devLogin(db: Db, name: String, email: String) =
        saveLogin(db, call(db, "POST", "/auth/dev", JSONObject().put("name", name).put("email", email)))

    fun googleLogin(db: Db, idToken: String) =
        saveLogin(db, call(db, "POST", "/auth/google", JSONObject().put("idToken", idToken)))

    private fun saveLogin(db: Db, r: JSONObject) {
        db.putSetting("cloud_token", r.getString("token"))
        db.putSetting("cloud_user", r.getJSONObject("user").toString())
        sync(db)
    }

    fun signOut(db: Db) {
        runCatching { call(db, "POST", "/auth/logout") }
        forget(db)
    }

    private fun forget(db: Db) {
        db.putSetting("cloud_token", null)
        db.putSetting("cloud_user", null)
        db.putSetting("cloud_me", null)
    }

    // ---------- groups ----------

    fun createGroup(db: Db, name: String, emoji: String): Group {
        val g = parseGroup(call(db, "POST", "/groups", JSONObject().put("name", name).put("emoji", emoji)))
        refreshMe(db)
        return g
    }

    /** Group name, its admin and how many are in it, before you ask. */
    fun invite(db: Db, code: String): JSONObject = call(db, "GET", "/invites/${code.trim().uppercase()}")

    /** Ask to join. Returns the group if I'm already in, null if now waiting for the admin. */
    fun join(db: Db, code: String): Group? {
        val r = call(db, "POST", "/groups/join", JSONObject().put("code", code.trim().uppercase()))
        refreshMe(db)
        if (r.optBoolean("pending")) return null
        sync(db)
        return parseGroup(r)
    }

    // ---------- admin ----------

    fun approve(db: Db, groupId: String, userId: String) = adminCall(db, "POST", "/groups/$groupId/requests/$userId/approve", groupId)
    fun deny(db: Db, groupId: String, userId: String) = adminCall(db, "POST", "/groups/$groupId/requests/$userId/deny", groupId)
    fun removeMember(db: Db, groupId: String, userId: String) = adminCall(db, "DELETE", "/groups/$groupId/members/$userId", groupId)
    /** New invite code; the old one stops working. */
    fun newCode(db: Db, groupId: String): String = adminCall(db, "POST", "/groups/$groupId/code", groupId).getString("code")

    private fun adminCall(db: Db, method: String, path: String, groupId: String): JSONObject {
        val r = call(db, method, path)
        refreshMe(db)
        runCatching { loadGroup(db, groupId) }
        return r
    }

    fun loadGroup(db: Db, id: String): JSONObject {
        val r = call(db, "GET", "/groups/$id")
        db.putSetting("cloud_group_$id", r.toString())
        return r
    }

    fun inviteLink(db: Db, g: Group) = server(db).trimEnd('/') + "/join/" + g.code

    // ---------- sharing (offline-safe: queued, sent in order, safe to repeat) ----------

    /** Share my spend to a group. parts = who owes what (me included if I had a part). */
    fun share(db: Db, t: Txn, g: Group, parts: List<Pair<String, Long>>) {
        val id = t.shareId ?: UUID.randomUUID().toString()
        val body = JSONObject()
            .put("groupId", g.id).put("amountPaise", t.amountPaise).put("title", t.payee ?: "Shared spend")
            .put("note", t.note).put("spentAt", t.ts)
            .put("parts", JSONArray(parts.map { (u, p) -> JSONObject().put("userId", u).put("paise", p) }))
        val mine = parts.firstOrNull { it.first == me(db)?.id }?.second ?: 0L
        db.markShared(t, id, body.put("groupName", g.name).toString(), mine, g.name)
        db.enqueue("PUT", "/shares/$id", body.toString())
        syncAsync(db)
    }

    fun unshare(db: Db, t: Txn) {
        val id = t.shareId ?: return
        db.markUnshared(t)
        db.enqueue("DELETE", "/shares/$id", null)
        syncAsync(db)
    }

    /** "Paid back": from paid to. Mark the bank spend (if any) as not a new spend: it was paying back. */
    fun settle(db: Db, groupId: String, fromId: String, toId: String, paise: Long, paidWith: Txn? = null) {
        val body = JSONObject().put("groupId", groupId).put("fromId", fromId).put("toId", toId).put("paise", paise)
        if (paidWith != null) {
            db.setKind(paidWith.id, com.x45.expense.data.KIND_TRANSFER)
            db.tag(paidWith.id, paidWith.category, listOfNotNull("Paid back · ${group(db, groupId)?.name ?: "group"}", paidWith.note).joinToString(" · "))
        }
        db.enqueue("PUT", "/settlements/${UUID.randomUUID()}", body.toString())
        syncAsync(db)
    }

    // ---------- sync ----------

    @Volatile var syncing = false
        private set

    /** Send what's waiting, then pull groups and my parts of friends' spends. Returns null if all went fine. */
    @Synchronized
    fun sync(db: Db): String? {
        if (!signedIn(db)) return null
        syncing = true
        try {
            for (o in db.outbox()) {
                try {
                    call(db, o.method, o.path, o.body?.let { JSONObject(it) })
                    db.dequeue(o.id)
                } catch (e: CloudError) {
                    // The server will never accept this one (left the group, bad data): drop it, say why.
                    if (e.status == 401) return why(e)
                    db.dequeue(o.id)
                    db.putSetting("cloud_error", "Couldn't share: ${e.message}")
                }
            }
            refreshMe(db)
            val parts = call(db, "GET", "/me/parts").getJSONArray("parts")
            db.syncFriendParts((0 until parts.length()).map { i ->
                val p = parts.getJSONObject(i)
                Db.FriendPart(
                    p.getString("shareId"), p.getString("title"), p.getString("payerName"), p.getString("groupName"),
                    p.getLong("spentAt"), p.getLong("totalPaise"), p.getLong("myPaise"), p.optString("note").takeIf { !p.isNull("note") && it.isNotBlank() },
                )
            })
            db.putSetting("cloud_synced", System.currentTimeMillis().toString())
            return null
        } catch (e: Exception) {
            return why(e)
        } finally {
            syncing = false
        }
    }

    fun syncAsync(db: Db, done: (String?) -> Unit = {}) {
        thread { done(sync(db)) }
    }

    private fun refreshMe(db: Db) {
        val r = call(db, "GET", "/me")
        // Only rewrite when something changed, so screens don't redraw for nothing.
        if (db.setting("cloud_me") != r.toString()) db.putSetting("cloud_me", r.toString())
    }

    // ---------- json ----------

    private fun parseGroups(a: JSONArray) = (0 until a.length()).map { parseGroup(a.getJSONObject(it)) }

    fun parseGroup(o: JSONObject): Group {
        val m = o.getJSONArray("members")
        val d = o.getJSONArray("debts")
        return Group(
            o.getString("id"), o.getString("name"), o.optString("emoji", "👥"), o.getString("code"),
            (0 until m.length()).map { m.getJSONObject(it).let { x -> Member(x.getString("id"), x.getString("name")) } },
            (0 until d.length()).map { d.getJSONObject(it).let { x -> Debt(x.getString("from"), x.getString("to"), x.getLong("paise")) } },
            o.optString("adminId"),
            o.optJSONArray("requests")?.let { r -> (0 until r.length()).map { r.getJSONObject(it).let { x -> Member(x.getString("id"), x.getString("name")) } } } ?: emptyList(),
        )
    }
}
