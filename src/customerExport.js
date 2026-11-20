import {csvCell} from './csv.js';

const columns=[['CRM客户编号','id'],['客户名称','name'],['客户主体','entity'],['客户预付款','balance'],['负责人','owner'],['客户状态','status'],['销售渠道','channel'],['线上销售店铺&链接','links'],['联系人名称','contact'],['联系人职位','position'],['联系人手机','phone'],['联系人微信','wechat']];
export function customerExportCsv(customers){
 const header=[...columns.map(([label])=>label),'聚水潭编号关联'];
 const rows=customers.map(customer=>[...columns.map(([,key])=>customer[key]??''),JSON.stringify(customer.externalLinks||[])]);
 return '\uFEFF'+[header,...rows].map(row=>row.map(csvCell).join(',')).join('\r\n');
}
