import {test} from 'node:test';
import assert from 'node:assert/strict';
import {salesQuantity,salesItemsCsv,salesTargetStatus,salesOrderFinance,salesTargetDepartmentStatus,salesTargetDepartmentLabel,matchesSalesTargetFilters,normalizeSalesTargetScopeSettings} from '../src/salesModel.js';

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
 assert.deepEqual(salesOrderFinance({total:12200,paid:0},{total:12200,paid:12200},true),{status:'待回款',tone:'warning',paid:0,outstanding:12200});
 assert.deepEqual(salesOrderFinance({total:12200,paid:200},{total:12200,paid:12200},true),{status:'部分回款',tone:'warning',paid:200,outstanding:12000});
 assert.equal(salesOrderFinance({total:12200},{total:12200,paid:12200},true).paid,0);
 assert.equal(salesOrderFinance({total:12200,paid:0},{total:12200,paid:12200},false).status,'已回款');
 assert.equal(salesOrderFinance({total:0,paid:0}).status,'无需回款');
 assert.equal(salesOrderFinance({total:'unknown',paid:0}).status,'待核对');
});

test('target department filtering uses IDs and combines with owner search',()=>{
 const first={owner:'Alice',departmentId:'d1',department:'销售部',departmentStatus:'assigned'};
 const sameName={owner:'Alina',departmentId:'d2',department:'销售部',departmentStatus:'assigned'};
 const reservedId={owner:'Alice',departmentId:'status:unassigned',department:'特殊部门',departmentStatus:'assigned'};
 assert.equal(matchesSalesTargetFilters(first,{query:' ALI ',department:'department:d1'}),true);
 assert.equal(matchesSalesTargetFilters(sameName,{query:'ali',department:'department:d1'}),false);
 assert.equal(matchesSalesTargetFilters(first,{query:'Bob',department:'department:d1'}),false);
 assert.equal(matchesSalesTargetFilters(reservedId,{department:'department:status:unassigned'}),true);
 assert.equal(matchesSalesTargetFilters(reservedId,{department:'status:unassigned'}),false);
 assert.equal(matchesSalesTargetFilters(first,{department:'invalid'}),false);
});

test('unresolved departments remain separate and old target rows display unassigned',()=>{
 assert.equal(salesTargetDepartmentStatus({owner:'旧签单人'}),'unassigned');
 assert.equal(salesTargetDepartmentLabel({owner:'旧签单人'}),'未分配部门');
 assert.equal(salesTargetDepartmentLabel({departmentId:'d1',department:'销售一部',departmentStatus:'assigned'}),'销售一部');
 for(const status of ['unassigned','unmatched','ambiguous']){
  const row={owner:'旧签单人',departmentStatus:status};
  assert.equal(matchesSalesTargetFilters(row,{department:'status:'+status}),true);
  assert.equal(matchesSalesTargetFilters(row,{department:'department:d1'}),false);
  assert.equal(matchesSalesTargetFilters(row,{department:'status:assigned'}),false);
 }
 assert.equal(salesTargetDepartmentLabel({departmentStatus:'ambiguous'}),'人员重名待确认');
});

test('department participation settings preserve empty selection and reject unsafe version data',()=>{
 const empty={mode:'selected',departmentIds:[],revision:0,departments:[{id:'d1',name:'销售部'}]};
 assert.deepEqual(normalizeSalesTargetScopeSettings(empty),{...empty,missingDepartmentIds:[]});
 const historical={...empty,departmentIds:['gone']};
 assert.deepEqual(normalizeSalesTargetScopeSettings(historical).missingDepartmentIds,['gone']);
 assert.deepEqual(normalizeSalesTargetScopeSettings(historical).departmentIds,['gone']);
 for(const change of [{mode:'invalid'},{revision:-1},{revision:1.5},{departmentIds:['d1','d1']},{departments:[{id:'d1',name:'销售部'},{id:'d1',name:'同名部门'}]}])assert.throws(()=>normalizeSalesTargetScopeSettings({...empty,...change}));
});
