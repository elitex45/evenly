import { createHash, randomBytes, randomInt } from 'node:crypto'
import { tx } from './db.js'
import { nets, simplify } from './balances.js'

// Small JSON API. Every route except login needs "Authorization: Bearer <token>".
//
// What a phone can send here: group names, and for a spend it chose to share:
// amount, title (who was paid), note, time and how it is split. Nothing else is accepted.

class HttpError extends Error {
  constructor(status, msg) { super(msg); this.status = status }
}
const bad = (m) => new HttpError(400, m)
/** A reply with a status other than 200. */
class Reply { constructor(status, body) { this.status = status; this.body = body } }
const rupees = (p) => '₹' + (p % 100 ? (p / 100).toFixed(2) : String(p / 100))
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const CODE_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789' // no 0/O, 1/I/L

const sha = (s) => createHash('sha256').update(s).digest('hex')
const str = (v, max, name) => {
  if (typeof v !== 'string' || !v.trim()) throw bad(`${name} is required`)
  return v.trim().slice(0, max)
}
const optStr = (v, max) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null)
const paise = (v, name) => {
  if (!Number.isSafeInteger(v) || v <= 0) throw bad(`${name} must be a positive whole number of paise`)
  return v
}
const uuid = (v, name) => {
  if (typeof v !== 'string' || !UUID.test(v)) throw bad(`${name} must be a uuid`)
  return v.toLowerCase()
}

