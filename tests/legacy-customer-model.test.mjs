import {test} from 'node:test';
import assert from 'node:assert/strict';
import {customerFieldRequired,firstMissingCustomerField,legacyOpeningBalanceText} from '../src/legacyCustomerModel.js';

const source={system:'legacy-feishu-crm',id:'1000000000000001'};
const complete={id:'c1',name:'客户甲',entity:'主体甲',owner:'负责人甲',status:'达成合作',channel:'其他',contact:'联系人甲',phone:'13800000000'};

test('新客户与普通客户保留全部必填要求，草稿伪造来源不豁免',()=>{
 assert.deepEqual(firstMissingCustomerField({name:'新客户'}),['entity','客户主体']);
 assert.deepEqual(firstMissingCustomerField({...complete,phone:''},complete),['phone','联系人手机']);
 assert.equal(customerFieldRequired({name:'新客户',legacySource:source},'phone'),true);
});

test('历史缺失资料可留空，已有资料和已补全资料不能再清空',()=>{
 const original={...complete,entity:'',owner:'',phone:'',legacySource:source};
 assert.equal(firstMissingCustomerField(original,original),undefined);
 assert.equal(customerFieldRequired(original,'entity'),false);
 assert.deepEqual(firstMissingCustomerField({...original,name:''},original),['name','客户名称']);
 assert.deepEqual(firstMissingCustomerField({...original,contact:''},original),['contact','联系人名称']);
 const completed={...original,phone:'13800000000'};
 assert.deepEqual(firstMissingCustomerField({...completed,phone:''},completed),['phone','联系人手机']);
});

test('原表未填写、明确零值和正期初金额分别显示',()=>{
 assert.equal(legacyOpeningBalanceText({legacySource:source}),'旧表未填写');
 assert.equal(legacyOpeningBalanceText({openingBalance:0}),'¥ 0.00');
 assert.equal(legacyOpeningBalanceText({openingBalance:1234567.8}),'¥ 1,234,567.80');
});
