import test from 'node:test';
import assert from 'node:assert/strict';
import {buildDirectReceiptChange,buildReceiptApprovalRequest,receiptApprovalActive,receiptApprovalCompletionKey,receiptApprovalProblem,
  reservedReceiptOrderIds,receiptPaymentMethods,validReceiptDate} from '../src/receiptApprovalModel.js';

const orders=[{id:'o1',customerId:'c1',type:'销售订单',status:'已批准',total:100,paid:10},
  {id:'o2',customerId:'c1',type:'海外提货单',status:'已批准',total:50,paid:0}];
const config={enabled:true,canSubmit:true,receiptCategories:['销售收入','服务收入']};
const input=()=>({requestId:'stable-request',db:{revision:5,customers:[{id:'c1',balance:100}]},customerId:'c1',
  selected:structuredClone(orders),payments:{o1:'20.01',o2:'9.99'},amount:'30.00',method:'银行转账',receiptDate:'2026-11-23',
  receiptCategory:'销售收入',methods:['银行转账','微信'],config});
test('receipt allocations conserve cents and never mutate orders before approval',()=>{
  const data=input(),before=structuredClone(data);const request=buildReceiptApprovalRequest(data);
  assert.deepEqual(data,before);assert.deepEqual(request.allocations,[{orderId:'o1',amount:20.01},{orderId:'o2',amount:9.99}]);
  assert.equal(request.amount,30);assert.equal(request.revision,5);assert.equal(request.requestId,'stable-request');
});
test('receipt approval prevents incomplete and inconsistent financial input',()=>{
  for(const change of [{amount:'30.001'},{amount:'29.99'},{payments:{o1:'100',o2:'1'}},{receiptDate:'2026-02-30'},
    {receiptCategory:'其他类别'},{customerId:'other'},{selected:[{...orders[0],status:'审批中'}]},
    {selected:[{...orders[0],deletedAt:'now'}]},{method:'已停用'}])assert.throws(()=>buildReceiptApprovalRequest({...input(),...change}));
});
test('pending and unbooked approved receipts reserve their orders',()=>{
  const rows=[{id:'a',status:'PENDING',allocations:[{orderId:'o1'}]},{id:'b',status:'APPROVED',booked:false,allocations:[{orderId:'o2'}]},
    {id:'c',status:'APPROVED',booked:true,allocations:[{orderId:'done'}]},{id:'d',status:'REJECTED',allocations:[{orderId:'rejected'}]}];
  assert.deepEqual([...reservedReceiptOrderIds(rows)],['o1','o2']);assert.equal(receiptApprovalActive(rows[1]),true);
  assert.throws(()=>buildReceiptApprovalRequest({...input(),records:rows}),/未完成/);
  assert.notEqual(receiptApprovalCompletionKey(rows[1]),receiptApprovalCompletionKey({...rows[1],booked:true}));
});
test('prepayment write-off remains available only for eligible sales and sufficient balance',()=>{
  assert.ok(receiptPaymentMethods(['银行转账'],orders).includes('预收款核销'));
  assert.equal(buildReceiptApprovalRequest({...input(),method:'预收款核销'}).method,'预收款核销');
  assert.throws(()=>buildReceiptApprovalRequest({...input(),method:'预收款核销',db:{revision:5,customers:[{id:'c1',balance:20}]}}),/余额不足/);
  for(const order of [{...orders[0],type:'预收款订单'},{...orders[0],source:'jushuitan-settlement'}]){
    assert.ok(!receiptPaymentMethods(['银行转账'],[order]).includes('预收款核销'));
    assert.throws(()=>buildReceiptApprovalRequest({...input(),selected:[order],method:'预收款核销'}),/不能使用/);
  }
});
test('receipt submission never falls back to direct booking when configuration is unavailable',()=>{
  assert.match(receiptApprovalProblem(null),/正在读取/);assert.match(receiptApprovalProblem(config,'network'),/无法读取/);
  assert.match(receiptApprovalProblem({...config,enabled:false}),/尚未配置/);
  assert.match(receiptApprovalProblem({...config,canSubmit:false}),/飞书账号/);
  assert.match(receiptApprovalProblem(config,'',false),/权限/);assert.equal(receiptApprovalProblem(config),'');
  assert.equal(validReceiptDate('2024-02-29'),true);assert.equal(validReceiptDate('2026-02-29'),false);
});

test('explicit unconfigured mode retains verified direct booking and settlement dates',()=>{
 const data={...input(),db:{...input().db,orders:structuredClone(orders)},config:{enabled:false,required:false}};
 assert.equal(receiptApprovalProblem(data.config),'');
 const changed=buildDirectReceiptChange(data);
 assert.equal(changed.data.orders[0].paid,30.01);assert.equal(changed.data.orders[1].paid,9.99);
 assert.equal(data.db.orders[0].paid,10);assert.equal(changed.verification.amount,30);
 assert.throws(()=>buildDirectReceiptChange({...data,config:{enabled:true,required:true}}),/必须先通过/);
 assert.throws(()=>buildDirectReceiptChange({...data,selected:[{...orders[0],source:'jushuitan-settlement'}],receiptDate:''}),/实际到账日期/);
});
