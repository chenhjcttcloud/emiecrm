import {test} from 'node:test';
import assert from 'node:assert/strict';
import {captureBusinessForm,replaceBusinessFormRecord} from '../src/businessFormSnapshot.js';

const data=()=>({revision:7,customers:[{id:'c1',name:'旧客户',openingBalance:270,balance:270,
 legacySource:{system:'legacy-feishu-crm',id:'001',fields:{客户编号:'0001',预收款金额:'270'}}}],
 products:[{id:'p1',enabled:true}],orders:[{id:'o1',customerId:'c1',paid:0,total:100,items:[{product:'p1',qty:1,price:100}]}]});

test('表单保持打开时的业务版本、选项及旧来源，不被后台读取或修改提升',()=>{
 const live=data(),base=captureBusinessForm(live);
 live.revision=8;live.customers[0].name='其他会话修改';live.customers[0].legacySource.fields.客户编号='changed';
 live.products[0].enabled=false;live.orders[0].items[0].price=200;
 assert.equal(base.revision,7);assert.equal(base.customers[0].name,'旧客户');
 assert.equal(base.customers[0].legacySource.fields.客户编号,'0001');assert.equal(base.products[0].enabled,true);
 assert.equal(base.orders[0].items[0].price,100);
 const draft={...base.customers[0],contact:'编辑的联系人'};
 const submitted=replaceBusinessFormRecord(base,'customers',draft);
 assert.equal(submitted.revision,7,'服务端仍可按原版本拒绝旧表单');
 assert.equal(submitted.customers[0].openingBalance,270);assert.deepEqual(submitted.customers[0].legacySource,base.customers[0].legacySource);
 assert.equal(base.customers[0].contact,undefined);assert.equal(live.customers[0].name,'其他会话修改');
});

test('新建记录仅合入已固定快照，保留原订单、商品及当前期初',()=>{
 const base=captureBusinessForm(data());
 const next=replaceBusinessFormRecord(base,'orders',{id:'o2',customerId:'c1',paid:0,total:200});
 assert.deepEqual(next.orders.map(r=>r.id),['o2','o1']);assert.equal(base.orders.length,1);
 assert.equal(next.revision,7);assert.deepEqual(next.products,base.products);assert.deepEqual(next.customers,base.customers);
});
