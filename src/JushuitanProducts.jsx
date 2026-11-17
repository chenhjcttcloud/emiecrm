import React,{useEffect,useState,useRef} from 'react';
import JushuitanAuthorization from './JushuitanAuthorization';
import Pagination from './Pagination';
import BusinessImage from './BusinessImage';

const isRemote=p=>p.source==='jushuitan'&&!p.historicalOnly;
const price=value=>value!==''&&value!=null?'¥ '+Number(value).toFixed(2):'未设置';
const productSku = product => String(product.jstSku ?? '').trim() || String(product.code ?? '').trim();

export function matchesProductPrefix(product) {
 const sku = productSku(product).toUpperCase();
 return sku.startsWith('EMIE') || sku.startsWith('69');
}

function ProductPhoto({product,Icon,onOpen}){
 const source=product.source==='jushuitan'?product.jstImage:product.image;
 const[failed,setFailed]=useState(false);
 useEffect(()=>setFailed(false),[source]);
 return <button type="button" className="catalog-photo product-image" disabled={!source||failed} aria-label={'查看商品图片：'+product.name} onClick={onOpen}>
 {source&&!failed?<BusinessImage src={source} alt={product.name} loading="lazy" referrerPolicy="no-referrer" onError={()=>setFailed(true)}/>:<div className="product-placeholder"><div className="placeholder-symbol"><Icon name="products" size={34}/></div><span>{failed?'图片暂不可用':'暂无商品图片'}</span></div>}
 <span className={'catalog-product-status '+(isRemote(product)&&product.enabled?'is-enabled':'is-muted')}>{!isRemote(product)?'历史商品':product.enabled?'已启用':'已停用'}</span>
 </button>;
}
export default function JushuitanProducts({db,api,onSynced,canWrite,Icon,DialogLayer}){
 const[busy,setBusy]=useState(false),[error,setError]=useState(''),[query,setQuery]=useState(''),[mode,setMode]=useState('sku'),[scope,setScope]=useState('all'),[page,setPage]=useState(1),[warehouses,setWarehouses]=useState([]),[warehouseId,setWarehouseId]=useState(''),[stocks,setStocks]=useState({}),[preview,setPreview]=useState(null);
 useEffect(()=>{let active=true;api('jst/warehouses').then(r=>{if(active)setWarehouses(r)}).catch(e=>{if(active)setError(e.message)});return()=>{active=false}},[]);
 const[authorization,setAuthorization]=useState(null);
 useEffect(()=>{let active=true;const refresh=()=>api('jst/config').then(c=>{if(active)setAuthorization(c.authorization)}).catch(()=>{if(active)setAuthorization({status:'UNAVAILABLE'})});refresh();const timer=setInterval(refresh,60000);return()=>{active=false;clearInterval(timer)}},[]);
 const[job,setJob]=useState({status:'IDLE'}),[startDate,setStartDate]=useState('2000-01-01');
 const revision=useRef(db.revision);useEffect(()=>{revision.current=db.revision},[db.revision]);
 useEffect(()=>{let active=true,inflight=false;async function refresh(){if(inflight)return;inflight=true;try{const info=await api('jst/products/catalog');if(!active)return;setJob(info);if(Number(info.catalogRevision)>Number(revision.current||0)){const state=await api('state');if(active){revision.current=state.revision;onSynced(state)}}}catch(e){if(active)setError(e.message)}finally{inflight=false}}refresh();const timer=setInterval(refresh,5000);return()=>{active=false;clearInterval(timer)}},[]);
 async function catalog(action,mode='FULL'){if(busy)return;setBusy(true);setError('');try{const info=await api(action==='start'?'jst/products/catalog/start':'jst/products/catalog/control',action==='start'?{mode,startDate}:{action});setJob(info)}catch(e){setError(e.message)}finally{setBusy(false)}}
 const activeJob=['RUNNING','PAUSED','FAILED'].includes(job.status);
 async function sync(){if(busy)return;setBusy(true);setError('');try{const state=await api('jst/products/sync',{keyword:query.trim(),mode});onSynced(state);setScope('all');setPage(1)}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function stock(p){setBusy(true);setError('');try{const rows=await api('jst/inventory',{skuId:p.jstSku,warehouseId:Number(warehouseId)});setStocks(s=>({...s,[p.id]:rows}))}catch(e){setError(e.message)}finally{setBusy(false)}}
 const catalogProducts = db.products.filter(matchesProductPrefix);
 const remoteCount = catalogProducts.filter(isRemote).length;
 const searchTerm = query.trim().toLowerCase();
 const rows = catalogProducts.filter(product => {
  const matchesScope = scope === 'all' || (scope === 'jst' ? isRemote(product) : !isRemote(product));
  return matchesScope && [product.name, productSku(product), product.code, product.model]
   .some(value => String(value ?? '').toLowerCase().includes(searchTerm));
 });
 const pageSize = 12;
 const current = Math.min(page, Math.max(1, Math.ceil(rows.length / pageSize)));
 const warehouse=warehouses.find(w=>String(w.id)===String(warehouseId));
 return <div className="catalog-page">
 <section className="catalog-controls panel" aria-label="聚水潭商品查询">
 <div className="catalog-source-row"><div className="catalog-source"><span className="catalog-source-icon"><Icon name="layers" size={19}/></span><div><strong>聚水潭商品目录</strong><p>商品资料与库存由聚水潭统一管理</p></div></div><span className="catalog-sync-time"><Icon name="clock" size={14}/>{db.productsSyncedAt?'最近同步 '+new Date(db.productsSyncedAt).toLocaleString():'尚未同步商品'}</span></div>
 <JushuitanAuthorization authorization={authorization}/>
 <form className="catalog-search-row" onSubmit={e=>{e.preventDefault();sync()}}>
 <div className="catalog-query"><select aria-label="商品同步查询方式" value={mode} onChange={e=>setMode(e.target.value)}><option value="sku">按 SKU</option><option value="name">按名称</option></select><span className="catalog-query-divider"/><Icon name="search" size={17}/><input aria-label="搜索聚水潭商品" placeholder={mode==='sku'?'输入聚水潭 SKU 编码':'输入商品名称'} value={query} onChange={e=>{setQuery(e.target.value);setPage(1)}}/></div>
 <button type="submit" className="primary" disabled={!canWrite||busy}><Icon name={query?'search':'clock'} size={16}/>{busy?'处理中…':query.trim()?'查询并同步商品':'刷新已同步商品'}</button>
 </form>
 <div className="catalog-filter-row"><div className="catalog-tabs" role="group" aria-label="商品来源筛选">{[['all','全部商品',catalogProducts.length],['jst','聚水潭商品',remoteCount],['history','历史商品',catalogProducts.length-remoteCount]].map(([key,label,count])=><button key={key} type="button" aria-pressed={scope===key} className={scope===key?'active':''} onClick={()=>{setScope(key);setPage(1)}}>{label}<span>{count}</span></button>)}</div><label className="catalog-warehouse"><Icon name="products" size={16}/><select aria-label="查询库存仓库" disabled={busy} value={warehouseId} onChange={e=>{setWarehouseId(e.target.value);setStocks({})}}><option value="">选择仓库查询库存</option>{warehouses.map(w=><option key={w.id} value={w.id}>{w.name}{w.main?'（本仓）':''}</option>)}</select></label></div>
 <div className="catalog-bulk-sync">
 <div className="catalog-bulk-heading"><div><strong>全量商品同步</strong><p>后台分段读取商品与图片，可离开此页面。完成后每 10 分钟自动增量更新。</p></div><div className="catalog-bulk-actions">{!activeJob&&<><label>起始日期<input type="date" aria-label="全量同步起始日期" value={startDate} onChange={e=>setStartDate(e.target.value)} min="1900-01-01"/></label><button type="button" className="secondary" disabled={!canWrite||busy} onClick={()=>catalog('start')}>{job.lastFullCompletedAt?'重新全量同步':'开始全量同步'}</button>{job.watermark&&<button type="button" className="secondary" disabled={!canWrite||busy} onClick={()=>catalog('start','INCREMENTAL')}>立即增量更新</button>}</>}{job.status==='RUNNING'&&<button type="button" className="secondary" disabled={!canWrite||busy} onClick={()=>catalog('pause')}>暂停同步</button>}{['PAUSED','FAILED'].includes(job.status)&&<button type="button" className="primary" disabled={!canWrite||busy} onClick={()=>catalog('resume')}>继续同步</button>}</div></div>
 {job.status!=='IDLE'&&<div className="catalog-job-status" role="status"><div><span>{{RUNNING:'同步中',PAUSED:'已暂停',FAILED:'同步中断 · 断点已保留',COMPLETE:'同步完成'}[job.status]} · {job.mode==='FULL'?'全量':'增量'}{job.phase==='VERIFY'?' · 核对已有商品':''}</span><strong>{job.progress||0}%</strong></div><progress aria-label="商品同步时间范围进度" max="100" value={job.progress||0}/><p>已读取 {job.processedRows||0} 条商品记录 · 当前扫描至 {job.cursor}{job.from&&<> · 范围 {job.from.slice(0,10)} 至 {job.until?.slice(0,10)}</>}</p>{job.error&&<p className="catalog-job-error">{job.error}</p>}<small>进度按已扫描时间范围计算；跨时间段重复商品会合并，不重复新增。</small></div>}
 </div>
 </section>
 {error&&<p className="catalog-error" role="alert"><Icon name="image" size={16}/>{error}</p>}
 <div className="catalog-caption"><span>{rows.length} 件商品</span><span>仅显示 SKU 以 EMIE 或 69 开头的商品</span></div>
 <div className="product-grid catalog-grid">{rows.slice((current-1)*pageSize,current*pageSize).map(p=><article className="product-card catalog-card" key={p.id}>
 <ProductPhoto product={p} Icon={Icon} onOpen={()=>setPreview(p)}/>
 <div className="catalog-card-content"><div className="catalog-sku">{productSku(p)}{p.category&&<span>{p.category}</span>}</div><h3 title={p.name}>{p.name}</h3><p className="product-model">{p.model||'暂无规格'}{p.unit?' · '+p.unit:''}</p>
 <div className="product-prices"><div><span>参考售价</span><strong className={p.price===''||p.price==null?'is-empty':''}>{price(p.price)}</strong></div><div><span>参考成本</span><b>{price(p.cost)}</b></div></div>
 <div className="catalog-stock"><div className="catalog-stock-heading"><span>{warehouse?warehouse.name:'仓库库存'}</span><button type="button" className="link-btn" disabled={busy||!warehouseId||!isRemote(p)} onClick={()=>stock(p)}>查询库存<Icon name="arrow" size={14}/></button></div>
 {stocks[p.id]?stocks[p.id].length?stocks[p.id].map((s,i)=><div className="catalog-stock-numbers" key={i}><div><strong>{s.qty??0}</strong><span>实物库存</span></div><div><strong>{s.order_lock??0}</strong><span>订单占用</span></div><div><strong>{s.pick_lock??0}</strong><span>配货占用</span></div></div>):<p>聚水潭暂无库存记录</p>:<p>{!isRemote(p)?'历史商品保留订单引用，不可新选':warehouse?'点击查询，获取所选仓库最新库存':'请先手工选择仓库'}</p>}
 </div></div>
 </article>)}</div>
 {!rows.length&&<div className="catalog-empty panel"><span className="catalog-empty-icon"><Icon name="products" size={36}/></span><h3>{searchTerm?'未找到已同步商品':scope==='history'?'暂无历史商品':'暂无商品'}</h3><p>{searchTerm?'点击「查询并同步商品」，从聚水潭读取匹配商品。':scope==='history'?'历史订单仍可查看原商品。':'输入 SKU 或商品名称，从聚水潭同步商品资料。'}</p></div>}
 <Pagination total={rows.length} page={current} pageSize={pageSize} onPage={setPage}/>
 <DialogLayer open={!!preview} onClose={()=>setPreview(null)} label="商品图片"><div className="modal catalog-preview" onClick={e=>e.stopPropagation()}><div className="modal-title"><h2>{preview?.name}</h2><button type="button" aria-label="关闭商品图片" onClick={()=>setPreview(null)}><Icon name="close" size={18}/></button></div>{preview&&<BusinessImage src={preview.source==='jushuitan'?preview.jstImage:preview.image} alt={preview.name} referrerPolicy="no-referrer"/>}<p>{preview&&productSku(preview)}</p></div></DialogLayer>
 </div>;
}
