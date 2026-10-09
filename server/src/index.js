import { createServer } from 'node:http'
import { pool, migrate } from './db.js'
import { makeApp } from './app.js'

const env = process.env
if (!env.DATABASE_URL) { console.error('DATABASE_URL is required'); process.exit(1) }
const db = pool(env.DATABASE_URL)
await migrate(db)
const port = Number(env.PORT ?? 8790)
createServer(makeApp(db, env)).listen(port, env.HOST, () =>  // HOST=127.0.0.1 behind a tunnel
  console.log(`evenly server on :${port}  dev-login=${env.DEV_LOGIN === '1' ? 'ON' : 'off'}  google=${env.GOOGLE_CLIENT_ID ? 'on' : 'off'}`))
