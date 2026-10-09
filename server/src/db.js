import pg from 'pg'
import { readFileSync } from 'node:fs'

// bigint columns come back as numbers (paise and ms fit easily in a JS number).
pg.types.setTypeParser(20, Number)

export function pool(url) {
  return new pg.Pool({ connectionString: url, max: 10 })
}

export async function migrate(db) {
  await db.query(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'))
}

/** Runs fn inside BEGIN/COMMIT; rolls back on any error. */
export async function tx(db, fn) {
  const c = await db.connect()
  try {
    await c.query('BEGIN')
    const r = await fn(c)
    await c.query('COMMIT')
    return r
  } catch (e) {
    await c.query('ROLLBACK')
    throw e
  } finally {
    c.release()
  }
}
