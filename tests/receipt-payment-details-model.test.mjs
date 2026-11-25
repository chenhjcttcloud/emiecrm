import test from 'node:test';
import assert from 'node:assert/strict';
import {allocateReceiptPaymentDetails,canonicalReceiptPaymentDetails,receiptPaymentDetailsForDisplay,
  receiptPaymentDetailTotals,receiptToday} from '../src/receiptPaymentDetailsModel.js';
import {buildDirectReceiptChange,buildReceiptApprovalRequest} from '../src/receiptApprovalModel.js';

const rows=()=>[{lineNo:90,method:'银行转账',amount:'20.01',receiptDate:'2026-11-24'},
  {lineNo:1,method:'微信',amount:'9.99',receiptDate:'2026-11-25'}];
const selected=()=>[{id:'o1',customerId:'c1',type:'销售订单',status:'已批准',total:50,paid:0},
  {id:'o2',customerId:'c1',type:'销售订单',status:'已批准',total:50,paid:0}];
const input=()=>({requestId:'fixed',db:{revision:1,customers:[{id:'c1',name:'客户',balance:10}],orders:selected()},
  customerId:'c1',selected:selected(),payments:{o1:'25',o2:'5'},paymentDetails:rows(),
  methods:['银行转账','微信'],config:{enabled:true,canSubmit:true},attachments:[{name:'本笔.png',url:'/api/assets/test'}]});

test('receipt dates default to the Shanghai calendar day regardless of the browser timezone',()=>{
  assert.equal(receiptToday(new Date('2026-11-24T15:59:59Z')),'2026-11-24');
  assert.equal(receiptToday(new Date('2026-11-24T16:00:00Z')),'2026-11-25');
});
test('multiple details keep cents and dates, canonicalize row positions and summarize mixed methods',()=>{
  const source=rows(),before=structuredClone(source),result=canonicalReceiptPaymentDetails(source);
  assert.deepEqual(source,before);assert.equal(result.amount,30);assert.equal(result.totalCents,3000);
  assert.equal(result.method,'组合收款');assert.equal(result.receiptDate,'2026-11-25');
  assert.deepEqual(result.paymentDetails.map(row=>row.lineNo),[1,2]);
  assert.equal(canonicalReceiptPaymentDetails(rows().map(row=>({...row,method:'银行转账'}))).method,'银行转账');
});
test('invalid and excess payment detail rows cannot be submitted',()=>{
  for(const source of [[],Array(51).fill(rows()[0]),[{...rows()[0],method:''}],
    [{...rows()[0],method:'a'.repeat(51)}],[{...rows()[0],method:'银行\n转账'}],[{...rows()[0],method:'组合收款'}],
    [{...rows()[0],amount:'0'}],[{...rows()[0],amount:'0.001'}],[{...rows()[0],receiptDate:'2026-02-30'}],
    [{...rows()[0],amount:'999999999999.99'},{...rows()[1],amount:'0.01'}]])
    assert.throws(()=>canonicalReceiptPaymentDetails(source));
  assert.equal(canonicalReceiptPaymentDetails(Array.from({length:50},()=>({...rows()[0],amount:'0.01'}))).amount,0.5);
  assert.equal(receiptPaymentDetailTotals([{amount:'0.001',method:'微信'}]).valid,false);
});
test('FIFO preview splits each detail across the selected allocation order without mutating either',()=>{
  const details=rows(),allocations=[{orderId:'o2',amount:25},{orderId:'o1',amount:5}];
  const before=structuredClone({details,allocations});
  assert.deepEqual(allocateReceiptPaymentDetails(details,allocations),[
    {lineNo:1,orderId:'o2',method:'银行转账',amount:20.01,receiptDate:'2026-11-24'},
    {lineNo:2,orderId:'o2',method:'微信',amount:4.99,receiptDate:'2026-11-25'},
    {lineNo:2,orderId:'o1',method:'微信',amount:5,receiptDate:'2026-11-25'},
  ]);
  assert.deepEqual({details,allocations},before);
  assert.throws(()=>allocateReceiptPaymentDetails(details,[{orderId:'o1',amount:29.99}]),/合计/);
});
test('new request carries all payment details, canonical totals, vouchers and internal allocation IDs only',()=>{
  const data=input(),before=structuredClone(data),request=buildReceiptApprovalRequest(data);
  assert.deepEqual(data,before);assert.equal(request.amount,30);assert.equal(request.method,'组合收款');
  assert.equal(request.receiptDate,'2026-11-25');assert.deepEqual(request.paymentDetails,canonicalReceiptPaymentDetails(rows()).paymentDetails);
  assert.deepEqual(request.allocations,[{orderId:'o1',amount:25},{orderId:'o2',amount:5}]);
  assert.deepEqual(request.attachments,data.attachments);assert.equal(Object.hasOwn(request,'paymentAllocations'),false);
  assert.throws(()=>buildReceiptApprovalRequest({...data,amount:29.99}),/明细合计/);
  assert.throws(()=>buildReceiptApprovalRequest({...data,payments:{o1:24,o2:5}}),/合计/);
  assert.throws(()=>buildReceiptApprovalRequest({...data,methods:['银行转账']}),/有效收款方式/);
});
test('mixed cash and write-off checks only combined write-off rows against balance and rejects ineligible orders',()=>{
  const data=input();data.paymentDetails=[rows()[0],{...rows()[1],method:'预收款核销'}];
  const request=buildReceiptApprovalRequest(data);assert.equal(request.method,'组合收款');
  assert.equal(receiptPaymentDetailTotals(request.paymentDetails).writeOffCents,999);
  const over={...data,paymentDetails:[{...rows()[0],amount:19.99},{...rows()[1],method:'预收款核销',amount:10.01}]};
  assert.throws(()=>buildReceiptApprovalRequest(over),/余额不足/);
  const both={...data,paymentDetails:rows().map(row=>({...row,method:'预收款核销'}))};
  assert.throws(()=>buildReceiptApprovalRequest(both),/余额不足/);
  for(const change of [{type:'预收款订单'},{source:'jushuitan-settlement'}])
    assert.throws(()=>buildReceiptApprovalRequest({...data,selected:[{...data.selected[0],...change},data.selected[1]]}),/不能使用/);
});
test('direct booking includes the same canonical detail and allocation contract without changing original orders',()=>{
  const data=input(),before=structuredClone(data);data.config={enabled:false,required:false};
  const direct=buildDirectReceiptChange(data);
  assert.equal(direct.verification.method,'组合收款');assert.equal(direct.verification.paymentDetails.length,2);
  assert.deepEqual(direct.verification.allocations,[{orderId:'o1',amount:25},{orderId:'o2',amount:5}]);
  assert.equal(direct.data.orders[0].paid,25);assert.equal(direct.data.orders[0].method,'组合收款');
  assert.deepEqual(data.db,before.db);
});
test('legacy frozen attempts project a single row without adding or changing original request fields',()=>{
  const legacy=Object.freeze({requestId:'old',amount:30,method:'停用的历史方式',receiptDate:'2026-11-23',receiptCategory:'opaque-old'});
  const before=JSON.stringify(legacy),display=receiptPaymentDetailsForDisplay(legacy);
  assert.deepEqual(display,[{lineNo:1,amount:'30',method:'停用的历史方式',receiptDate:'2026-11-23'}]);
  display[0].amount='20';assert.equal(JSON.stringify(legacy),before);assert.equal(Object.hasOwn(legacy,'paymentDetails'),false);
  const current={paymentDetails:rows()};assert.equal(receiptPaymentDetailsForDisplay(current).length,2);
});

