import React from 'react';
export default function Pagination({total,page,pageSize,onPage,onPageSize,label='分页'}){
 const pages=Math.max(1,Math.ceil(total/pageSize));
 return <div className="pagination" role="group" aria-label={label}><span>共 {total} 条</span>{onPageSize&&<select aria-label={label+'每页条数'} value={pageSize} onChange={e=>onPageSize(Number(e.target.value))}>{[5,10,20].map(n=><option key={n} value={n}>每页 {n} 条</option>)}</select>}<button type="button" className="secondary" disabled={page<=1} onClick={()=>onPage(page-1)}>上一页</button><span aria-live="polite">第 {page} / {pages} 页</span><button type="button" className="secondary" disabled={page>=pages} onClick={()=>onPage(page+1)}>下一页</button></div>;
}
