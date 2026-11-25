import {test} from 'node:test';
import assert from 'node:assert/strict';
import {reserveOrderNumber,orderNumber,orderNumberForId} from '../src/orderNumber.js';
import {salesItemsCsv} from '../src/salesModel.js';

const supplement=Object.freeze({id:'JS-internal-uuid',source:'jushuitan-settlement',orderNumber:'2026-11-00000001'});
const ordinary=Object.freeze({id:'2026-11-00000008',type:'销售订单'});
const orders=Object.freeze([supplement,ordinary]);

test('new order numbers come only from the server reservation and are cached per form',async()=>{
  const calls=[];const attempt={requestId:'stable-form-key'};
  const api=async(path,body)=>{calls.push({path,body});return {orderNumber:'N2611250001'}};
  assert.equal(await reserveOrderNumber(api,attempt),'N2611250001');
  assert.equal(await reserveOrderNumber(api,attempt),'N2611250001');
  assert.deepEqual(calls,[{path:'orders/number-reservations',body:{requestId:'stable-form-key'}}]);
  assert.deepEqual(attempt,{requestId:'stable-form-key',orderNumber:'N2611250001'});
});

test('an unconfirmed reservation retries the original key and accepts server dates and expanded sequences',async()=>{
  for(const failure of [Object.assign(Error('conflict'),{status:409}),Error('response lost')]){
    const calls=[];const attempt={requestId:'unchanged-after-failure'};
    const api=async(path,body)=>{calls.push({path,body});if(calls.length===1)throw failure;return {orderNumber:'N27120110000'}};
    await assert.rejects(reserveOrderNumber(api,attempt),/请在当前表单重试/);
    assert.deepEqual(attempt,{requestId:'unchanged-after-failure'});
    assert.equal(await reserveOrderNumber(api,attempt),'N27120110000');
    assert.deepEqual(calls[0],calls[1]);
  }
});

test('invalid reservation responses never become a local fallback number',async()=>{
  for(const response of [undefined,{}, {orderNumber:'2026-11-00014585'},{orderNumber:'S261125151751967'},
    {orderNumber:'N261125001'},{orderNumber:'N2611250001 '},{orderNumber:2611250001},
    {orderNumber:'N2613320001'},{orderNumber:'N2602290001'},{orderNumber:'N2611250000'},
    {orderNumber:'N261125'+'1'.repeat(185)}]){
    const attempt={requestId:'retry-invalid-response'};
    await assert.rejects(reserveOrderNumber(async()=>response,attempt),/服务器返回的订单编号格式无效/);
    assert.equal(attempt.orderNumber,undefined);
    assert.equal(attempt.requestId,'retry-invalid-response');
  }
});

test('independent forms use independent reservation requests while existing numbers remain display-only',async()=>{
  const first={requestId:'first-form'},second={requestId:'second-form'};const calls=[];
  const api=async(path,body)=>{calls.push(body.requestId);return {orderNumber:body.requestId==='first-form'?'N2611250001':'N2611250002'}};
  assert.equal(await reserveOrderNumber(api,first),'N2611250001');
  assert.equal(await reserveOrderNumber(api,second),'N2611250002');
  assert.deepEqual(calls,['first-form','second-form']);
  for(const id of ['N2611250001','S261125151751967','2026-11-00014584']){
    assert.equal(orderNumber({id}),id);
    assert.equal(orderNumberForId(id,[{id}]),id);
  }
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
