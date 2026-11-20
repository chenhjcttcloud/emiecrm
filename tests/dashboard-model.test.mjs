import {test} from 'node:test';
import assert from 'node:assert/strict';
import {normalizeDashboardFilters,shiftDashboardMonth,targetProgress,validTargetAmount,dashboardMoney,dashboardDetailRows,currentDashboardMonth,sumDashboardMoney,summarizeSalesTargets} from '../src/dashboardModel.js';

test('month shortcuts cross years and stop at the supported bounds',()=>{
  assert.equal(shiftDashboardMonth('2026-01',-1),'2025-12');
  assert.equal(shiftDashboardMonth('2026-12',1),'2027-01');
  assert.equal(shiftDashboardMonth('2000-01',-1),'2000-01');
  assert.equal(shiftDashboardMonth('9999-12',1),'9999-12');
  assert.equal(shiftDashboardMonth('invalid',1),'invalid');
});

test('stored filters are validated and do not accept arbitrary month formats',()=>{
  assert.deepEqual(normalizeDashboardFilters(null),{month:currentDashboardMonth(),owner:'',customerId:''});
  for(const month of ['2026-13','2026-1','1999-12','2026-11-01',42])assert.equal(normalizeDashboardFilters({month}).month,currentDashboardMonth());
  assert.deepEqual(normalizeDashboardFilters({month:'2026-11',owner:'小王',customerId:'c1'}),{month:'2026-11',owner:'小王',customerId:'c1'});
  assert.equal(normalizeDashboardFilters({owner:'x'.repeat(500)}).owner.length,191);
});

test('target distinguishes missing, explicit zero, filtered and exceeded goals',()=>{
  assert.equal(targetProgress(100,null).state,'unset');
  assert.equal(targetProgress(100,'0.00').state,'zero');
  assert.equal(targetProgress(100,200,false).state,'filtered');
  assert.deepEqual(targetProgress(300,'200.00'),{state:'active',percent:150,width:100});
  assert.deepEqual(targetProgress(50,200),{state:'active',percent:25,width:25});
  assert.equal(targetProgress(100,'invalid').state,'unset');
});

test('money formatting preserves unknown data and signed refunds',()=>{
  assert.equal(dashboardMoney(null),'—');
  assert.equal(dashboardMoney(undefined),'—');
  assert.equal(dashboardMoney('NaN'),'—');
  assert.equal(dashboardMoney(0),'¥ 0.00');
  assert.equal(dashboardMoney('-10.01'),'¥ -10.01');
  assert.equal(dashboardMoney('1234567.89'),'¥ 1,234,567.89');
  assert.equal(dashboardMoney('1234567890123456.78'),'¥ 1,234,567,890,123,456.78');
  assert.equal(sumDashboardMoney(['0.10','0.20','-0.01']),'0.29');
  assert.equal(sumDashboardMoney(['1234567890123456.78','0.01']),'1234567890123456.79');
  assert.equal(sumDashboardMoney([null,'1.00']),null);
  for(const value of ['0','0.01','999999999999.99'])assert.equal(validTargetAmount(value),true);
  for(const value of ['','-1','1.001','1e3','1000000000000',null])assert.equal(validTargetAmount(value),false);
});

test('card and date details remain within the same returned order cohort',()=>{
  const paid={id:'paid',date:'2026-11-01',settled:true,outstanding:'0.00'};
  const partial={id:'partial',date:'2026-11-02',settled:false,outstanding:'60.00'};
  const zero={id:'zero',date:'2026-11-02',settled:false,outstanding:'0.00'};
  const pre={id:'pre',amount:'100'};
  const data={orders:[paid,partial,zero],advanceReceipts:[pre]};
  assert.deepEqual(dashboardDetailRows(data,{kind:'settled'}),[paid]);
  assert.deepEqual(dashboardDetailRows(data,{kind:'pending'}),[partial]);
  assert.deepEqual(dashboardDetailRows(data,{kind:'outstanding'}),[partial]);
  assert.deepEqual(dashboardDetailRows(data,{kind:'day',date:'2026-11-02'}),[partial,zero]);
  assert.deepEqual(dashboardDetailRows(data,{kind:'advance'}),[pre]);
  assert.deepEqual(dashboardDetailRows(data,{kind:'amount'}),data.orders);
  assert.deepEqual(dashboardDetailRows(null,{kind:'amount'}),[]);
});

test('personnel target totals preserve cents and distinguish unset from explicit zero',()=>{
  assert.deepEqual(summarizeSalesTargets([{owner:'小王',amount:null}]),{amount:null,personCount:0});
  assert.deepEqual(summarizeSalesTargets([{owner:'小王',amount:'0.00'},{owner:'小李',amount:null}]),{amount:'0.00',personCount:1});
  const rows=[{owner:'小王',amount:'999999999999.99'},{owner:'小李',amount:'0.02'},{owner:'小张',amount:null},{owner:' ',amount:'100.00'}];
  assert.deepEqual(summarizeSalesTargets(rows),{amount:'1000000000000.01',personCount:2});
  assert.deepEqual(summarizeSalesTargets([{owner:'小王',amount:'1.001'}]),{amount:null,personCount:1});
  assert.deepEqual(summarizeSalesTargets(null),{amount:null,personCount:0});
});
