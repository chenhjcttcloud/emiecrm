import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  customerImportFields, suggestCustomerMapping, validateCustomerMapping, makeCustomerImportRows,
  initialCustomerDecisions, customerDecisionSummary, customerImportTemplate, validateCustomerFile,
  CUSTOMER_IMPORT_MAX_BYTES, mergeCustomerImportCandidates,
} from '../src/customerImportModel.js';

test('版本冲突后重预览的新增及改名候选能进入关联选择器，按ID保留原有详细资料',()=>{
 const customers=[{id:'c1',name:'旧名',entity:'旧主体',phone:'13800000000'},{id:'c2',name:'同名'}];
 const merged=mergeCustomerImportCandidates(customers,[{matches:[{id:'c1',name:'新名',entity:'新主体'},{id:'c3',name:'同名',entity:'新候选'}]},{matches:[{id:'c3',name:'同名',entity:'新候选'}]}]);
 assert.equal(merged.length,3);
 assert.deepEqual(merged.find(c=>c.id==='c1'),{id:'c1',name:'新名',entity:'新主体',phone:'13800000000'});
 assert.equal(merged.find(c=>c.id==='c3').entity,'新候选');
 assert.equal(customers[0].name,'旧名');
});

test('客户导入自动识别中英文列名，并分别保留名称和编号', () => {
  const headers = ['客户名称', ' 客户编号 ', '客户主体', '负责人', '销售渠道', '联系人', 'contact_phone', '微信号'];
  const mapping = suggestCustomerMapping(headers);
  assert.equal(mapping.name, 0);
  assert.equal(mapping.externalId, 1);
  assert.equal(mapping.phone, 6);
  assert.equal(mapping.wechat, 7);
  assert.equal(validateCustomerMapping(headers, mapping), '');
  assert.equal(suggestCustomerMapping(['Co_Name', 'channel_co_id', 'CONTACT_NAME']).name, 0);
  assert.equal(suggestCustomerMapping(['Co_Name', 'channel_co_id', 'CONTACT_NAME']).externalId, 1);
});

test('客户映射拒绝同一列复用、越界或缺少名称列', () => {
  const headers = ['客户名称', '客户编号'];
  const mapping = suggestCustomerMapping(headers);
  assert.match(validateCustomerMapping(headers, { ...mapping, externalId: 0 }), /同一文件列/);
  assert.match(validateCustomerMapping(headers, { ...mapping, externalId: 2 }), /无效/);
  assert.match(validateCustomerMapping(headers, { ...mapping, name: '' }), /客户名称/);
});

test('客户导入把手机号和外部编号作为文本，保留前导零和长编号', () => {
  const parsed = { headers: ['客户名称', '客户编号', '手机'], rows: [{ rowNumber: 2, values: [' 客户甲 ', '00012345678901234567890', '0013800000000'] }] };
  const rows = makeCustomerImportRows(parsed, suggestCustomerMapping(parsed.headers));
  assert.equal(rows[0].rowNumber, 2);
  assert.equal(rows[0].name, '客户甲');
  assert.equal(rows[0].externalId, '00012345678901234567890');
  assert.equal(rows[0].phone, '0013800000000');
  assert.equal(rows[0].balance, undefined);
  assert.equal(rows[0].owner, '');
});

test('客户导入拒绝空数据、超过行上限和异常或重复行号', () => {
  const headers = ['客户名称'], mapping = suggestCustomerMapping(headers);
  assert.throws(() => makeCustomerImportRows({ headers, rows: [] }, mapping), /1 至 2000/);
  assert.throws(() => makeCustomerImportRows({ headers, rows: Array.from({ length: 2001 }, (_, i) => ({ rowNumber: i + 2, values: ['客户'] })) }, mapping), /1 至 2000/);
  assert.throws(() => makeCustomerImportRows({ headers, rows: [{ rowNumber: 1, values: ['客户'] }] }, mapping), /行号/);
  assert.throws(() => makeCustomerImportRows({ headers, rows: [{ rowNumber: 2, values: ['客户'] }, { rowNumber: 2, values: ['客户'] }] }, mapping), /行号/);
});

test('初始决策仅自动选择可新增客户，已有、待确认和无效记录均跳过', () => {
  const rows = [{ rowNumber: 2, status: 'NEW', createable: true }, { rowNumber: 3, status: 'MATCHED' }, { rowNumber: 4, status: 'REVIEW', createable: true }, { rowNumber: 5, status: 'INVALID', linkable: true }, { rowNumber: 6, status: 'NEW', createable: false }];
  const decisions = initialCustomerDecisions(rows);
  assert.equal(decisions[2].action, 'CREATE');
  for (const row of rows.slice(1)) assert.equal(decisions[row.rowNumber].action, 'SKIP');
  decisions[4] = { rowNumber: 4, action: 'LINK', customerId: 'c1' };
  decisions[5] = { rowNumber: 5, action: 'LINK' };
  assert.deepEqual(customerDecisionSummary(rows, decisions), { created: 1, linked: 2, skipped: 2, missingTarget: 1 });
});

test('空白CSV模板含所有可导入列、UTF-8 BOM且没有虚构客户', () => {
  const template = customerImportTemplate();
  assert.equal(template[0], '\uFEFF');
  assert.ok(template.endsWith('\r\n'));
  assert.equal(template.split('\r\n').length, 2);
  for (const field of customerImportFields) assert.ok(template.includes(`"${field.label}"`));
  assert.ok(!template.includes('balance'));
});

test('客户文件校验限制扩展名、空文件和五MB大小', () => {
  for (const name of ['客户.XLSX', '客户.xls', '客户.csv', '客户.tsv']) assert.equal(validateCustomerFile({ name, size: 100 }), '');
  assert.match(validateCustomerFile({ name: '客户.json', size: 100 }), /请选择/);
  assert.match(validateCustomerFile({ name: '客户.csv', size: 0 }), /为空/);
  assert.equal(validateCustomerFile({ name: '客户.csv', size: CUSTOMER_IMPORT_MAX_BYTES }), '');
  assert.match(validateCustomerFile({ name: '客户.csv', size: CUSTOMER_IMPORT_MAX_BYTES + 1 }), /5 MB/);
});
test('买家昵称或店铺买家账号不会被自动认作聚水潭唯一客户编号',()=>{
 const mapping=suggestCustomerMapping(['客户名称','shop_buyer_id','买家昵称','buyer_id']);
 assert.equal(mapping.externalId,3);
 assert.equal(suggestCustomerMapping(['客户名称','shop_buyer_id','买家账号']).externalId,'');
});
