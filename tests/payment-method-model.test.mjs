import test from 'node:test';
import assert from 'node:assert/strict';
import {enabledPaymentMethodNames,receiptMethodChoices} from '../src/paymentMethodModel.js';

test('payment choices use only enabled server configuration and follow its sort order',()=>{
 const rows=[{name:'自定义收款C',status:'启用',sortOrder:3},{name:'已停用方式',status:'停用',sortOrder:1},
  {name:'自定义收款A',status:'启用',sortOrder:1},{name:'自定义收款B',status:'启用',sortOrder:2},
  {name:'预收款核销',status:'启用',sortOrder:4}];
 const original=structuredClone(rows);
 assert.deepEqual(enabledPaymentMethodNames(rows),['自定义收款A','自定义收款B','自定义收款C']);
 assert.deepEqual(rows,original);assert.deepEqual(enabledPaymentMethodNames([]),[]);
 assert.throws(()=>enabledPaymentMethodNames(null),/配置格式无效/);
 assert.throws(()=>enabledPaymentMethodNames([{status:'启用',name:''}]),/名称无效/);
});

test('a frozen receipt keeps its disabled original method for display without offering it to new receipts',()=>{
 const methods=['当前启用方式'];
 assert.deepEqual(receiptMethodChoices(methods,'已停用原方式',true),['已停用原方式','当前启用方式']);
 assert.deepEqual(receiptMethodChoices(methods,'已停用原方式',false),['当前启用方式']);
 assert.deepEqual(methods,['当前启用方式']);
 assert.deepEqual(receiptMethodChoices([],'原申请方式',true),['原申请方式']);
});
