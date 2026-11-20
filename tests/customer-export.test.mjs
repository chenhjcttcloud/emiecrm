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
