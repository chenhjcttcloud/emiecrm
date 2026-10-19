import {test} from 'node:test';
import assert from 'node:assert/strict';
import {emptyOrderFilters,matchesOrderFilters,orderDate} from '../src/orderFilterLogic.js';
const orders=[{id:1,customer:'甲',owner:'张三',status:'已批准',updated:'2026-10-13 23:59:59'},{id:2,customer:'甲',owner:'李四',status:'审批中',updated:'2026-10-14 00:00:00'},{id:3,customer:'乙',owner:'张三',status:'草稿',updated:'2026-10-15 12:00:00'}];
const filter=values=>orders.filter(r=>matchesOrderFilters(r,{...emptyOrderFilters(),...values})).map(r=>r.id);
test('按对应字段筛选，多个条件取交集',()=>{assert.deepEqual(filter({customer:'甲'}),[1,2]);assert.deepEqual(filter({status:'草稿'}),[3]);assert.deepEqual(filter({owner:'张三'}),[1,3]);assert.deepEqual(filter({customer:'甲',status:'已批准',owner:'张三'}),[1]);assert.deepEqual(filter({customer:'甲',status:'草稿'}),[]);assert.deepEqual(filter({customer:'张三'}),[])});
test('日期包含边界，支持单边区间和北京时间转换',()=>{assert.deepEqual(filter({updatedFrom:'2026-10-13',updatedTo:'2026-10-13'}),[1]);assert.deepEqual(filter({updatedFrom:'2026-10-14'}),[2,3]);assert.deepEqual(filter({updatedTo:'2026-10-14'}),[1,2]);assert.equal(orderDate('2026-10-13T18:00:00Z'),'2026-10-14');assert.equal(matchesOrderFilters({}, {...emptyOrderFilters(),updatedFrom:'2026-10-13'}),false);assert.deepEqual(filter({updatedFrom:'2026-10-15',updatedTo:'2026-10-13'}),[])});
test('清空后恢复全部订单',()=>assert.deepEqual(filter({}),[1,2,3]));
