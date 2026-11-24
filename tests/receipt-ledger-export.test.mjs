import test from 'node:test';
import assert from 'node:assert/strict';
import {receiptLedgerCsv} from '../src/receiptLedgerExport.js';

test('receipt CSV shows business numbers while retaining original receipt and order relationships',()=>{
  const orders=[{id:'JS-internal-uuid',orderNumber:'2026-11-00000001',source:'jushuitan-settlement'},
    {id:'2026-11-00000002'}];
  const rows=[{id:'receipt-1',orderId:orders[0].id,customer:'客户,名称',amount:20},
    {id:'receipt-2',orderId:orders[1].id,amount:30},
    {id:'receipt-3',orderId:'unmapped-history-order',amount:40}];
  const before=structuredClone({orders,rows});
  const csv=receiptLedgerCsv(rows,orders);
  assert.ok(csv.startsWith('\uFEFF"订单编号"'));
  assert.match(csv,/\r\n"2026-11-00000001","客户,名称"/);
  assert.match(csv,/\r\n"2026-11-00000002"/);
  assert.match(csv,/\r\n"unmapped-history-order"/);
  assert.doesNotMatch(csv,/JS-internal-uuid/);
  assert.deepEqual({orders,rows},before);
});

test('receipt CSV uses projected business numbers when the associated order is outside the current state',()=>{
  const csv=receiptLedgerCsv([{orderId:'JS-internal-uuid',orderNumber:'2026-11-00000003',amount:10}],[]);
  assert.match(csv,/\r\n"2026-11-00000003"/);
  assert.doesNotMatch(csv,/JS-internal-uuid/);
});

test('historical receipt order fields map to the current business number',()=>{
  const csv=receiptLedgerCsv([{order:'JS-internal-uuid',amount:10}],
    [{id:'JS-internal-uuid',orderNumber:'2026-11-00000004'}]);
  assert.match(csv,/\r\n"2026-11-00000004"/);
});
