import {test} from 'node:test';
import assert from 'node:assert/strict';
import {emptyCustomerFilters,matchesCustomerFilters} from '../src/customerFilterLogic.js';
const customers=[{name:'Hello品牌',entity:'亿觅公司',owner:'甲',status:'达成合作',channel:'线下门店',balance:100},{name:'客户乙',entity:'亿觅公司',owner:'乙',status:'跟进中',channel:'淘宝店铺',balance:0},{name:'客户丙',owner:'甲',status:'潜在客户',channel:'其他'}];
const ids=filters=>customers.filter(c=>matchesCustomerFilters(c,{...emptyCustomerFilters(),...filters})).map(c=>c.name);
test('名称主体关键字和字段独立匹配',()=>{assert.deepEqual(ids({name:' hello '}),['Hello品牌']);assert.deepEqual(ids({entity:'亿觅'}),['Hello品牌','客户乙']);assert.deepEqual(ids({owner:'甲',status:'达成合作',channel:'线下门店'}),['Hello品牌']);assert.deepEqual(ids({status:'甲'}),[])});
test('预付款筛选和组合条件',()=>{assert.deepEqual(ids({prepayment:'positive'}),['Hello品牌']);assert.deepEqual(ids({prepayment:'zero'}),['客户乙','客户丙']);assert.deepEqual(ids({entity:'亿觅',prepayment:'zero'}),['客户乙']);assert.deepEqual(ids({owner:'乙',prepayment:'positive'}),[]);assert.equal(ids({}).length,3)});