test('legacy direct receipt without a date never exposes validation-only dates or a new payment plan',()=>{
  const data=input();delete data.paymentDetails;
  const changed=buildDirectReceiptChange({...data,amount:30,method:'银行转账',config:{enabled:false,required:false}});
  assert.equal(changed.verification.method,'银行转账');
  for(const key of ['paymentDetails','allocations','receiptDate','receivedOn'])assert.equal(Object.hasOwn(changed.verification,key),false);
  assert.equal(JSON.stringify(changed).includes('2000-01-01'),false);
});

test('legacy explicit dates keep the old settlement verification contract and malformed explicit arrays are rejected',()=>{
  const data=input();delete data.paymentDetails;
  const legacy={...data,amount:30,method:'银行转账',receiptDate:'2026-11-24',config:{enabled:false,required:false}};
  const ordinary=buildDirectReceiptChange(legacy);
  assert.equal(Object.hasOwn(ordinary.verification,'receivedOn'),false);
  const settlement=buildDirectReceiptChange({...legacy,selected:legacy.selected.map(order=>({...order,source:'jushuitan-settlement'}))});
  assert.equal(settlement.verification.receivedOn,'2026-11-24');
  for(const value of [ordinary,settlement]){
    assert.equal(Object.hasOwn(value.verification,'paymentDetails'),false);
    assert.equal(Object.hasOwn(value.verification,'allocations'),false);
  }
  for(const paymentDetails of [null,undefined,{},'bad']){
    assert.throws(()=>buildDirectReceiptChange({...legacy,paymentDetails}),/收款明细/);
    assert.throws(()=>buildReceiptApprovalRequest({...input(),paymentDetails}),/收款明细/);
  }
});
