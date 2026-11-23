import {moneyCents,orderTotal} from './orderMoney.js';

const moneyFormat=new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'});
export const receiptProductMoney=value=>value===null||value===undefined||value===''||!Number.isFinite(Number(value))?'—':moneyFormat.format(Number(value));

export function itemsFromOrder(order,products=[]){
 const catalog=products instanceof Map?products:new Map((Array.isArray(products)?products:[]).map(product=>[product.id,product]));
 return (Array.isArray(order?.items)?order.items:[]).filter(item=>item&&typeof item==='object').map(item=>{
  const product=catalog.get(item.product);
  return {product:item.product,name:product?.name||item.name||item.product||'未命名产品',sku:product?.jstSku||product?.code||item.sku||'',model:product?.model||item.model||'',qty:item.qty,price:item.price};
 });
}

export function receiptProducts(receipt,order,products=[]){
 if(Array.isArray(receipt?.items))return {items:receipt.items,itemsSource:['order','unknown'].includes(receipt.itemsSource)?receipt.itemsSource:'snapshot'};
 if(receipt&&Object.hasOwn(receipt,'items'))return {items:[],itemsSource:'unknown'};
 return order&&Array.isArray(order.items)?{items:itemsFromOrder(order,products),itemsSource:'order'}:{items:[],itemsSource:'unknown'};
}

export const productSourceLabel=source=>({snapshot:'收款时商品快照',order:'当前关联订单',unknown:'未记录商品资料'}[source]||'未记录商品资料');
export function productSummary(items=[]){
 const rows=Array.isArray(items)?items:[];
 if(!rows.length)return '暂无产品明细';
 return rows.slice(0,2).map(item=>`${item.name||item.product||'未命名产品'} × ${item.qty??'—'}`).join('、')+(rows.length>2?` 等 ${rows.length} 款`:'');
}
export function productDetailsText(items=[]){
 return (Array.isArray(items)?items:[]).map(item=>[
  item.name||item.product||'未命名产品',item.sku&&`SKU：${item.sku}`,item.model&&`规格：${item.model}`,
  `数量：${item.qty??'—'}`,`单价：${receiptProductMoney(item.price)}`,`小计：${receiptProductMoney(orderTotal([item]))}`,
 ].filter(Boolean).join('；')).join('\n');
}
export function canReceiveOrder(order){
 if(!order?.customerId||order.status!=='已批准'||order.deletedAt!=null)return false;
 const total=moneyCents(order.total),paid=moneyCents(order.paid??0);
 return total!==null&&paid!==null&&paid<total;
}
