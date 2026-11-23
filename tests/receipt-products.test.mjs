import {test} from 'node:test';
import assert from 'node:assert/strict';
import {canReceiveOrder,itemsFromOrder,receiptProducts,productSummary,productDetailsText,productSourceLabel} from '../src/receiptProductModel.js';

const order={id:'o1',customerId:'c1',status:'已批准',total:'10.00',paid:'3.00',items:[{product:'p1',qty:'0.000001',price:'10.00'}]};
const products=[{id:'p1',name:'当前产品名称',jstSku:'SKU-A',model:'新型号'}];

test('receipt eligibility excludes canceled and deleted orders without changing the payment rules',()=>{
 assert.equal(canReceiveOrder(order),true);
 for(const changes of [{status:'已取消'},{deletedAt:'2026-11-23T00:00:00Z'},{deletedAt:''},{status:'审批中'},{customerId:''},{paid:'10.00'},{paid:'11.00'},{paid:'bad'},{total:'bad'}])assert.equal(canReceiveOrder({...order,...changes}),false);
 assert.equal(canReceiveOrder({...order,deletedAt:null}),true);
});

test('a receipt snapshot keeps historical product values when the current catalog changes',()=>{
 const items=[{product:'p1',name:'历史商品',sku:'OLD-SKU',model:'旧型号',qty:'2',price:'4.00'}];
 assert.deepEqual(receiptProducts({items,itemsSource:'snapshot'},order,products),{items,itemsSource:'snapshot'});
 assert.deepEqual(receiptProducts({items:[],itemsSource:'snapshot'},order,products),{items:[],itemsSource:'snapshot'});
 assert.deepEqual(receiptProducts({items},order,products),{items,itemsSource:'snapshot'});
});

test('historical receipts use explicit current-order fallback and unknown stays unknown',()=>{
 const fallback=receiptProducts({},order,products);
 assert.equal(fallback.itemsSource,'order');
 assert.equal(fallback.items[0].name,'当前产品名称');
 assert.equal(fallback.items[0].qty,'0.000001');
 assert.deepEqual(receiptProducts({},null,products),{items:[],itemsSource:'unknown'});
 assert.deepEqual(receiptProducts({items:null},order,products),{items:[],itemsSource:'unknown'});
 assert.deepEqual(receiptProducts({items:[],itemsSource:'unknown'},order,products),{items:[],itemsSource:'unknown'});
 assert.equal(productSourceLabel('order'),'当前关联订单');
 assert.equal(productSourceLabel('snapshot'),'收款时商品快照');
});

test('product detail exports retain SKU, quantity precision and exact rounded line amounts',()=>{
 const items=itemsFromOrder(order,products);
 const text=productDetailsText(items);
 assert.ok(text.includes('当前产品名称')&&text.includes('SKU：SKU-A')&&text.includes('规格：新型号'));
 assert.ok(text.includes('数量：0.000001')&&text.includes('单价：¥10.00')&&text.includes('小计：¥0.00'));
 assert.equal(productDetailsText([{name:'缺失金额',qty:1}]),'缺失金额；数量：1；单价：—；小计：—');
 assert.equal(productSummary([...items,...items,...items]),'当前产品名称 × 0.000001、当前产品名称 × 0.000001 等 3 款');
 assert.equal(productSummary([]),'暂无产品明细');
});
