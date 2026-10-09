<p align="center"><img src="docs/logo.png" width="128" alt="Evenly logo"></p>

<h1 align="center">Evenly</h1>

<p align="center">Your spends, written down by themselves. Split them with friends.</p>

Evenly is an Android app. It reads the texts your bank sends ("Rs 450 debited at Chai Point…") and turns them into an expense diary. No typing.

When you spend with friends, share the spend to a group. Evenly works out who owes whom.

## Your bank texts stay on your phone

- Texts are read and parsed **on the phone**. They never go to any server.
- Only the spends you choose to share go to a group: amount, title, note and date.
- No bank names, card numbers or texts are ever uploaded.

Privacy policy: https://evenlyapi.x45.in/privacy

## What it does

**On your phone (no account needed)**
- Finds spends in bank texts as they arrive, from many Indian banks.
- Tags them by category and shop, and asks you to review the unsure ones.
- Shows your month: spent so far, budget left, safe to spend per day.
- Insights by category and by place.
- Lets you add cash spends by hand.
- Has an app lock, and CSV export and backup.

**With friends (sign in with Google)**
- Groups with invite links. The admin approves who joins.
- Share a spend and split it equally or by amounts.
- Balances show who owes whom, and you can record a settle-up.
- Works offline. Shares wait and send when you are back online.

## Install

Download **Evenly-2.0.apk** from [Releases](https://github.com/elitex45/evenly/releases/latest) on your Android phone (Android 8 or newer) and open it.

## Build it yourself

```bash
# App (needs JDK 17 and the Android SDK)
./gradlew assembleRelease testDebugUnitTest

# Server (needs Node 22 and Docker)
cd server
docker compose -p evenly up -d --build   # API on :8790 with Postgres
npm test                                 # needs the Postgres above
```

Server settings (environment variables):

| Name | What it does |
|---|---|
| `DATABASE_URL` | Postgres connection |
| `PORT`, `HOST` | Where the API listens (default `:8790`) |
| `GOOGLE_CLIENT_ID` | Web OAuth client id; turns on Google sign-in |
| `DEV_LOGIN=1` | Test login without Google. **Never on a real server.** |
| `TRUST_CF=1` | Behind Cloudflare: trust `cf-connecting-ip` for rate limits |

The app talks to `https://evenlyapi.x45.in` by default. You can change it under **More → Groups → Server address**.

## Layout

```
app/      Android app (Kotlin, Jetpack Compose, on-device SQLite)
  sms/    bank text parsing and tagging
  data/   local database, backup, stats
  cloud/  group server client, Google sign-in, split maths
  ui/     screens
server/   group API (Node 22, Postgres)
```
