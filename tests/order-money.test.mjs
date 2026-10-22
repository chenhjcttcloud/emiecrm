import {test} from 'node:test';
import assert from 'node:assert/strict';
import {orderTotal,moneyCents} from '../src/orderMoney.js';
import {customerOptions} from '../src/customerOptions.js';
test('金额按十进制汇总后四舍五入，避免浮点误差',()=>{
 assert.equal(orderTotal([{qty:'1.005',price:'1.00'}]),1.01);
 assert.equal(orderTotal([{qty:'1',price:'0.10'},{qty:'1',price:'0.20'}]),0.3);
 assert.equal(orderTotal([{qty:'0.005',price:'1.00'},{qty:'0.005',price:'1.00'}]),0.01);
});
test('拒绝超过精度、负数、非数字和超限金额',()=>{
 for(const item of [{qty:'1',price:'0.004'},{qty:'1.0000001',price:'1'},{qty:'-1',price:'1'},{qty:'NaN',price:'1'},{qty:'1e3',price:'1'},{qty:'1000000001',price:'1'}])assert.equal(orderTotal([item]),null);
});
test('同名客户选项按ID区分并保留搜索信息',()=>{
 const options=customerOptions([{id:'c1',name:'同名客户',entity:'主体甲'},{id:'c2',name:'同名客户',entity:'主体乙'},{id:'c3',name:'唯一客户'}]);
 assert.deepEqual(options.map(c=>c.value),['c1','c2','c3']);assert.notEqual(options[0].label,options[1].label);assert.equal(options[2].label,'唯一客户');
});

test('收款金额保持分精度，不静默舍入',()=>{
 assert.equal(moneyCents('0.01'),1);assert.equal(moneyCents('1.20'),120);assert.equal(moneyCents('0.004'),null);assert.equal(moneyCents('1.005'),null);assert.equal(moneyCents(''),null);assert.equal(moneyCents('999999999999.99'),99999999999999);
});