export function makeApp(db, env = {}) {
  const routes = []
  // Wrong invite codes: after JOIN_TRIES misses, that person (or that IP, for the web page) waits an hour.
  const TRIES = Number(env.JOIN_TRIES ?? 5), HOUR = 3600_000
  const misses = new Map()
  const blocked = (key) => {
    const m = misses.get(key)
    if (m && Date.now() - m.since > HOUR) misses.delete(key)
    return (misses.get(key)?.n ?? 0) >= TRIES
  }
  const missed = (key) => {
    const m = misses.get(key) ?? { n: 0, since: Date.now() }
    m.n++; misses.set(key, m)
    if (misses.size > 100_000) misses.clear() // never grow without end
  }
  /** The group for a code, counting misses. 429 once blocked, even for a right code. */
  async function byCode(key, code) {
    if (blocked(key)) throw new HttpError(429, 'Too many wrong codes. Try again in an hour.')
    const { rows } = await db.query(
      'SELECT g.*, u.name AS admin_name FROM groups g JOIN users u ON u.id=g.created_by WHERE g.code=$1', [code.toUpperCase()])
    if (!rows.length) { missed(key); throw new HttpError(404, 'That invite code is not right') }
    return rows[0]
  }
  const on = (method, path, fn, open = false) => {
    const keys = []
    const rx = new RegExp('^' + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)' }) + '$')
    routes.push({ method, rx, keys, fn, open })
  }

  async function session(user) {
    const token = randomBytes(32).toString('base64url')
    await db.query('INSERT INTO sessions(token_hash, user_id) VALUES ($1, $2)', [sha(token), user.id])
    return { token, user }
  }

  async function upsertUser(email, name) {
    const { rows } = await db.query(
      `INSERT INTO users(email, name) VALUES (lower($1), $2)
       ON CONFLICT (email) DO UPDATE SET name = users.name RETURNING id, email, name`, [email, name])
    return rows[0]
  }

  async function memberOf(c, groupId, userId) {
    const { rowCount } = await c.query('SELECT 1 FROM members WHERE group_id=$1 AND user_id=$2', [groupId, userId])
    if (!rowCount) throw new HttpError(404, 'group not found')
  }

  async function members(c, groupId) {
    const { rows } = await c.query(
      `SELECT u.id, u.name FROM members m JOIN users u ON u.id=m.user_id WHERE m.group_id=$1 ORDER BY m.joined_at, u.name`, [groupId])
    return rows
  }

  async function admin(c, groupId, me) {
    await memberOf(c, groupId, me.id)
    const { rows } = await c.query('SELECT * FROM groups WHERE id=$1', [groupId])
    if (rows[0].created_by !== me.id) throw new HttpError(403, 'only the group admin can do this')
    return rows[0]
  }

  /** "X owes Y" lines for one group. */
  async function balances(c, groupId) {
    return simplify(await groupNets(c, groupId))
  }

  /** Each person's net in a group: + the group owes them, - they owe. */
  async function groupNets(c, groupId) {
    const sh = await c.query(
      `SELECT s.payer_id, s.amount_paise, COALESCE(json_agg(json_build_array(p.user_id, p.paise)) FILTER (WHERE p.user_id IS NOT NULL), '[]') AS parts
         FROM shares s LEFT JOIN share_parts p ON p.share_id=s.id
        WHERE s.group_id=$1 AND NOT s.deleted GROUP BY s.id`, [groupId])
    const st = await c.query('SELECT from_id, to_id, paise FROM settlements WHERE group_id=$1 AND NOT deleted', [groupId])
    return nets(
      sh.rows.map((r) => ({ payer: r.payer_id, amount: r.amount_paise, parts: r.parts.map(([u, p]) => [u, Number(p)]) })),
      st.rows.map((r) => ({ from: r.from_id, to: r.to_id, paise: r.paise })),
    )
  }

  async function groupSummary(c, g) {
    return { id: g.id, name: g.name, emoji: g.emoji, code: g.code, adminId: g.created_by, members: await members(c, g.id), debts: await balances(c, g.id) }
  }

  // ---------- login ----------

  // Which sign-in buttons the phone should show.
  on('GET', '/config', async () => ({ devLogin: env.DEV_LOGIN === '1', googleClientId: env.GOOGLE_CLIENT_ID || null }), true)

  // Test login for local runs and demos. Off unless DEV_LOGIN=1.
  on('POST', '/auth/dev', async ({ body }) => {
    if (env.DEV_LOGIN !== '1') throw new HttpError(404, 'not found')
    const email = str(body.email, 200, 'email')
    return session(await upsertUser(email, str(body.name ?? email.split('@')[0], 60, 'name')))
  }, true)

  // Google sign-in: the phone sends the ID token Google gave it; we ask Google if it is real and for us.
  on('POST', '/auth/google', async ({ body }) => {
    if (!env.GOOGLE_CLIENT_ID) throw new HttpError(503, 'Google sign-in is not set up on this server')
    const r = await fetch('https://oauth2.googleapis.com/tokeninfo?id_token=' + encodeURIComponent(str(body.idToken, 4096, 'idToken')))
    const t = await r.json().catch(() => ({}))
    if (!r.ok || t.aud !== env.GOOGLE_CLIENT_ID || t.email_verified !== 'true' || !t.email) throw new HttpError(401, 'Google sign-in failed')
    return session(await upsertUser(t.email, (t.name || t.email.split('@')[0]).slice(0, 60)))
  }, true)

  on('POST', '/auth/logout', async ({ token }) => {
    await db.query('DELETE FROM sessions WHERE token_hash=$1', [sha(token)])
    return { ok: true }
  })

  // ---------- me & groups ----------

  on('GET', '/me', async ({ me }) => {
    const { rows } = await db.query(
      `SELECT g.* FROM groups g JOIN members m ON m.group_id=g.id WHERE m.user_id=$1 ORDER BY m.joined_at`, [me.id])
    const groups = []
    for (const g of rows) {
      const s = await groupSummary(db, g)
      // Only the admin sees who is asking to join.
      if (g.created_by === me.id) {
        s.requests = (await db.query(
          `SELECT u.id, u.name FROM join_requests r JOIN users u ON u.id=r.user_id WHERE r.group_id=$1 ORDER BY r.created_at, u.name`, [g.id])).rows
      }
      groups.push(s)
    }
    const pending = (await db.query(
      `SELECT g.id AS "groupId", g.name, g.emoji, u.name AS admin
         FROM join_requests r JOIN groups g ON g.id=r.group_id JOIN users u ON u.id=g.created_by
        WHERE r.user_id=$1 ORDER BY r.created_at`, [me.id])).rows
    return { user: me, groups, pending }
  })

  on('POST', '/groups', async ({ me, body }) => {
    const name = str(body.name, 60, 'name')
    const emoji = optStr(body.emoji, 16) ?? '👥'
    return tx(db, async (c) => {
      for (let tries = 0; ; tries++) {
        const code = Array.from({ length: 6 }, () => CODE_CHARS[randomInt(CODE_CHARS.length)]).join('')
        const r = await c.query(
          `INSERT INTO groups(name, emoji, code, created_by) VALUES ($1,$2,$3,$4) ON CONFLICT (code) DO NOTHING RETURNING *`,
          [name, emoji, code, me.id])
        if (r.rowCount) {
          await c.query('INSERT INTO members(group_id, user_id) VALUES ($1,$2)', [r.rows[0].id, me.id])
          return groupSummary(c, r.rows[0])
        }
        if (tries > 5) throw new Error('could not make an invite code')
      }
    })
  })

  // Look before you ask: group name, who runs it, how many. Not everyone's names (a code can leak).
  on('GET', '/invites/:code', async ({ me, params }) => {
    const g = await byCode('u:' + me.id, params.code)
    const { rows } = await db.query('SELECT count(*)::int AS n FROM members WHERE group_id=$1', [g.id])
    return { name: g.name, emoji: g.emoji, admin: g.admin_name, count: rows[0].n }
  })

  // A code only asks to join. The admin lets you in (or not).
  on('POST', '/groups/join', async ({ me, body }) => {
    const g = await byCode('u:' + me.id, str(body.code, 12, 'code'))
    const inside = await db.query('SELECT 1 FROM members WHERE group_id=$1 AND user_id=$2', [g.id, me.id])
    if (inside.rowCount) return groupSummary(db, g)
    await db.query('INSERT INTO join_requests(group_id, user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING', [g.id, me.id])
    return new Reply(202, { pending: true, groupId: g.id, name: g.name, emoji: g.emoji, admin: g.admin_name })
  })

  async function decide(me, params, approve) {
    const id = uuid(params.id, 'group id'), who = uuid(params.user, 'user id')
    return tx(db, async (c) => {
      await admin(c, id, me)
      const r = await c.query('DELETE FROM join_requests WHERE group_id=$1 AND user_id=$2', [id, who])
      if (!r.rowCount) throw new HttpError(404, 'no such request')
      if (approve) await c.query('INSERT INTO members(group_id, user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING', [id, who])
      return { ok: true }
    })
  }
  on('POST', '/groups/:id/requests/:user/approve', ({ me, params }) => decide(me, params, true))
  on('POST', '/groups/:id/requests/:user/deny', ({ me, params }) => decide(me, params, false))

  // Admin removes someone. Only when they are settled, so nobody's money disappears with them.
  on('DELETE', '/groups/:id/members/:user', async ({ me, params }) => {
    const id = uuid(params.id, 'group id'), who = uuid(params.user, 'user id')
    if (who === me.id) throw bad('The admin cannot remove themselves')
    return tx(db, async (c) => {
      await admin(c, id, me)
      // Lock the member list so no share or payment lands while we check the balance.
      const { rows } = await c.query(
        'SELECT m.user_id, u.name FROM members m JOIN users u ON u.id=m.user_id WHERE m.group_id=$1 FOR UPDATE OF m', [id])
      const them = rows.find((r) => r.user_id === who)
      if (!them) throw new HttpError(404, 'not in this group')
      const net = (await groupNets(c, id)).get(who) ?? 0
      if (net < 0) throw new HttpError(409, `${them.name} still owes ${rupees(-net)}. Settle up first.`)
      if (net > 0) throw new HttpError(409, `${them.name} is still owed ${rupees(net)}. Settle up first.`)
      await c.query('DELETE FROM members WHERE group_id=$1 AND user_id=$2', [id, who])
      return { ok: true }
    })
  })

  // New invite code. The old one stops working.
  on('POST', '/groups/:id/code', async ({ me, params }) => {
    const id = uuid(params.id, 'group id')
    await admin(db, id, me)
    for (let tries = 0; ; tries++) {
      const code = Array.from({ length: 6 }, () => CODE_CHARS[randomInt(CODE_CHARS.length)]).join('')
      const r = await db.query(
        `UPDATE groups SET code=$2 WHERE id=$1 AND NOT EXISTS (SELECT 1 FROM groups WHERE code=$2) RETURNING code`, [id, code])
      if (r.rowCount) return { code }
      if (tries > 5) throw new Error('could not make an invite code')
    }
  })

  // One group: members, who owes whom, and what happened (newest first).
  on('GET', '/groups/:id', async ({ me, params }) => {
    const id = uuid(params.id, 'group id')
    await memberOf(db, id, me.id)
    const { rows: [g] } = await db.query('SELECT * FROM groups WHERE id=$1', [id])
    const shares = await db.query(
      `SELECT s.id, s.payer_id, s.amount_paise, s.title, s.note, s.spent_at,
              COALESCE(json_agg(json_build_object('userId', p.user_id, 'paise', p.paise)) FILTER (WHERE p.user_id IS NOT NULL), '[]') AS parts
         FROM shares s LEFT JOIN share_parts p ON p.share_id=s.id
        WHERE s.group_id=$1 AND NOT s.deleted GROUP BY s.id ORDER BY s.spent_at DESC LIMIT 100`, [id])
    const settles = await db.query(
      `SELECT id, from_id, to_id, paise, (extract(epoch FROM created_at)*1000)::bigint AS at
         FROM settlements WHERE group_id=$1 AND NOT deleted ORDER BY created_at DESC LIMIT 100`, [id])
    const activity = [
      ...shares.rows.map((s) => ({
        type: 'share', id: s.id, payerId: s.payer_id, amountPaise: s.amount_paise, title: s.title, note: s.note, at: s.spent_at,
        parts: s.parts.map((p) => ({ userId: p.userId, paise: Number(p.paise) })),
      })),
      ...settles.rows.map((s) => ({ type: 'settle', id: s.id, fromId: s.from_id, toId: s.to_id, paise: s.paise, at: s.at })),
    ].sort((a, b) => b.at - a.at)
    // Names for everyone in the activity, including people who were removed since.
    const ids = new Set(activity.flatMap((a) => (a.type === 'share' ? [a.payerId, ...a.parts.map((p) => p.userId)] : [a.fromId, a.toId])))
    const names = {}
    if (ids.size) for (const u of (await db.query('SELECT id, name FROM users WHERE id = ANY($1)', [[...ids]])).rows) names[u.id] = u.name
    return { ...(await groupSummary(db, g)), activity, names }
  })

  // ---------- shared spends ----------

  // Create or update. Only the person who paid can. parts = who owes what (payer may be in it too).
  on('PUT', '/shares/:id', async ({ me, params, body }) => {
    const id = uuid(params.id, 'share id')
    const groupId = uuid(body.groupId, 'groupId')
    const amount = paise(body.amountPaise, 'amountPaise')
    const title = str(body.title, 80, 'title')
    const note = optStr(body.note, 200)
    const spentAt = body.spentAt
    if (!Number.isSafeInteger(spentAt) || spentAt <= 0) throw bad('spentAt must be ms since 1970')
    if (!Array.isArray(body.parts) || !body.parts.length) throw bad('parts are required')
    const parts = body.parts.map((p) => [uuid(p.userId, 'part userId'), paise(p.paise, 'part paise')])
    if (new Set(parts.map((p) => p[0])).size !== parts.length) throw bad('a person is in parts twice')
    if (parts.reduce((s, p) => s + p[1], 0) !== amount) throw bad('parts must add up to the amount')
    if (parts.length === 1 && parts[0][0] === me.id) throw bad('nobody else is in this split')

    return tx(db, async (c) => {
      await memberOf(c, groupId, me.id)
      await c.query('SELECT 1 FROM members WHERE group_id=$1 FOR SHARE', [groupId]) // waits for a removal in progress
      const ids = new Set((await members(c, groupId)).map((m) => m.id))
      if (!parts.every(([u]) => ids.has(u))) throw bad('everyone in the split must be in the group')
      const old = await c.query('SELECT payer_id FROM shares WHERE id=$1 FOR UPDATE', [id])
      if (old.rowCount && old.rows[0].payer_id !== me.id) throw new HttpError(403, 'only the person who paid can change this')
      await c.query(
        `INSERT INTO shares(id, group_id, payer_id, amount_paise, title, note, spent_at) VALUES ($1,$2,$3,$4,$5,$6,$7)
         ON CONFLICT (id) DO UPDATE SET group_id=$2, amount_paise=$4, title=$5, note=$6, spent_at=$7, deleted=false, updated_at=now()`,
        [id, groupId, me.id, amount, title, note, spentAt])
      await c.query('DELETE FROM share_parts WHERE share_id=$1', [id])
      for (const [u, p] of parts) await c.query('INSERT INTO share_parts(share_id, user_id, paise) VALUES ($1,$2,$3)', [id, u, p])
      return { id, ok: true }
    })
  })

  // Un-share. Kept as deleted so friends' phones learn it is gone.
  on('DELETE', '/shares/:id', async ({ me, params }) => {
    const id = uuid(params.id, 'share id')
    const r = await db.query('UPDATE shares SET deleted=true, updated_at=now() WHERE id=$1 AND payer_id=$2', [id, me.id])
    const exists = await db.query('SELECT 1 FROM shares WHERE id=$1', [id])
    if (!r.rowCount && exists.rowCount) throw new HttpError(403, 'only the person who paid can remove this')
    return { id, ok: true } // unknown id: already gone, fine for a retry
  })

  // My part of what friends paid. The phone adds these to my own spends ("via Ravi").
  // Not limited to groups I am still in: if I was removed, my past spends stay mine.
  on('GET', '/me/parts', async ({ me }) => {
    const { rows } = await db.query(
      `SELECT s.id, s.group_id, g.name AS group_name, u.name AS payer_name, s.title, s.note, s.spent_at, s.amount_paise, p.paise
         FROM share_parts p JOIN shares s ON s.id=p.share_id JOIN groups g ON g.id=s.group_id JOIN users u ON u.id=s.payer_id
        WHERE p.user_id=$1 AND s.payer_id<>$1 AND NOT s.deleted ORDER BY s.spent_at DESC`, [me.id])
    return {
      parts: rows.map((r) => ({
        shareId: r.id, groupId: r.group_id, groupName: r.group_name, payerName: r.payer_name,
        title: r.title, note: r.note, spentAt: r.spent_at, totalPaise: r.amount_paise, myPaise: r.paise,
      })),
    }
  })

  // ---------- settle up ----------

  // "Paid back". Either side can record it; it must involve me.
  on('PUT', '/settlements/:id', async ({ me, params, body }) => {
    const id = uuid(params.id, 'settlement id')
    const groupId = uuid(body.groupId, 'groupId')
    const from = uuid(body.fromId, 'fromId')
    const to = uuid(body.toId, 'toId')
    const p = paise(body.paise, 'paise')
    if (from === to) throw bad('from and to are the same person')
    if (from !== me.id && to !== me.id) throw new HttpError(403, 'you can only record payments you made or got')
    return tx(db, async (c) => {
      await memberOf(c, groupId, me.id)
      await c.query('SELECT 1 FROM members WHERE group_id=$1 FOR SHARE', [groupId])
      const ids = new Set((await members(c, groupId)).map((m) => m.id))
      if (!ids.has(from) || !ids.has(to)) throw bad('both people must be in the group')
      await c.query(
        `INSERT INTO settlements(id, group_id, from_id, to_id, paise, created_by) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO NOTHING`,
        [id, groupId, from, to, p, me.id])
      return { id, ok: true }
    })
  })

  on('DELETE', '/settlements/:id', async ({ me, params }) => {
    const id = uuid(params.id, 'settlement id')
    await db.query('UPDATE settlements SET deleted=true WHERE id=$1 AND created_by=$2', [id, me.id])
    return { id, ok: true }
  })

  // ---------- invite page ----------

  // A link people can tap in WhatsApp. It shows the code and opens the app.
  async function invitePage(code, ip) {
    const safe = code.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12)
    let rows = []
    try { rows = [await byCode('ip:' + ip, safe)] } catch (e) { if (e.status !== 404 && e.status !== 429) throw e }
    const esc = (s) => s.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`)
    const title = rows.length ? `${esc(rows[0].emoji)} Join ${esc(rows[0].name)} on Evenly` : 'Invite not found'
    return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<body style="background:#0b0b0d;color:#f3efe6;font:16px system-ui;display:grid;place-items:center;min-height:90vh;text-align:center;margin:0 16px">
<div><h1 style="font-weight:600">${title}</h1>
${rows.length ? `<p>Invite code</p><p style="font-size:34px;letter-spacing:6px;color:#e6c88e">${safe}</p>
<p><a href="evenly://join/${safe}" style="display:inline-block;padding:14px 26px;border-radius:14px;background:#e6c88e;color:#1a140a;text-decoration:none;font-weight:700">Open in Evenly</a></p>
<p style="color:#8d8a84">Or open Evenly → More → Groups → Join, and type the code.</p>` : ''}</div></body>`
  }

  // ---------- public pages (Google sign-in needs a home page and a privacy policy) ----------

  const page = (title, body) => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<body style="background:#0b0b0d;color:#f3efe6;font:16px/1.6 system-ui;max-width:640px;margin:40px auto;padding:0 16px">
