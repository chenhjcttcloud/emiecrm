import {csvCell} from './csv.js';
import {dashboardTime} from './dashboardModel.js';
import {orderNumberForId} from './orderNumber.js';
import {productDetailsText,productSourceLabel} from './receiptProductModel.js';

export function receiptLedgerCsv(rows,orders=[]) {
  const table=[['订单编号','客户名称','签单人','订单当前回款状态','流水类型','收款方式','收款金额','税率（原始记录）','税额','订单日期','实际到账日期','登记时间','操作人','产品资料来源','产品明细'],
    ...rows.map(row=>[row.orderNumber||orderNumberForId(row.orderId||row.order,orders),row.customer,row.owner,row.status,row.type,row.method,row.amount,row.taxRate??'',row.tax??'',row.orderDate??'',row.receivedOn??'',dashboardTime(row.time),row.operator,productSourceLabel(row.itemsSource),productDetailsText(row.items)])];
  return '\uFEFF'+table.map(row=>row.map(csvCell).join(',')).join('\r\n');
}
