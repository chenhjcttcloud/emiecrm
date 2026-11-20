import{csvCell}from'./csv.js';
import{moneyCents}from'./orderMoney.js';

export function salesOrderFinance(order,effective={}){
 const total=moneyCents(effective?.total??order.total),paid=moneyCents(effective?.paid??order.paid??0);
 if(total===null||paid===null)return{status:'待核对',tone:'warning',paid:null,outstanding:null};
 return{status:total===0?'无需回款':paid>=total?'已回款':paid>0?'部分回款':'待回款',tone:total>0&&paid>=total?'green':'warning',paid:paid/100,outstanding:Math.max(0,total-paid)/100};
}

export function salesQuantity(value){
 if(value===null||value===undefined||value==='')return'—';
 const match=String(value).match(/^(-?)(\d+)(?:\.(\d{1,6}))?$/);
 if(!match)return'—';
 const fraction=(match[3]||'').replace(/0+$/,'');
 return(match[1]||'')+BigInt(match[2]).toLocaleString('zh-CN')+(fraction?'.'+fraction:'');
}

export function salesItemsCsv(rows,includeCosts=false){
 const fields=[['商品名称','product'],['产品代码','code'],['规格型号','model'],['条码','barcode'],['上市时间','listedOn'],['客户名称','customer'],['签单人','owner'],['订单编号','orderId'],['归属日期','date'],['审批状态','status'],['数量','qty'],['销售单价','price'],['销售小计','amount']];
 if(includeCosts)fields.push(['历史成本单价','unitCost'],['历史成本小计','cost'],['历史毛利','profit']);
 return'\uFEFF'+[fields.map(([label])=>label),...rows.map(row=>fields.map(([,key])=>row[key]??''))].map(row=>row.map(csvCell).join(',')).join('\r\n');
}

export function salesTargetStatus(amount){
 if(amount===null||amount===undefined||amount==='')return'未设置';
 return Number(amount)===0?'目标为零':'已设置';
}

export const salesTargetDepartmentStates=[
 {status:'unassigned',label:'未分配部门'},
 {status:'unmatched',label:'人员未匹配'},
 {status:'ambiguous',label:'人员重名待确认'}
];

export function salesTargetDepartmentStatus(row){
 const status=row?.departmentStatus;
 if(status==='assigned'||(!status&&row?.departmentId&&row?.department))
  return typeof row?.departmentId==='string'&&row.departmentId&&typeof row?.department==='string'&&row.department?'assigned':'unassigned';
 return salesTargetDepartmentStates.some(item=>item.status===status)?status:'unassigned';
}

export function salesTargetDepartmentLabel(row){
 const status=salesTargetDepartmentStatus(row);
 return status==='assigned'?row.department:salesTargetDepartmentStates.find(item=>item.status===status).label;
}

export function matchesSalesTargetFilters(row,{query='',department=''}={}){
 const owner=typeof row?.owner==='string'?row.owner:'';
 if(!owner.toLocaleLowerCase().includes(String(query).trim().toLocaleLowerCase()))return false;
 if(!department)return true;
 const status=salesTargetDepartmentStatus(row);
 if(department.startsWith('department:'))return status==='assigned'&&row.departmentId===department.slice('department:'.length);
 return department.startsWith('status:')&&status===department.slice('status:'.length);
}
