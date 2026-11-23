import {test} from 'node:test';
import assert from 'node:assert/strict';
import {approvalDetails,approvalDraftValues,plannedOrderWarehouse,shippingWarehouseId,warehouseIdentifier} from '../src/feishuApprovalModel.js';

const order={id:'warehouse-order',type:'销售订单',approvalDraft:{warehouseId:'15136229',warehouseName:'国内主仓',recipientName:'旧收货人'}};

test('warehouse selection survives absent form controls but explicit clearing removes the saved name',()=>{
 assert.deepEqual(approvalDraftValues({},order,new Map()),{warehouseId:'15136229',warehouseName:'国内主仓'});
 assert.deepEqual(approvalDetails({},order,new Map()),{warehouseId:'15136229',warehouseName:'国内主仓'});
 assert.deepEqual(approvalDraftValues({},order,new Map([['warehouseId',''],['warehouseName','旧名字']])),{warehouseId:'',warehouseName:''});
 assert.deepEqual(approvalDetails({},order,new Map([['warehouseId','']])),{});
 assert.deepEqual(approvalDraftValues({},order,new Map([['warehouseId','42']])),{warehouseId:'42',warehouseName:''});
 assert.deepEqual(approvalDraftValues({}, {...order,approvalDraft:{}},new Map()),{});
});

test('selected warehouse is optional, validates exact positive IDs and does not change recipient or discount fields',()=>{
 const fields=new Map([['warehouseId',' 15136230 '],['warehouseName',' 另一个仓库 '],['recipientName',' 张三 '],['recipientPhone','13800000000'],['recipientAddress','深圳市地址'],['approvalDiscount-0','0']]);
 const sales={...order,items:[{product:'p',qty:1,price:10}]};
 assert.deepEqual(approvalDetails({salesRecipientRequired:true,salesDiscountRequired:true},sales,fields),{
  warehouseId:'15136230',warehouseName:'另一个仓库',recipientName:'张三',recipientPhone:'13800000000',recipientAddress:'深圳市地址',discounts:['0'],
 });
 assert.deepEqual(approvalDetails({}, {...order,approvalDraft:{}},new Map()),{});
 assert.throws(()=>approvalDetails({},order,new Map([['warehouseId','0']])),/有效的发货仓库/);
 assert.throws(()=>approvalDetails({},order,new Map([['warehouseId','1'],['warehouseName','仓'.repeat(192)]])),/最多191字/);
});

test('warehouse identifiers preserve long string precision and reject invalid numeric forms',()=>{
 for(const value of ['1','15136229','9007199254740993','9223372036854775807'])assert.equal(warehouseIdentifier(value),value);
 assert.equal(warehouseIdentifier(15136229),'15136229');
 for(const value of [0,-1,1.5,Number.MAX_SAFE_INTEGER+1,{},null,undefined,'0','01','-2','1.2','1e3','9223372036854775808','1'.repeat(20)])assert.equal(warehouseIdentifier(value),'');
});

test('prepayments and settlement supplements never reuse an ordinary order warehouse',()=>{
 for(const other of [{...order,type:'预收款订单'},{...order,source:'jushuitan-settlement'}]){
  assert.deepEqual(approvalDraftValues({},other,new Map([['warehouseId','15136230'],['warehouseName','无关仓库']])),{});
  assert.deepEqual(approvalDetails({},other,new Map([['warehouseId','15136230']])),{});
  assert.equal(plannedOrderWarehouse(other),null);
  assert.equal(shippingWarehouseId(other), '');
 }
 assert.deepEqual(plannedOrderWarehouse({...order,type:'海外提货单'}),{id:'15136229',name:'国内主仓'});
});

test('manual shipment prefill respects its original request and never replaces it with the plan',()=>{
 assert.equal(shippingWarehouseId(order),'15136229');
 assert.equal(shippingWarehouseId(order,{request:{wms_co_id:42}}),42);
 assert.equal(shippingWarehouseId(order,{request:{wms_co_id:'42'}}),'42');
 for(const request of [{wms_co_id:0},{wms_co_id:null},{}])assert.equal(shippingWarehouseId(order,{request}),request.wms_co_id??'');
 assert.equal(plannedOrderWarehouse({...order,approvalDraft:{warehouseId:'0',warehouseName:'错误仓库'}}),null);
});
