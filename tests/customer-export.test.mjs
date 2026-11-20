import {test} from 'node:test';
import assert from 'node:assert/strict';
import {customerExportCsv} from '../src/customerExport.js';

test('客户导出包含稳定CRM编号和全部外部关联，保留前导零并转义单元格',()=>{
 const links=[{system:'jushuitan',environment:'production',type:'offline',id:'000123',name:'外部名称'}];
 const csv=customerExportCsv([{id:'c1',name:'=客户,甲',phone:'0013800000000',balance:12.5,externalLinks:links}]);
 assert.ok(csv.startsWith('\uFEFF"CRM客户编号"'));
 assert.ok(csv.includes('"\'=客户,甲"'));
 assert.ok(csv.includes('"0013800000000"'));
 assert.ok(csv.includes('"12.5"'));
 assert.ok(csv.includes('""000123""'));
 assert.ok(csv.includes('"c1"'));
});

test('客户导出保留旧系统文本ID、原始记录和期初金额，不将未填写转为0',()=>{
 const legacySource={system:'legacy-feishu-crm',id:'1234567890123456',fields:{'客户编号':'000012','预收款金额':null}};
 const empty=customerExportCsv([{id:'c2',name:'旧客户乙',legacySource}]);
 assert.ok(empty.includes('"期初预收款","旧系统客户ID","旧系统客户编号","旧系统来源记录"'));
 assert.ok(empty.includes('"[]","","1234567890123456","000012",'));
 assert.ok(empty.includes('""预收款金额"":null'));
 const zero=customerExportCsv([{id:'c2',name:'旧客户乙',legacySource,openingBalance:0}]);
 assert.ok(zero.includes('"[]","0","1234567890123456"'));
 const positive=customerExportCsv([{id:'c2',name:'旧客户乙',legacySource,openingBalance:123.45}]);
 assert.ok(positive.includes('"[]","123.45","1234567890123456"'));
});
