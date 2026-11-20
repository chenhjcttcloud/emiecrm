import { csvCell } from './csv.js';

export const CUSTOMER_IMPORT_MAX_BYTES = 5 * 1024 * 1024;
export const CUSTOMER_IMPORT_MAX_ROWS = 2000;

export const customerImportFields = [
  { key: 'name', label: '客户名称', required: true, aliases: ['客户名称', '客户名', '客户', '公司名称', '分销商名称', '采购商名称', '买家名称', 'name', 'customername', 'co_name', 'bill_name'] },
  { key: 'externalId', label: '聚水潭客户编号', aliases: ['聚水潭客户编号', '聚水潭客户编码', '外部客户编号', '外部客户编码', '客户编号', '客户编码', '分销商编号', '采购商编号', '买家id', 'externalid', 'channel_co_id', 'drp_co_id', 'buyer_id', 'cus_id'] },
  { key: 'entity', label: '客户主体', aliases: ['客户主体', '企业主体', '主体', 'entity'] },
  { key: 'owner', label: '负责人', aliases: ['负责人', '客户负责人', '业务员', '销售人员', 'owner', 'salesman', 'supplier_salesman_name'] },
  { key: 'status', label: '客户状态', aliases: ['客户状态', '客户合作状态', 'status'] },
  { key: 'channel', label: '销售渠道', aliases: ['销售渠道', '客户渠道', '渠道', 'channel'] },
  { key: 'contact', label: '联系人名称', aliases: ['联系人名称', '联系人', '联系人姓名', 'contact', 'contact_name'] },
  { key: 'phone', label: '联系人手机', aliases: ['联系人手机', '联系人手机号', '联系电话', '联系手机', '手机', '手机号', '电话', 'phone', 'mobile', 'contact_phone'] },
  { key: 'wechat', label: '联系人微信', aliases: ['联系人微信', '微信', '微信号', 'wechat'] },
  { key: 'position', label: '联系人职位', aliases: ['联系人职位', '职位', '职务', 'position'] },
  { key: 'links', label: '店铺名称与链接', aliases: ['店铺名称与链接', '线上销售店铺&链接', '店铺链接', '线上店铺', 'links'] },
];

const normalizeHeader = value => String(value ?? '').replace(/^\uFEFF/, '').trim().toLowerCase().replace(/[\s_\-（）()：:]+/gu, '');

export function suggestCustomerMapping(headers) {
  const used = new Set();
  return Object.fromEntries(customerImportFields.map(field => {
    const aliases = new Set(field.aliases.map(normalizeHeader));
    const index = headers.findIndex((header, i) => !used.has(i) && aliases.has(normalizeHeader(header)));
    if (index >= 0) used.add(index);
    return [field.key, index >= 0 ? index : ''];
  }));
}

export function validateCustomerMapping(headers, mapping) {
  const used = new Set();
  for (const field of customerImportFields) {
    const value = mapping[field.key];
    if (value === '' || value == null) {
      if (field.required) return '请选择客户名称对应的文件列。';
      continue;
    }
    if (!Number.isInteger(Number(value)) || Number(value) < 0 || Number(value) >= headers.length) return '文件列映射无效，请重新选择。';
    if (used.has(Number(value))) return '同一文件列只能对应一个客户字段，请调整重复的映射。';
    used.add(Number(value));
  }
  return '';
}

export function makeCustomerImportRows(parsed, mapping) {
  if (!parsed || !Array.isArray(parsed.headers) || !Array.isArray(parsed.rows)) throw Error('文件解析结果无效，请重新选择文件。');
  const problem = validateCustomerMapping(parsed.headers, mapping);
  if (problem) throw Error(problem);
  if (!parsed.rows.length || parsed.rows.length > CUSTOMER_IMPORT_MAX_ROWS) throw Error('每次请选择含 1 至 2000 行客户数据的文件。');
  const numbers = new Set();
  return parsed.rows.map(row => {
    if (!Number.isSafeInteger(row.rowNumber) || row.rowNumber < 2 || numbers.has(row.rowNumber) || !Array.isArray(row.values)) throw Error('文件行号或数据无效，请重新选择文件。');
    numbers.add(row.rowNumber);
    return Object.fromEntries([
      ['rowNumber', row.rowNumber],
      ...customerImportFields.map(field => [field.key, mapping[field.key] === '' || mapping[field.key] == null ? '' : String(row.values[Number(mapping[field.key])] ?? '').trim()]),
    ]);
  });
}

export function initialCustomerDecisions(rows) {
  return Object.fromEntries(rows.map(row => [row.rowNumber, {
    rowNumber: row.rowNumber,
    action: row.status === 'NEW' && row.createable !== false ? 'CREATE' : 'SKIP',
  }]));
}

export function mergeCustomerImportCandidates(customers, rows) {
  const byId = new Map(customers.map(customer => [customer.id, customer]));
  for (const row of rows) for (const match of row.matches || []) {
    if (match.id) byId.set(match.id, { ...byId.get(match.id), ...match });
  }
  return [...byId.values()];
}

export function customerDecisionSummary(rows, decisions) {
  const summary = { created: 0, linked: 0, skipped: 0, missingTarget: 0 };
  for (const row of rows) {
    const decision = decisions[row.rowNumber];
    if (decision?.action === 'CREATE') summary.created++;
    else if (decision?.action === 'LINK') {
      summary.linked++;
      if (!decision.customerId) summary.missingTarget++;
    } else summary.skipped++;
  }
  return summary;
}

export function customerImportTemplate() {
  return '\uFEFF' + customerImportFields.map(field => csvCell(field.label)).join(',') + '\r\n';
}

export function validateCustomerFile(file) {
  if (!file || !/\.(xlsx|xls|csv|tsv)$/i.test(file.name)) return '请选择 Excel（.xlsx / .xls）、CSV 或 TSV 文件。';
  if (!file.size) return '所选文件为空，请重新选择。';
  if (file.size > CUSTOMER_IMPORT_MAX_BYTES) return '文件不能超过 5 MB，请拆分后导入。';
  return '';
}
