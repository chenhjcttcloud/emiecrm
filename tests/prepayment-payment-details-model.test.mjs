import {test} from 'node:test';
import assert from 'node:assert/strict';
import {canonicalPrepaymentPaymentDetails, prepaymentDetailsForDraft, prepaymentPaymentDetailsForDisplay} from '../src/prepaymentPaymentDetailsModel.js';

const rows = () => [
  {lineNo:99, method:'银行转账', amount:'20.01', receiptDate:'2026-11-25'},
  {lineNo:99, method:'现金', amount:'9.99', receiptDate:'2026-11-24'},
];

test('prepayment rows share exact receipt money and date validation and derive one summary', () => {
  const input = rows(), before = structuredClone(input);
  const result = canonicalPrepaymentPaymentDetails(input);
  assert.deepEqual(result.paymentDetails, [
    {lineNo:1, method:'银行转账', amount:20.01, receiptDate:'2026-11-25'},
    {lineNo:2, method:'现金', amount:9.99, receiptDate:'2026-11-24'},
  ]);
  assert.equal(result.total,30); assert.equal(result.amount,30);
  assert.equal(result.totalCents,3000); assert.equal(result.writeOffCents,0);
  assert.equal(result.method,'组合收款'); assert.equal(result.receiptDate,'2026-11-25');
  assert.deepEqual(input,before);
  const same = canonicalPrepaymentPaymentDetails(input.map(row=>({...row,method:'银行转账'})));
  assert.equal(same.method,'银行转账');
});

test('prepayment rows reject write-offs and summary labels as funding methods', () => {
  for (const method of ['预收款核销','组合收款','',null,'x'.repeat(51),'现金\n银行'])
    assert.throws(()=>canonicalPrepaymentPaymentDetails([{...rows()[0],method}]),/方式|核销/);
});

test('prepayment rows reject empty or malformed arrays and nonpositive or inexact money', () => {
  for (const value of [undefined,null,{},'bad',[],Array.from({length:51},()=>rows()[0])])
    assert.throws(()=>canonicalPrepaymentPaymentDetails(value),/明细/);
  for (const amount of ['','0',0,'-0.01','0.001','1e2','NaN','1000000000000'])
    assert.throws(()=>canonicalPrepaymentPaymentDetails([{...rows()[0],amount}]),/金额/);
  assert.throws(()=>canonicalPrepaymentPaymentDetails([
    {...rows()[0],amount:'999999999999.99'}, {...rows()[1],amount:'0.01'},
  ]),/合计金额/);
  assert.equal(canonicalPrepaymentPaymentDetails([{...rows()[0],amount:'999999999999.99'}]).totalCents,99999999999999);
});

test('prepayment dates must be real calendar dates and latest is derived without reordering rows', () => {
  for (const receiptDate of ['',undefined,'2026-02-30','2026-2-01','2026-11-25T00:00:00Z','2026-11-31','2026-02-29'])
    assert.throws(()=>canonicalPrepaymentPaymentDetails([{...rows()[0],receiptDate}]),/日期/);
  const leap = canonicalPrepaymentPaymentDetails([{...rows()[0],receiptDate:'2028-02-29'}]);
  assert.equal(leap.receiptDate,'2028-02-29');
});

test('legacy scalar prepayments are projected once without changing frozen snapshots', () => {
  const legacy = Object.freeze({id:'legacy',total:123.45,method:'旧银行',
    approvalDraft:Object.freeze({receiptDate:'2026-11-20',receiptCategory:'历史类别'})});
  const before = JSON.stringify(legacy), display = prepaymentPaymentDetailsForDisplay(legacy);
  assert.deepEqual(display,[{lineNo:1,method:'旧银行',amount:'123.45',receiptDate:'2026-11-20'}]);
  display[0].amount='0.01'; display.push(rows()[0]);
  assert.equal(JSON.stringify(legacy),before);
  assert.equal(Object.hasOwn(legacy,'paymentDetails'),false);
  assert.deepEqual(prepaymentPaymentDetailsForDisplay(undefined),[]);
});

test('explicit rows stay independent in display and malformed explicit data never becomes a legacy scalar row', () => {
  const order = {total:999,method:'旧汇总',paymentDetails:rows()};
  const before = structuredClone(order), display = prepaymentPaymentDetailsForDisplay(order);
  assert.equal(display.length,2); assert.deepEqual(display.map(row=>row.lineNo),[1,2]);
  assert.equal(display[0].amount,'20.01'); display[0].method='改显示';
  assert.deepEqual(order,before);
  for(const paymentDetails of [null,undefined,{},'bad',[]])
    assert.deepEqual(prepaymentPaymentDetailsForDisplay({...order,paymentDetails}),[]);
});

test('editable draft projection supplies a blank row and a default date without writing legacy data', () => {
  const today='2026-11-25';
  assert.deepEqual(prepaymentDetailsForDraft(undefined,today),[{lineNo:1,method:'',amount:'',receiptDate:today}]);
  const legacy={total:'20.00',method:'现金',approvalDraft:{}};
  assert.deepEqual(prepaymentDetailsForDraft(legacy,today),[{lineNo:1,method:'现金',amount:'20.00',receiptDate:today}]);
  assert.deepEqual(legacy,{total:'20.00',method:'现金',approvalDraft:{}});
  const withDate={...legacy,approvalDraft:{receiptDate:'2026-11-20'}};
  assert.equal(prepaymentDetailsForDraft(withDate,today)[0].receiptDate,'2026-11-20');
});

test('clearing an explicit draft row date stays blank so the next validation reports it', () => {
  const order={paymentDetails:[{lineNo:1,method:'现金',amount:'20.00',receiptDate:''}]};
  const before=structuredClone(order);
  const projected=prepaymentDetailsForDraft(order,'2026-11-25');
  assert.equal(projected[0].receiptDate,'');
  assert.throws(()=>canonicalPrepaymentPaymentDetails(projected),/日期/);
  assert.deepEqual(order,before);
  assert.deepEqual(prepaymentDetailsForDraft({paymentDetails:[]},'2026-11-25'),[]);
});
