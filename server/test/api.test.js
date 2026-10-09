// End-to-end over HTTP against a real Postgres (scratch database "autolog_test").
// Run: docker compose -p autolog up -d db && npm test
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import pg from 'pg'
import { pool, migrate } from '../src/db.js'
import { makeApp } from '../src/app.js'

const ADMIN = process.env.TEST_ADMIN_URL ?? 'postgres://autolog:autolog-local@127.0.0.1:5544/autolog'
let db, server, base

before(async () => {
  const admin = new pg.Client(ADMIN)
  await admin.connect()
  await admin.query('DROP DATABASE IF EXISTS autolog_test')
  await admin.query('CREATE DATABASE autolog_test')
  await admin.end()
  db = pool(ADMIN.replace(/\/autolog$/, '/autolog_test'))
  await migrate(db)
  server = createServer(makeApp(db, { DEV_LOGIN: '1' })).listen(0)
  base = `http://127.0.0.1:${server.address().port}`
})
after(async () => { server.close(); await db.end() })

async function call(token, method, path, body) {
  const r = await fetch(base + path, {
    method, headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await r.text()
  return { status: r.status, body: text.startsWith('{') ? JSON.parse(text) : text }
}
const login = async (name) => (await call(null, 'POST', '/auth/dev', { email: `${name}@test.local`, name })).body
// Ask with the code, then the admin lets them in.
async function joinVia(admin, who, g) {
  assert.equal((await call(who.token, 'POST', '/groups/join', { code: g.code })).status, 202)
  assert.equal((await call(admin.token, 'POST', `/groups/${g.id}/requests/${who.user.id}/approve`)).status, 200)
}

test('full story: group, invite, share, balances, friend parts, settle', async () => {
  const asha = await login('Asha'), ravi = await login('Ravi'), bala = await login('Bala')
  const A = asha.user.id, R = ravi.user.id, B = bala.user.id

  // Asha makes a group; Ravi and Bala ask with the code and she lets them in.
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Goa trip', emoji: '🏖' })).body
  assert.match(g.code, /^[A-Z2-9]{6}$/)
  assert.equal((await call(ravi.token, 'GET', '/invites/' + g.code.toLowerCase())).body.name, 'Goa trip')
  await joinVia(asha, ravi, g)
  await joinVia(asha, bala, g)
  assert.equal((await call(bala.token, 'POST', '/groups/join', { code: g.code })).status, 200, 'asking again once in is fine')

  // Asha shares a ₹900 dinner, split 3 ways.
  const dinner = randomUUID()
  const body = { groupId: g.id, amountPaise: 90000, title: 'Dinner at Fisherman', note: 'Day 1', spentAt: 1760000000000,
    parts: [{ userId: A, paise: 30000 }, { userId: R, paise: 30000 }, { userId: B, paise: 30000 }] }
  assert.equal((await call(asha.token, 'PUT', '/shares/' + dinner, body)).status, 200)
  assert.equal((await call(asha.token, 'PUT', '/shares/' + dinner, body)).status, 200, 'retry from the outbox is harmless')

  let me = (await call(ravi.token, 'GET', '/me')).body
  assert.deepEqual(me.groups[0].debts.find((d) => d.from === R), { from: R, to: A, paise: 30000 })

  // Ravi sees his ₹300 part, "via Asha".
  const parts = (await call(ravi.token, 'GET', '/me/parts')).body.parts
  assert.equal(parts.length, 1)
  assert.equal(parts[0].myPaise, 30000)
  assert.equal(parts[0].payerName, 'Asha')
  assert.equal(parts[0].title, 'Dinner at Fisherman')
  assert.equal((await call(asha.token, 'GET', '/me/parts')).body.parts.length, 0, 'payer does not get her own share back')

  // Bala wasn't in the cab: Ravi leaves him out.
  const cab = randomUUID()
  await call(ravi.token, 'PUT', '/shares/' + cab, { groupId: g.id, amountPaise: 40000, title: 'Cab', spentAt: 1760000100000,
    parts: [{ userId: A, paise: 20000 }, { userId: R, paise: 20000 }] })
  me = (await call(asha.token, 'GET', '/me')).body
  assert.deepEqual(me.groups[0].debts, [
    { from: B, to: A, paise: 30000 },
    { from: R, to: A, paise: 10000 },
  ].sort((x, y) => x.from.localeCompare(y.from)))

  // Ravi pays Asha back ₹100: they are even.
  const st = randomUUID()
  assert.equal((await call(ravi.token, 'PUT', '/settlements/' + st, { groupId: g.id, fromId: R, toId: A, paise: 10000 })).status, 200)
  await call(ravi.token, 'PUT', '/settlements/' + st, { groupId: g.id, fromId: R, toId: A, paise: 10000 })
  me = (await call(asha.token, 'GET', '/me')).body
  assert.deepEqual(me.groups[0].debts, [{ from: B, to: A, paise: 30000 }])

  // Group screen: activity newest first.
  const detail = (await call(bala.token, 'GET', '/groups/' + g.id)).body
  assert.deepEqual(detail.members.map((m) => m.name), ['Asha', 'Ravi', 'Bala'])
  assert.equal(detail.activity.length, 3)

  // Asha un-shares the dinner: it disappears from Ravi's parts and from balances.
  assert.equal((await call(asha.token, 'DELETE', '/shares/' + dinner)).status, 200)
  assert.equal((await call(ravi.token, 'GET', '/me/parts')).body.parts.length, 0)
  me = (await call(asha.token, 'GET', '/me')).body
  // Left: cab (Asha owes Ravi ₹200) + the ₹100 Ravi paid her for the dinner that is now gone.
  assert.deepEqual(me.groups[0].debts, [{ from: A, to: R, paise: 30000 }])
})

test('rules: who can do what', async () => {
  const asha = await login('Asha2'), ravi = await login('Ravi2'), eve = await login('Eve')
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Flat' })).body
  await joinVia(asha, ravi, g)
  const A = asha.user.id, R = ravi.user.id, E = eve.user.id
  const id = randomUUID()
  const ok = { groupId: g.id, amountPaise: 1000, title: 'Milk', spentAt: 1, parts: [{ userId: A, paise: 500 }, { userId: R, paise: 500 }] }

  assert.equal((await call(null, 'GET', '/me')).status, 401, 'needs login')
  assert.equal((await call('nope', 'GET', '/me')).status, 401, 'bad token')
  assert.equal((await call(eve.token, 'GET', '/groups/' + g.id)).status, 404, 'outsiders cannot see a group')
  assert.equal((await call(eve.token, 'PUT', '/shares/' + id, ok)).status, 404, 'outsiders cannot share into a group')
  assert.equal((await call(asha.token, 'PUT', '/shares/' + id, { ...ok, amountPaise: 999 })).status, 400, 'parts must add up')
  assert.equal((await call(asha.token, 'PUT', '/shares/' + id, { ...ok, parts: [{ userId: A, paise: 500 }, { userId: E, paise: 500 }] })).status, 400, 'non-member in split')
  assert.equal((await call(asha.token, 'PUT', '/shares/' + id, { ...ok, amountPaise: 1000, parts: [{ userId: A, paise: 1000 }] })).status, 400, 'only me in split')
  assert.equal((await call(asha.token, 'PUT', '/shares/' + id, ok)).status, 200)
  assert.equal((await call(ravi.token, 'PUT', '/shares/' + id, ok)).status, 403, 'only the payer can edit')
  assert.equal((await call(ravi.token, 'DELETE', '/shares/' + id)).status, 403, 'only the payer can delete')
  assert.equal((await call(eve.token, 'PUT', '/settlements/' + randomUUID(), { groupId: g.id, fromId: R, toId: A, paise: 100 })).status, 403)
  assert.equal((await call(asha.token, 'GET', '/invites/ZZZZZZ')).status, 404)

  // Nothing private can sneak in: extra fields are dropped, not stored.
  await call(asha.token, 'PUT', '/shares/' + id, { ...ok, bank: 'HDFC Bank', account: '1234', body: 'Txn Rs.10 ...' })
  const { rows } = await db.query('SELECT * FROM shares WHERE id=$1', [id])
  assert.deepEqual(Object.keys(rows[0]).sort(), ['amount_paise', 'deleted', 'group_id', 'id', 'note', 'payer_id', 'spent_at', 'title', 'updated_at'])
})

test('dev login is off unless DEV_LOGIN=1', async () => {
  const s = createServer(makeApp(db, {})).listen(0)
  const r = await fetch(`http://127.0.0.1:${s.address().port}/auth/dev`, { method: 'POST', body: '{"email":"x@y.z"}' })
  s.close()
  assert.equal(r.status, 404)
  assert.deepEqual((await call(null, 'GET', '/config')).body, { devLogin: true, googleClientId: null })
})

test('invite link page shows the code and opens the app', async () => {
  const asha = await login('Asha3')
  const g = (await call(asha.token, 'POST', '/groups', { name: '<b>Flat</b>' })).body
  const page = (await call(null, 'GET', '/join/' + g.code)).body
  assert.ok(page.includes('evenly://join/' + g.code))
  assert.ok(!page.includes('<b>Flat</b>'), 'group name is escaped')
})

test('home and privacy pages are public', async () => {
  const home = await call(null, 'GET', '/')
  assert.equal(home.status, 200)
  assert.ok(home.body.includes('/privacy'))
  const priv = await call(null, 'GET', '/privacy')
  assert.equal(priv.status, 200)
  assert.ok(priv.body.includes('never leave your phone'))
})

test('joining needs the admin: ask, approve, deny', async () => {
  const asha = await login('Asha4'), ravi = await login('Ravi4'), bala = await login('Bala4'), eve = await login('Eve4')
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Goa trip', emoji: '🏖' })).body
  assert.equal(g.adminId, asha.user.id, 'whoever makes the group is its admin')

  // The preview tells a stranger little: name, admin, how many. Not everyone's names.
  const pre = (await call(ravi.token, 'GET', '/invites/' + g.code)).body
  assert.deepEqual(pre, { name: 'Goa trip', emoji: '🏖', admin: 'Asha4', count: 1 })

  // A code only asks. Ravi is not in yet and cannot see the group.
  const ask = await call(ravi.token, 'POST', '/groups/join', { code: g.code })
  assert.equal(ask.status, 202)
  assert.deepEqual(ask.body, { pending: true, groupId: g.id, name: 'Goa trip', emoji: '🏖', admin: 'Asha4' })
  assert.equal((await call(ravi.token, 'POST', '/groups/join', { code: g.code })).status, 202, 'asking twice is one request')
  assert.equal((await call(ravi.token, 'GET', '/groups/' + g.id)).status, 404)
  let rme = (await call(ravi.token, 'GET', '/me')).body
  assert.equal(rme.groups.length, 0)
  assert.deepEqual(rme.pending, [{ groupId: g.id, name: 'Goa trip', emoji: '🏖', admin: 'Asha4' }])

  // Asha sees who wants in.
  await call(eve.token, 'POST', '/groups/join', { code: g.code })
  let ame = (await call(asha.token, 'GET', '/me')).body
  assert.deepEqual(ame.groups[0].requests.map((r) => r.name), ['Ravi4', 'Eve4'])

  // Only the admin decides.
  await joinVia(asha, bala, g)
  assert.equal((await call(bala.token, 'POST', `/groups/${g.id}/requests/${ravi.user.id}/approve`)).status, 403)
  assert.equal((await call(bala.token, 'GET', '/me')).body.groups[0].requests, undefined, 'members do not see requests')
  assert.equal((await call(asha.token, 'POST', `/groups/${g.id}/requests/${randomUUID()}/approve`)).status, 404, 'no such request')

  // Approve Ravi: he is in. Deny Eve: she is not.
  assert.equal((await call(asha.token, 'POST', `/groups/${g.id}/requests/${ravi.user.id}/approve`)).status, 200)
  assert.equal((await call(asha.token, 'POST', `/groups/${g.id}/requests/${eve.user.id}/deny`)).status, 200)
  rme = (await call(ravi.token, 'GET', '/me')).body
  assert.deepEqual(rme.groups.map((x) => x.name), ['Goa trip'])
  assert.deepEqual(rme.pending, [])
  assert.equal(rme.groups[0].adminId, asha.user.id)
  assert.equal((await call(eve.token, 'GET', '/groups/' + g.id)).status, 404)
  assert.deepEqual((await call(eve.token, 'GET', '/me')).body.pending, [])
  ame = (await call(asha.token, 'GET', '/me')).body
  assert.deepEqual(ame.groups[0].requests, [])
  assert.deepEqual(ame.groups[0].members.map((m) => m.name), ['Asha4', 'Bala4', 'Ravi4'])
})

test('admin removes people only when they are settled', async () => {
  const asha = await login('Asha5'), ravi = await login('Ravi5'), bala = await login('Bala5')
  const A = asha.user.id, R = ravi.user.id
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Flat' })).body
  await joinVia(asha, ravi, g)
  await joinVia(asha, bala, g)
  const milk = randomUUID()
  await call(asha.token, 'PUT', '/shares/' + milk, { groupId: g.id, amountPaise: 6000, title: 'Milk', spentAt: 5,
    parts: [{ userId: A, paise: 3000 }, { userId: R, paise: 3000 }] })

  // Ravi owes ₹30: he cannot be removed yet.
  const no = await call(asha.token, 'DELETE', `/groups/${g.id}/members/${R}`)
  assert.equal(no.status, 409)
  assert.equal(no.body.error, 'Ravi5 still owes ₹30. Settle up first.')
  assert.equal((await call(bala.token, 'DELETE', `/groups/${g.id}/members/${R}`)).status, 403, 'only the admin removes')
  assert.equal((await call(asha.token, 'DELETE', `/groups/${g.id}/members/${A}`)).status, 400, 'the admin cannot remove herself')

  // Settled: now he can go. His old part stays in his own spends; the group is gone for him.
  await call(ravi.token, 'PUT', '/settlements/' + randomUUID(), { groupId: g.id, fromId: R, toId: A, paise: 3000 })
  assert.equal((await call(asha.token, 'DELETE', `/groups/${g.id}/members/${R}`)).status, 200)
  assert.equal((await call(ravi.token, 'GET', '/groups/' + g.id)).status, 404)
  assert.deepEqual((await call(ravi.token, 'GET', '/me')).body.groups, [])
  assert.equal((await call(ravi.token, 'GET', '/me/parts')).body.parts.length, 1, 'history stays')
  const detail = (await call(asha.token, 'GET', '/groups/' + g.id)).body
  assert.deepEqual(detail.members.map((m) => m.name), ['Asha5', 'Bala5'])
  assert.equal(detail.names[R], 'Ravi5', 'old activity still has his name')
  assert.deepEqual(detail.debts, [])
  // Out means out: no new shares with him.
  assert.equal((await call(asha.token, 'PUT', '/shares/' + randomUUID(), { groupId: g.id, amountPaise: 200, title: 'Tea', spentAt: 6,
    parts: [{ userId: A, paise: 100 }, { userId: R, paise: 100 }] })).status, 400)
  // He can ask to come back; it is a new request.
  assert.equal((await call(ravi.token, 'POST', '/groups/join', { code: g.code })).status, 202)
})

test('admin makes a new code; the old one stops working', async () => {
  const asha = await login('Asha6'), ravi = await login('Ravi6'), bala = await login('Bala6')
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Flat' })).body
  await joinVia(asha, ravi, g)
  assert.equal((await call(ravi.token, 'POST', `/groups/${g.id}/code`)).status, 403)
  const fresh = (await call(asha.token, 'POST', `/groups/${g.id}/code`)).body
  assert.match(fresh.code, /^[A-Z2-9]{6}$/)
  assert.notEqual(fresh.code, g.code)
  assert.equal((await call(bala.token, 'POST', '/groups/join', { code: g.code })).status, 404)
  assert.equal((await call(bala.token, 'POST', '/groups/join', { code: fresh.code })).status, 202)
})

test('too many wrong codes: blocked for a while', async () => {
  const mallory = await login('Mallory'), asha = await login('Asha7')
  const g = (await call(asha.token, 'POST', '/groups', { name: 'Flat' })).body
  for (let i = 0; i < 5; i++) {
    const r = await call(mallory.token, i % 2 ? 'GET' : 'POST', i % 2 ? '/invites/ZZZZZ' + i : '/groups/join', i % 2 ? undefined : { code: 'ZZZZZ' + i })
    assert.equal(r.status, 404, 'try ' + (i + 1))
  }
  const r = await call(mallory.token, 'POST', '/groups/join', { code: g.code })
  assert.equal(r.status, 429, 'even the right code is refused once blocked')
  assert.equal(r.body.error, 'Too many wrong codes. Try again in an hour.')
  assert.equal((await call(mallory.token, 'GET', '/invites/' + g.code)).status, 429)
  assert.equal((await call(asha.token, 'GET', '/invites/' + g.code)).status, 200, 'other people are not blocked')
})
