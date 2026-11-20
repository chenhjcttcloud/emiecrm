import {csvCell} from './csv.js';

const columns=[['CRM客户编号','id'],['客户名称','name'],['客户主体','entity'],['客户预付款','balance'],['负责人','owner'],['客户状态','status'],['销售渠道','channel'],['线上销售店铺&链接','links'],['联系人名称','contact'],['联系人职位','position'],['联系人手机','phone'],['联系人微信','wechat']];
export function customerExportCsv(customers){
 const header=[...columns.map(([label])=>label),'聚水潭编号关联','期初预收款','旧系统客户ID','旧系统客户编号','旧系统来源记录'];
 const rows=customers.map(customer=>[...columns.map(([,key])=>customer[key]??''),JSON.stringify(customer.externalLinks||[]),customer.openingBalance??'',customer.legacySource?.id??'',customer.legacySource?.fields?.['客户编号']??'',customer.legacySource?JSON.stringify(customer.legacySource):'']);
 return '\uFEFF'+[header,...rows].map(row=>row.map(csvCell).join(',')).join('\r\n');
}
