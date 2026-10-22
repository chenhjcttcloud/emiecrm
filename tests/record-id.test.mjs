import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {recordId} from '../src/recordId.js';

test('uses native UUID with the crypto receiver and preserves record prefixes', () => {
  const cryptoApi = {randomUUID() { assert.equal(this, cryptoApi); return 'native-uuid'; }};
  assert.equal(recordId('c', cryptoApi), 'cnative-uuid');
  assert.equal(recordId('p', cryptoApi), 'pnative-uuid');
});

test('LAN HTTP crypto without randomUUID generates distinct v4 record IDs', () => {
  const cryptoApi = {getRandomValues: webcrypto.getRandomValues.bind(webcrypto)};
  for (const prefix of ['c', 'p']) {
    const ids = Array.from({length:1000}, () => recordId(prefix, cryptoApi));
    for (const id of ids) assert.match(id, new RegExp(`^${prefix}[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`));
    assert.equal(new Set(ids).size, ids.length);
  }
});
