// Who owes whom in a group. All money is in paise (integers), so nothing rounds.

/**
 * Each person's net: positive = the group owes them, negative = they owe the group.
 * shares: [{ payer, amount, parts: [[userId, paise], ...] }]
 * settlements: [{ from, to, paise }]  (from paid to back)
 */
export function nets(shares, settlements) {
  const n = new Map()
  const add = (u, p) => n.set(u, (n.get(u) ?? 0) + p)
  for (const s of shares) {
    add(s.payer, s.amount)
    for (const [u, p] of s.parts) add(u, -p)
  }
  for (const s of settlements) {
    add(s.from, s.paise)
    add(s.to, -s.paise)
  }
  return n
}

/** Fewest "X owes Y ₹Z" lines: biggest debtor pays biggest creditor, repeat. Sorted by who owes. */
export function simplify(net) {
  const owe = [], owed = []
  for (const [u, p] of net) {
    if (p < 0) owe.push([u, -p])
    else if (p > 0) owed.push([u, p])
  }
  const big = (a, b) => b[1] - a[1] || a[0].localeCompare(b[0])
  owe.sort(big); owed.sort(big)
  const out = []
  let i = 0, j = 0
  while (i < owe.length && j < owed.length) {
    const p = Math.min(owe[i][1], owed[j][1])
    out.push({ from: owe[i][0], to: owed[j][0], paise: p })
    owe[i][1] -= p; owed[j][1] -= p
    if (owe[i][1] === 0) i++
    if (owed[j][1] === 0) j++
  }
  return out.sort((a, b) => a.from.localeCompare(b.from) || a.to.localeCompare(b.to))
}
