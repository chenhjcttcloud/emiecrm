import React,{useEffect,useRef,useState} from 'react';
import Pagination from './Pagination';
import{dashboardMoney}from'./dashboardModel';
import{salesItemsCsv,salesQuantity}from'./salesModel';
import './sales.css';

export default function SalesItems({api,Icon,DialogLayer,onOpenOrder}){
 const[filters,setFilters]=useState({month:'',owner:'',customerId:''}),[query,setQuery]=useState(''),[debouncedQuery,setDebouncedQuery]=useState(''),[page,setPage]=useState(1),[pageSize,setPageSize]=useState(20),[reload,setReload]=useState(0),[showCosts,setShowCosts]=useState(false),[selected,setSelected]=useState(null);
 const[state,setState]=useState({key:'',loading:true,data:null,error:''}),[options,setOptions]=useState({owners:[],customers:[]});
 const request=useRef(0);
 useEffect(()=>{const timer=setTimeout(()=>setDebouncedQuery(query.trim()),240);return()=>clearTimeout(timer)},[query]);
 const requestKey=JSON.stringify({filters,q:debouncedQuery,page,pageSize,reload});
 useEffect(()=>{
  let active=true;const id=++request.current;setState({key:requestKey,loading:true,data:null,error:''});setSelected(null);
  const params=new URLSearchParams({...filters,q:debouncedQuery,page,pageSize});
  api('sales/items?'+params).then(data=>{
   if(!active||id!==request.current)return;
   if(!Array.isArray(data?.rows)||!data.summary||!Number.isSafeInteger(data.total)||!Number.isSafeInteger(data.page)||!Array.isArray(data.options?.owners)||!Array.isArray(data.options?.customers))throw Error('产品明细数据格式无效，请重试');
   setOptions(data.options);setState({key:requestKey,loading:false,data,error:''});
  }).catch(error=>{if(active&&id===request.current)setState({key:requestKey,loading:false,data:null,error:error.message||'产品明细加载失败，请重试'})});
  return()=>{active=false};
 },[api,requestKey]);
 const pendingQuery=query.trim()!==debouncedQuery;
 const loading=state.key!==requestKey||state.loading||pendingQuery;
 const data=!loading?state.data:null;
 const error=!loading&&state.key===requestKey?state.error:'';
 const activeFilters=Boolean(query||filters.month||filters.owner||filters.customerId);
 function changeFilter(key,value){setPage(1);setFilters(previous=>({...previous,[key]:value}))}
 function clearFilters(){setPage(1);setQuery('');setFilters({month:'',owner:'',customerId:''})}
 function exportPage(){
  if(!data||loading)return;
  const url=URL.createObjectURL(new Blob([salesItemsCsv(data.rows,showCosts)],{type:'text/csv;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='订单产品明细-'+(filters.month||'全部月份')+'-第'+data.page+'页.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 }
 function openOrder(orderId){if(onOpenOrder)onOpenOrder(orderId)}
 const itemDetail=selected&&<div className="drawer sales-item-detail"><div className="modal-title"><h2>订单产品明细</h2><button type="button" aria-label="关闭产品明细" onClick={()=>setSelected(null)}><Icon name="close" size={18}/></button></div><div className="modal-body"><div className="sales-detail-product"><span>商品信息</span><h3>{selected.product||'历史商品'}</h3><p>{selected.code||'未记录产品代码'}{selected.model?' · '+selected.model:''}</p></div><dl className="sales-detail-grid">{[['所属订单',selected.orderId],['客户名称',selected.customer],['签单人',selected.owner],['审批状态',selected.status],['归属日期',selected.date],['产品条码',selected.barcode||'未记录'],['上市时间',selected.listedOn||'未记录'],['产品数量',salesQuantity(selected.qty)],['销售单价',dashboardMoney(selected.price)],['销售小计',dashboardMoney(selected.amount)],['历史成本单价',selected.unitCost==null?'未记录':dashboardMoney(selected.unitCost)],['历史成本小计',selected.cost==null?'未记录':dashboardMoney(selected.cost)],['历史毛利',selected.profit==null?'未记录':dashboardMoney(selected.profit)]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value||'—'}</dd></div>)}</dl><p className="sales-page-note">成本使用订单中已记录的历史数据。未记录的成本和毛利不按零元计算。</p></div><div className="modal-footer"><button type="button" className="secondary" onClick={()=>setSelected(null)}>关闭</button>{onOpenOrder&&<button type="button" className="primary" onClick={()=>{setSelected(null);openOrder(selected.orderId)}}>查看所属订单 <Icon name="arrow" size={15}/></button>}</div></div>;
 return <div className="sales-items sales-page">
  <div className="page-heading"><div><div className="breadcrumb">销售管理 <span>/</span> 订单产品明细</div><h1>订单产品明细</h1><p>汇总订单中的商品、数量和销售金额，快速追溯每一项交易。</p></div><div className="page-heading-symbol"><Icon name="products" size={29}/></div></div>
  <section className="sales-toolbar" aria-label="产品明细筛选"><div className="sales-toolbar-controls">
   <label className="sales-search-control"><Icon name="search" size={16}/><input aria-label="搜索产品明细" maxLength={200} value={query} onChange={e=>{setQuery(e.target.value);setPage(1)}} placeholder="搜索商品、编码、订单或客户"/></label>
   <label className="sales-inline-control sales-month-filter"><span>月份</span><input aria-label="产品明细月份" type="month" min="2000-01" max="9999-12" value={filters.month} onChange={e=>{const value=e.target.value;if(!value||(/^\d{4}-(0[1-9]|1[0-2])$/.test(value)&&Number(value.slice(0,4))>=2000))changeFilter('month',value)}}/>{filters.month&&<button type="button" className="sales-clear-control" aria-label="清除产品明细月份" onClick={()=>changeFilter('month','')}><Icon name="close" size={12}/></button>}</label>
   <label className="sales-inline-control"><span>签单人</span><select aria-label="产品明细签单人" value={filters.owner} onChange={e=>changeFilter('owner',e.target.value)}><option value="">全部签单人</option>{options.owners.map(owner=><option key={owner} value={owner}>{owner}</option>)}</select></label>
   <label className="sales-inline-control sales-customer-filter"><span>客户</span><select aria-label="产品明细客户" value={filters.customerId} onChange={e=>changeFilter('customerId',e.target.value)}><option value="">全部客户</option>{options.customers.map(customer=><option key={customer.id} value={customer.id}>{customer.name}</option>)}</select></label>
  </div><div className="sales-toolbar-actions">{activeFilters&&<button type="button" className="sales-quiet-button" onClick={clearFilters}>重置</button>}<button type="button" className="sales-refresh-button" disabled={loading} onClick={()=>setReload(value=>value+1)}><Icon name="clock" size={14}/>{loading?'加载中':'刷新'}</button></div></section>
  <div className="sales-items-overview" aria-live="polite"><div><span>匹配产品明细</span><strong>{data?data.total.toLocaleString('zh-CN'):'—'}<small> 条</small></strong></div><div><span>数量合计</span><strong>{data?salesQuantity(data.summary.quantity):'—'}</strong></div><div><span>销售金额合计</span><strong>{data?dashboardMoney(data.summary.amount):'—'}</strong></div>{showCosts&&<><div><span>历史成本合计</span><strong>{data?dashboardMoney(data.summary.cost):'—'}</strong></div><div><span>历史毛利合计</span><strong>{data?dashboardMoney(data.summary.profit):'—'}</strong></div></>}</div>
  <section className="panel sales-list-panel"><div className="sales-list-heading"><span>订单商品明细 <small>汇总覆盖全部匹配结果</small></span><div><label className="sales-cost-toggle"><input type="checkbox" checked={showCosts} onChange={e=>setShowCosts(e.target.checked)}/>显示成本与毛利</label><button type="button" className="sales-quiet-button" disabled={loading||Boolean(error)||!data?.rows.length} onClick={exportPage}><Icon name="export" size={15}/>导出本页</button></div></div>
   <div className="table-scroll"><table className="sales-items-table"><thead><tr>{['商品名称','产品代码','规格型号','客户名称','签单人','订单编号','归属日期','审批状态','数量','销售单价','销售小计',...(showCosts?['历史成本单价','历史成本小计','历史毛利']:[])].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{data?.rows.map(row=><tr key={row.id}><td><button type="button" className="sales-product-link" onClick={()=>setSelected(row)}>{row.product||'历史商品'}</button></td><td>{row.code||'—'}</td><td>{row.model||'—'}</td><td>{row.customer||'—'}</td><td>{row.owner||'—'}</td><td>{onOpenOrder?<button type="button" className="sales-order-link" onClick={()=>openOrder(row.orderId)}>{row.orderId}</button>:row.orderId}</td><td>{row.date||'—'}</td><td><span className={'sales-status '+(row.status==='已批准'?'is-approved':'')}>{row.status||'—'}</span></td><td className="sales-number">{salesQuantity(row.qty)}</td><td className="sales-number">{dashboardMoney(row.price)}</td><td className="sales-number sales-total">{dashboardMoney(row.amount)}</td>{showCosts&&<><td className="sales-number">{row.unitCost==null?<span className="sales-unrecorded">未记录</span>:dashboardMoney(row.unitCost)}</td><td className="sales-number">{row.cost==null?<span className="sales-unrecorded">未记录</span>:dashboardMoney(row.cost)}</td><td className="sales-number">{row.profit==null?<span className="sales-unrecorded">未记录</span>:dashboardMoney(row.profit)}</td></>}</tr>)}</tbody></table></div>
   {(loading||error||!data?.rows.length)&&<div className={'sales-list-state'+(error?' is-error':'')} role={error?'alert':'status'}>{loading?'正在加载订单产品明细…':error||'暂无符合条件的产品明细'}{error&&<button type="button" className="sales-quiet-button" onClick={()=>setReload(value=>value+1)}>重新加载</button>}</div>}
   {data&&<Pagination total={data.total} page={data.page} pageSize={pageSize} pageSizes={[10,20,50,100]} onPage={setPage} onPageSize={value=>{setPageSize(value);setPage(1)}} label="产品明细分页"/>}
  </section>
  {data?.warnings?.length>0&&<details className="sales-data-warnings"><summary>数据与统计提示（{data.warnings.length}）</summary><ul>{data.warnings.map((warning,index)=><li key={index}>{warning}</li>)}</ul></details>}
  <p className="sales-page-note">明细只读展示订单中的商品记录。选择商品查看详情，选择订单编号查看原订单。</p>
  {DialogLayer&&<DialogLayer open={Boolean(selected)} onClose={()=>setSelected(null)} kind="drawer" label="订单产品明细">{itemDetail}</DialogLayer>}
 </div>;
}
