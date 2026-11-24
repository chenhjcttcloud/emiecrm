import {test} from 'node:test';
import assert from 'node:assert/strict';
import {nextOrderNumber,orderNumber,orderNumberForId} from '../src/orderNumber.js';
import {salesItemsCsv} from '../src/salesModel.js';

const supplement=Object.freeze({id:'JS-internal-uuid',source:'jushuitan-settlement',orderNumber:'2026-11-00000001'});
const ordinary=Object.freeze({id:'2026-11-00000008',type:'销售订单'});
const orders=Object.freeze([supplement,ordinary]);

test('ordinary orders continue the shared sequence after a settlement supplement occupies a number',()=>{
  const existing=[{id:'2026-10-00000007'},
    {id:'JS-internal-uuid',source:'jushuitan-settlement',orderNumber:'2026-11-00000008'}];
  const before=structuredClone(existing);
  assert.equal(nextOrderNumber(existing,new Date('2026-11-24T00:00:00Z')),'2026-11-00000009');
  existing.push({id:'2026-11-00000009'});
  assert.equal(nextOrderNumber(existing,new Date('2026-11-24T00:00:00Z')),'2026-11-00000010');
  assert.deepEqual(existing.slice(0,2),before);
});

test('shared order numbering continues globally across months and uses the current Beijing month',()=>{
  const existing=[{id:'2026-10-00000012'},
    {id:'JS-internal-uuid',orderNumber:'2026-11-00000013'}];
  assert.equal(nextOrderNumber(existing,new Date('2026-11-30T16:00:00Z')),'2026-12-00000014');
  assert.equal(nextOrderNumber([],new Date('2026-11-30T15:59:59Z')),'2026-11-00000001');
  assert.equal(nextOrderNumber([],new Date('2026-11-30T16:00:00Z')),'2026-12-00000001');
});

test('both standard number fields reserve their suffix while legacy UUIDs do not',()=>{
  const existing=[{id:'2026-10-00000024',orderNumber:'2026-11-00000021'},
    {id:'JS-12345678-1234-4321-8765-999999999999'},
    {id:'2026-11-short',orderNumber:'not-a-business-number'},
    {id:'receipt-999999999999'}];
  assert.equal(nextOrderNumber(existing,new Date('2026-11-24T00:00:00Z')),'2026-11-00000025');
  assert.equal(nextOrderNumber([...existing,{id:'2026-11-00000031',deletedAt:'2026-11-23'}],new Date('2026-11-24T00:00:00Z')),'2026-11-00000032');
  assert.equal(nextOrderNumber([{id:'JS-12345678-1234-4321-8765-999999999999'}],new Date('2026-11-24T00:00:00Z')),'2026-11-00000001');
  assert.equal(nextOrderNumber([{id:'2026-11-9999999999999999'}],new Date('2026-11-24T00:00:00Z')),'2026-11-10000000000000000');
});

test('business order numbers display independently of immutable relationship IDs',()=>{
  assert.equal(orderNumber(supplement),'2026-11-00000001');
  assert.equal(orderNumber(ordinary),'2026-11-00000008');
  assert.equal(orderNumber({id:'JS-unmigrated-uuid'}),'JS-unmigrated-uuid');
  assert.equal(orderNumber({id:'ordinary',orderNumber:''}),'ordinary');
  assert.equal(orderNumber(null),'');
  assert.equal(orderNumber(undefined),'');
  assert.equal(supplement.id,'JS-internal-uuid');
});

test('receipts and approval allocations resolve display numbers while retaining their original order IDs',()=>{
  const allocation=Object.freeze({orderId:supplement.id,amount:12});
  assert.equal(orderNumberForId(allocation.orderId,orders),supplement.orderNumber);
  assert.equal(orderNumberForId(ordinary.id,orders),ordinary.id);
  assert.equal(orderNumberForId('missing',orders),'missing');
  assert.equal(orderNumberForId('historical'),'historical');
  assert.equal(orderNumberForId(null,orders),'');
  assert.equal(allocation.orderId,'JS-internal-uuid');
});

test('sales exports use projected numbers or the order lookup and keep ordinary order numbers',()=>{
  const rows=[
    {orderId:supplement.id,product:'结算补录',amount:100},
    {orderId:'other-internal',orderNumber:'2026-11-00000002',product:'投影记录'},
    {orderId:ordinary.id,product:'普通商品'},
  ];
  const csv=salesItemsCsv(rows,false,orders);
  assert.match(csv,/2026-11-00000001/);
  assert.match(csv,/2026-11-00000002/);
  assert.match(csv,/2026-11-00000008/);
  assert.doesNotMatch(csv,/JS-internal-uuid|other-internal/);
  assert.equal(rows[0].orderId,'JS-internal-uuid');
  assert.equal(rows[1].orderId,'other-internal');
});
