import test from 'node:test';
import assert from 'node:assert/strict';
import {allocationPaymentRows,hasReceiptPaymentDetails,paymentLineLabel,receiptPaymentRows} from '../src/receiptPaymentDisplay.js';

const batch=()=>({method:'组合收款',amount:300,receiptDate:'2026-11-18',paymentDetails:[
  {lineNo:1,method:'银行转账',amount:150,receiptDate:'2026-11-17'},
  {lineNo:2,method:'自定义配置账户',amount:150,receiptDate:'2026-11-18'},
]});

test('payment rows show frozen channel and date metadata rather than the batch summary',()=>{
  const record=batch(),before=structuredClone(record);
  assert.equal(hasReceiptPaymentDetails(record),true);
  assert.deepEqual(receiptPaymentRows(record),record.paymentDetails);
  assert.deepEqual(record,before);
  assert.equal(paymentLineLabel(2),'第2笔');
});

test('each order displays only its saved portions while reusing the original payment metadata',()=>{
  const record=batch();
  const first={orderId:'o1',amount:200,paymentDetails:[{lineNo:1,amount:150},{lineNo:2,amount:50}]};
  const second={orderId:'o2',amount:100,paymentDetails:[{lineNo:2,amount:100}]};
  const before=structuredClone({record,first,second});
  assert.deepEqual(allocationPaymentRows(record,first),[
    {lineNo:1,method:'银行转账',amount:150,receiptDate:'2026-11-17'},
    {lineNo:2,method:'自定义配置账户',amount:50,receiptDate:'2026-11-18'},
  ]);
  assert.deepEqual(allocationPaymentRows(record,second),[
    {lineNo:2,method:'自定义配置账户',amount:100,receiptDate:'2026-11-18'},
  ]);
  assert.deepEqual({record,first,second},before);
});

test('part fields cannot replace the frozen payment channel or date',()=>{
  const allocation={paymentDetails:[{lineNo:2,amount:50,method:'组合收款',receiptDate:'2099-12-31'}]};
  assert.deepEqual(allocationPaymentRows(batch(),allocation),[
    {lineNo:2,method:'自定义配置账户',amount:50,receiptDate:'2026-11-18'},
  ]);
});

test('missing or ambiguous references remain unknown instead of borrowing the summary channel or date',()=>{
  assert.deepEqual(allocationPaymentRows(batch(),{paymentDetails:[{lineNo:99,amount:5}]}),[
    {lineNo:99,method:'',amount:5,receiptDate:''},
  ]);
  const record=batch();record.paymentDetails.push({...record.paymentDetails[0],method:'重复编号账户'});
  assert.deepEqual(allocationPaymentRows(record,{paymentDetails:[{lineNo:1,amount:5}]}),[
    {lineNo:1,method:'',amount:5,receiptDate:''},
  ]);
});

test('legacy scalar receipts do not gain invented payment rows or line numbers',()=>{
  const legacy={method:'历史银行账户',amount:200,receiptDate:'2026-01-01'};
  assert.equal(hasReceiptPaymentDetails(legacy),false);
  assert.deepEqual(receiptPaymentRows(legacy),[]);
  assert.deepEqual(allocationPaymentRows(legacy,{amount:200}),[]);
  for(const value of [null,undefined,0,-1,'1',1.5])assert.equal(paymentLineLabel(value),'');
});