${body}</body>`
  const HOME = page('Evenly', `<h1 style="color:#e6c88e">Evenly</h1>
<p>Evenly reads your bank texts on your phone and keeps a list of what you spend. You can split spends with friends in a group.</p>
<p><a style="color:#e6c88e" href="/privacy">Privacy policy</a></p>`)
  const PRIVACY = page('Evenly privacy policy', `<h1 style="color:#e6c88e">Privacy policy</h1>
<p>Your bank texts, card numbers and bank names never leave your phone. Evenly reads them on the phone only.</p>
<h2>What the server keeps</h2>
<ul><li>Your Google email and name, so friends in your groups know who you are.</li>
<li>Groups you make or join.</li>
<li>Spends you choose to share with a group: amount, title, note and date.</li>
<li>Settle-ups you record.</li></ul>
<p>Only people in the same group can see that group's spends. We do not sell or share your data, and we show no ads.</p>
<h2>Delete your data</h2>
<p>Email <a style="color:#e6c88e" href="mailto:3litedev@gmail.com">3litedev@gmail.com</a> from your Google email and we will delete your account and everything it shared.</p>`)

  // ---------- server ----------

  return async function handle(req, res) {
    const send = (status, obj, type = 'application/json') => {
      res.writeHead(status, { 'content-type': type + '; charset=utf-8', 'cache-control': 'no-store' })
      res.end(type === 'application/json' ? JSON.stringify(obj) : obj)
    }
    try {
      const url = new URL(req.url, 'http://x')
      if (req.method === 'GET' && url.pathname === '/health') return send(200, { ok: true })
      if (req.method === 'GET' && url.pathname === '/') return send(200, HOME, 'text/html')
      if (req.method === 'GET' && url.pathname === '/privacy') return send(200, PRIVACY, 'text/html')
      const join = url.pathname.match(/^\/join\/([A-Za-z0-9]{1,12})$/)
      if (req.method === 'GET' && join) return send(200, await invitePage(join[1], (env.TRUST_CF === '1' && req.headers['cf-connecting-ip']) || req.socket.remoteAddress || '?'), 'text/html')

      let route, params = {}
      for (const r of routes) {
        const m = r.method === req.method && url.pathname.match(r.rx)
        if (m) { route = r; r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1]))); break }
      }
      if (!route) return send(404, { error: 'not found' })

      let raw = ''
      for await (const chunk of req) {
        raw += chunk
        if (raw.length > 64 * 1024) throw new HttpError(413, 'too big')
      }
      let body = {}
      if (raw) { try { body = JSON.parse(raw) } catch { throw bad('body is not JSON') } }
      if (typeof body !== 'object' || body === null || Array.isArray(body)) throw bad('body must be a JSON object')

      const token = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '')
      let me = null
      if (!route.open) {
        if (!token) throw new HttpError(401, 'sign in first')
        const { rows } = await db.query(
          'SELECT u.id, u.email, u.name FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=$1', [sha(token)])
        if (!rows.length) throw new HttpError(401, 'sign in again')
        me = rows[0]
      }
      const out = await route.fn({ me, body, params, token, query: url.searchParams })
      if (out instanceof Reply) send(out.status, out.body)
      else send(200, out)
    } catch (e) {
      if (e instanceof HttpError) return send(e.status, { error: e.message })
      console.error(e)
      send(500, { error: 'server error' })
    }
  }
}
