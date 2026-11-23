import {test} from 'node:test';
import assert from 'node:assert/strict';
import {canonicalWorkspacePage,defaultWorkspacePage,resolveWorkspacePage} from '../src/workspaceNavigation.js';

test('dashboard permissions grant an independent module without business access',()=>{
  assert.equal(defaultWorkspacePage(['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('orders',['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('dashboard-sales',['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('dashboard-targets',['dashboard.read']),'dashboard-sales');
});
test('target management and viewing are separate permissions',()=>{
  assert.equal(defaultWorkspacePage(['dashboard.targets.manage']),'targets-company');
  assert.equal(resolveWorkspacePage('dashboard-sales',['dashboard.targets.manage']),'targets-company');
  assert.equal(resolveWorkspacePage('dashboard-targets',['business.read']),'sales-orders');
});
test('existing business entry and browser routes remain available',()=>{
  const permissions=['business.read','dashboard.read','dashboard.targets.manage','system.manage'];
  assert.equal(defaultWorkspacePage(permissions),'sales-orders');
  for(const page of ['sales-orders','order-items','targets-personnel','advance','receipt-orders','customers','products','sys-roles','dashboard-sales','targets-company'])assert.equal(resolveWorkspacePage(page,permissions),page);
  assert.equal(resolveWorkspacePage('unknown',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('toString',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('orders',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('receipts',permissions),'receipt-orders');
  assert.equal(resolveWorkspacePage('sales-receipts',permissions),'receipt-orders');
  assert.equal(resolveWorkspacePage('receipt-orders',['business.read']),'receipt-orders');
  assert.equal(resolveWorkspacePage('receipt-orders',['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('receipt-orders',[]),'');
  for(const page of ['sales-orders','sales-items','order-items','sales-receipts','sales-targets'])assert.equal(resolveWorkspacePage(page,['dashboard.read']),'dashboard-sales');
  assert.equal(defaultWorkspacePage([]),'');
});

test('order product detail view preserves its legacy link and business permission boundary',()=>{
  assert.equal(canonicalWorkspacePage('sales-items'),'order-items');
  assert.equal(canonicalWorkspacePage('order-items'),'order-items');
  assert.equal(canonicalWorkspacePage('orders'),'sales-orders');
  for(const page of ['sales-items','order-items']){
    assert.equal(resolveWorkspacePage(page,['business.read']),'order-items');
    assert.equal(resolveWorkspacePage(page,['business.read','dashboard.read']),'order-items');
    assert.equal(resolveWorkspacePage(page,['dashboard.read']),'dashboard-sales');
    assert.equal(resolveWorkspacePage(page,['dashboard.targets.manage']),'targets-company');
    assert.equal(resolveWorkspacePage(page,['system.manage']),'sys-users');
    assert.equal(resolveWorkspacePage(page,[]),'');
  }
  assert.equal(defaultWorkspacePage(['business.read']),'sales-orders');
});

test('unified target views preserve legacy links and separate company and personnel permissions',()=>{
  const combined=['business.read','dashboard.targets.manage'];
  assert.equal(canonicalWorkspacePage('dashboard-targets'),'targets-company');
  assert.equal(canonicalWorkspacePage('sales-targets'),'targets-personnel');
  for(const page of ['dashboard-targets','targets-company']){
    assert.equal(resolveWorkspacePage(page,combined),'targets-company');
    assert.equal(resolveWorkspacePage(page,['dashboard.targets.manage']),'targets-company');
    assert.equal(resolveWorkspacePage(page,['business.read']),'sales-orders');
    assert.equal(resolveWorkspacePage(page,['dashboard.read']),'dashboard-sales');
    assert.equal(resolveWorkspacePage(page,[]),'');
  }
  for(const page of ['sales-targets','targets-personnel']){
    assert.equal(resolveWorkspacePage(page,combined),'targets-personnel');
    assert.equal(resolveWorkspacePage(page,['business.read']),'targets-personnel');
    assert.equal(resolveWorkspacePage(page,['dashboard.targets.manage']),'targets-company');
    assert.equal(resolveWorkspacePage(page,['system.manage']),'sys-users');
    assert.equal(resolveWorkspacePage(page,[]),'');
  }
});
