import {test} from 'node:test';
import assert from 'node:assert/strict';
import {salesQuantity,salesItemsCsv,salesTargetStatus,salesOrderFinance} from '../src/salesModel.js';

test('sales quantities preserve six decimal places and large values',()=>{
 assert.equal(salesQuantity('1234567890123456.000001'),'1,234,567,890,123,456.000001');
 assert.equal(salesQuantity('1.200000'),'1.2');
 assert.equal(salesQuantity(null),'—');
 assert.equal(salesQuantity('1.1234567'),'—');
});
test('sales CSV protects formulas and does not turn unknown historical cost into zero',()=>{
 const csv=salesItemsCsv([{product:'=SUM(1,2)',qty:'0.000001',price:'0.01',amount:'0.00',unitCost:null,cost:null,profit:null}],true);
 assert.ok(csv.startsWith('\uFEFF'));
 assert.ok(csv.includes("'=SUM(1,2)"));
 assert.ok(csv.includes('0.000001'));
 assert.ok(csv.endsWith(',"","",""')&&csv.includes('历史成本单价'));
 assert.equal(salesTargetStatus(null),'未设置');
 assert.equal(salesTargetStatus('0.00'),'目标为零');
});
test('order collection status uses the effective amount and exact cents',()=>{
 assert.deepEqual(salesOrderFinance({total:100.01,paid:50.02}),{status:'部分回款',tone:'warning',paid:50.02,outstanding:49.99});
 assert.equal(salesOrderFinance({total:100,paid:0},{total:60,paid:60}).status,'已回款');
 assert.equal(salesOrderFinance({total:0,paid:0}).status,'无需回款');
 assert.equal(salesOrderFinance({total:'unknown',paid:0}).status,'待核对');
});
