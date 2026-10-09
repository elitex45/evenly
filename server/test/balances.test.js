import { test } from 'node:test'
import assert from 'node:assert/strict'
import { nets, simplify } from '../src/balances.js'

// Asha paid ₹900 dinner for 3. Ravi and Bala each owe her ₹300.
const dinner = { payer: 'asha', amount: 90000, parts: [['asha', 30000], ['ravi', 30000], ['bala', 30000]] }

test('equal split: each friend owes the payer their part', () => {
  assert.deepEqual(simplify(nets([dinner], [])), [
    { from: 'bala', to: 'asha', paise: 30000 },
    { from: 'ravi', to: 'asha', paise: 30000 },
  ].sort((a, b) => a.from.localeCompare(b.from)))
})

test('paying back clears the debt', () => {
  const s = [{ from: 'ravi', to: 'asha', paise: 30000 }]
  assert.deepEqual(simplify(nets([dinner], s)), [{ from: 'bala', to: 'asha', paise: 30000 }])
})

test('two people paying for each other cancel out to one line', () => {
  const cab = { payer: 'ravi', amount: 40000, parts: [['asha', 20000], ['ravi', 20000]] } // Asha owes Ravi 200
  const tea = { payer: 'asha', amount: 6000, parts: [['asha', 3000], ['ravi', 3000]] }   // Ravi owes Asha 30
  assert.deepEqual(simplify(nets([cab, tea], [])), [{ from: 'asha', to: 'ravi', paise: 17000 }])
})

test('they owe me all: payer has no part', () => {
  const gift = { payer: 'asha', amount: 50000, parts: [['ravi', 50000]] }
  assert.deepEqual(simplify(nets([gift], [])), [{ from: 'ravi', to: 'asha', paise: 50000 }])
})

test('chains collapse: A owes B, B owes C → A pays C directly', () => {
  const x = { payer: 'b', amount: 10000, parts: [['a', 10000]] }
  const y = { payer: 'c', amount: 10000, parts: [['b', 10000]] }
  assert.deepEqual(simplify(nets([x, y], [])), [{ from: 'a', to: 'c', paise: 10000 }])
})

test('everyone even → no lines', () => {
  assert.deepEqual(simplify(nets([], [])), [])
})
