package com.x45.expense.cloud

data class Member(val id: String, val name: String) {
    val first get() = name.trim().substringBefore(' ')
}

/** "from owes to" this much. */
data class Debt(val from: String, val to: String, val paise: Long)

/** adminId = who made the group; only they let people in or out. requests = who asked to join (only the admin gets these). */
data class Group(
    val id: String, val name: String, val emoji: String, val code: String, val members: List<Member>, val debts: List<Debt>,
    val adminId: String = "", val requests: List<Member> = emptyList(),
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun nameOf(id: String, me: String?) = if (id == me) "You" else member(id)?.first ?: "Someone"
    fun isAdmin(me: String?) = me != null && me == adminId

    /** + the group owes them, - they owe the group. */
    fun netOf(id: String) = debts.filter { it.to == id }.sumOf { it.paise } - debts.filter { it.from == id }.sumOf { it.paise }
}

/** I asked to join; waiting for the admin. */
data class Pending(val groupId: String, val name: String, val emoji: String, val admin: String)

/** Money I owe one person in one group. */
data class Payback(val groupId: String, val to: Member, val paise: Long)

object Split {
    /** Equal parts that add up exactly; the first people take the leftover paise. */
    fun equal(total: Long, n: Int): List<Long> {
        if (n <= 0) return emptyList()
        val each = total / n
        val extra = (total - each * n).toInt()
        return List(n) { if (it < extra) each + 1 else each }
    }

    fun iOwe(debts: List<Debt>, me: String) = debts.filter { it.from == me }
    fun owedToMe(debts: List<Debt>, me: String) = debts.filter { it.to == me }

    /**
     * Is this spend me paying a friend back? Same amount I owe them, and their first name
     * is a whole word in the payee (so "Ravi" matches "RAVI K" and "ravi.k@okaxis", not "Ravindra").
     */
    fun paybackFor(amountPaise: Long, payee: String?, owe: List<Payback>): Payback? {
        if (payee.isNullOrBlank()) return null
        val words = payee.lowercase().split(Regex("[^a-z]+")).toSet()
        return owe.firstOrNull { it.paise == amountPaise && it.to.first.length >= 3 && it.to.first.lowercase() in words }
    }
}
