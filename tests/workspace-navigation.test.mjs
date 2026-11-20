import {test} from 'node:test';
import assert from 'node:assert/strict';
import {defaultWorkspacePage,resolveWorkspacePage} from '../src/workspaceNavigation.js';

test('dashboard permissions grant an independent module without business access',()=>{
  assert.equal(defaultWorkspacePage(['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('orders',['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('dashboard-sales',['dashboard.read']),'dashboard-sales');
  assert.equal(resolveWorkspacePage('dashboard-targets',['dashboard.read']),'dashboard-sales');
});
test('target management and viewing are separate permissions',()=>{
  assert.equal(defaultWorkspacePage(['dashboard.targets.manage']),'dashboard-targets');
  assert.equal(resolveWorkspacePage('dashboard-sales',['dashboard.targets.manage']),'dashboard-targets');
  assert.equal(resolveWorkspacePage('dashboard-targets',['business.read']),'sales-orders');
});
test('existing business entry and browser routes remain available',()=>{
  const permissions=['business.read','dashboard.read','dashboard.targets.manage','system.manage'];
  assert.equal(defaultWorkspacePage(permissions),'sales-orders');
  for(const page of ['sales-orders','sales-items','sales-receipts','sales-targets','advance','customers','products','sys-roles','dashboard-sales','dashboard-targets'])assert.equal(resolveWorkspacePage(page,permissions),page);
  assert.equal(resolveWorkspacePage('unknown',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('toString',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('orders',permissions),'sales-orders');
  assert.equal(resolveWorkspacePage('receipts',permissions),'sales-receipts');
  for(const page of ['sales-orders','sales-items','sales-receipts','sales-targets'])assert.equal(resolveWorkspacePage(page,['dashboard.read']),'dashboard-sales');
  assert.equal(defaultWorkspacePage([]),'');
});
