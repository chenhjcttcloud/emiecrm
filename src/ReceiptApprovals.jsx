import React, {useEffect, useRef, useState} from 'react';
import ReceiptProductDetails from './ReceiptProductDetails';
import {ReceiptVoucherGallery} from './ReceiptVoucherUpload';
import './ReceiptApprovals.css';
import './OrderDetails.css';
import './feishuApproval.css';
import {approvalInstanceUrl,approvalTime} from './feishuApprovalModel';
import {orderNumberForId} from './orderNumber';
import {receiptApprovalActive,receiptApprovalLabels,receiptApprovalCompleted,receiptApprovalCompletionKey} from './receiptApprovalModel';
export {receiptApprovalActive,receiptApprovalLabels} from './receiptApprovalModel';

const ROOT = 'feishu/receipt-approvals';
const money = value => '¥ ' + Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});
const time = value => value ? new Date(value).toLocaleString('sv-SE', {timeZone: 'Asia/Shanghai'}) : '—';
const delayFor = rows => rows.some(row => receiptApprovalActive(row) || row.cancelPending) ? 5000 : 60000;
const completionKey = receiptApprovalCompletionKey;

export function useReceiptApprovals(api, enabled, onStateChange) {
  const [config, setConfig] = useState(null);
  const [rows, setRows] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState({});
  const mounted = useRef(false);
  const generation = useRef(0);
  const inFlight = useRef(null);
  const operations = useRef(new Set());
  const version = useRef(0);
  const completed = useRef(new Set());
  const pollingDelay = useRef(60000);
  const onUpdate = useRef(onStateChange);
  const currentApi = useRef(api);
  onUpdate.current = onStateChange;
  currentApi.current = api;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; generation.current++; };
  }, []);

  useEffect(() => {
    if (!enabled) { setLoading(false); return; }
    let active = true;
    let running = false;
    let timer;
    const currentGeneration = ++generation.current;
    const current = () => active && mounted.current && currentGeneration === generation.current;

    async function load() {
      if (!current() || running) return;
      if (document.visibilityState === 'hidden') { clearTimeout(timer); timer=setTimeout(load,5000); return; }
      clearTimeout(timer);
      running = true;
      let pending;
      let nextDelay = pollingDelay.current;
      try {
        await inFlight.current;
        if (!current() || operations.current.size) return;
        const startedVersion = version.current;
        pending = (async () => {
          const results = await Promise.allSettled([api(ROOT + '/config'), api(ROOT)]);
          const failed = results.find(result => result.status === 'rejected');
          if (failed) throw failed.reason;
          if (!current() || startedVersion !== version.current) return;
          const [settings, entries] = results.map(result => result.value);
          if (typeof settings?.enabled !== 'boolean' || !Array.isArray(entries)) throw Error('收款审批配置返回无效，请重新加载');
          setConfig(settings);
          setRows(entries);
          setError('');
          nextDelay = delayFor(entries);
          pollingDelay.current = nextDelay;
          const nextCompleted = new Set(entries.filter(receiptApprovalCompleted).map(completionKey));
          if ([...nextCompleted].some(key => !completed.current.has(key))) {
            const next = await api('state');
            if (!current() || startedVersion !== version.current) return;
            onUpdate.current?.(next);
          }
          completed.current = nextCompleted;
        })().catch(failure => {
          if (current() && startedVersion === version.current) setError(failure.message || '收款审批读取失败');
        });
        inFlight.current = pending;
        await pending;
      } finally {
        if (inFlight.current === pending) inFlight.current = null;
        running = false;
        if (current()) {
          setLoading(false);
          timer = setTimeout(load, nextDelay);
        }
      }
    }

    load();
    const onFocus = () => { if (document.visibilityState !== 'hidden') load(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      active = false;
      clearTimeout(timer);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, [api, reload, enabled]);

  async function request(id, action, body = {}) {
    const key = id || body.requestId;
    if (operations.current.has(key)) return null;
    const startedApi = api;
    operations.current.add(key);
    version.current++;
    if (mounted.current) setBusy(previous => ({...previous, [key]: true}));
    try {
      const result = await api(id ? `${ROOT}/${encodeURIComponent(id)}/${action}` : ROOT, body);
      if (mounted.current && currentApi.current === startedApi) {
        if (result.approval) {
          setRows(previous => [result.approval, ...previous.filter(row => row.id !== result.approval.id)]);
          if (receiptApprovalCompleted(result.approval)) completed.current.add(completionKey(result.approval));
        }
        if (result.state) onUpdate.current?.(result.state);
        setError('');
      }
      return result;
    } finally {
      operations.current.delete(key);
      version.current++;
      if (mounted.current && currentApi.current === startedApi) {
        setBusy(previous => ({...previous, [key]: false}));
        setReload(value => value + 1);
      }
    }
  }

  return {config, rows, loading, error, busy, request, retry: () => setReload(value => value + 1)};
}

function Status({record}) {
  const waitingForBooking = record.status === 'APPROVED' && record.booked !== true;
  const tone = waitingForBooking ? 'warning' : record.status === 'APPROVED' ? 'cyan' : ['QUEUED', 'SUBMITTING', 'PENDING'].includes(record.status) ? 'green' : 'warning';
  return <span className={'badge badge-' + tone}>{record.cancelPending ? '撤回待确认' : waitingForBooking ? '审批通过，入账待处理' : receiptApprovalLabels[record.status] || '状态待核对'}</span>;
}

const nodeLabels = {APPROVED: '已通过', DONE: '已处理', PENDING: '待处理', UPCOMING: '后续节点', REJECTED: '已驳回', CANCELED: '已撤回', DELETED: '已删除', TRANSFERRED: '已转交', SKIPPED: '已跳过', ROLLBACK: '已退回'};
const eventLabels = {START: '发起审批', PASS: '同意', APPROVE: '同意', APPROVED: '审批通过', AUTO_PASS: '自动同意', REJECT: '驳回', REJECTED: '审批驳回', CANCEL: '撤回审批', CANCELED: '撤回审批', DELETE: '删除审批', DELETED: '删除审批', CC: '抄送', COMMENT: '添加审批意见', TRANSFER: '转交', ADD_APPROVER: '加签'};

function ReceiptApprovalFlow({api, record}) {
  const [value, setValue] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const key = JSON.stringify([record.id, record.instanceCode, record.status]);
  const flow = value?.key === key ? value.flow : null;
  useEffect(() => {
    let active = true, timer;
    async function load() {
      setLoading(true); setError('');
      try {
        const result=await api(`${ROOT}/${encodeURIComponent(record.id)}/flow`);
        if(active)setValue({key,flow:result});
      } catch(failure) {if(active)setError(failure.message||'审批流程读取失败');}
      finally {
        if(active){setLoading(false);if(receiptApprovalActive(record))timer=setTimeout(load,30000);}
      }
    }
    void load();
    return()=>{active=false;clearTimeout(timer)};
  }, [api, key, reload]);
  return <section className="feishu-flow" aria-label="收款审批流程">
    <div className="feishu-flow-heading"><h3>审批流程</h3><button type="button" className="link-btn" disabled={loading} onClick={() => setReload(value => value + 1)}>刷新流程</button></div>
    {loading && <p className="order-detail-empty" role="status">正在读取审批流程…</p>}
    {error && <p className="system-error" role="alert">{error}</p>}
    {flow && ['UNAVAILABLE', 'PARTIAL'].includes(flow.previewStatus) && <p className="order-detail-empty">飞书暂未返回完整的后续节点。</p>}
    {flow && !flow.nodes?.length && !flow.timeline?.length && !loading && !error && <p className="order-detail-empty">{record.instanceCode ? '暂无审批节点记录。' : '审批尚未成功发起，请核对提交状态。'}</p>}
    {Boolean(flow?.nodes?.length) && <ol className="feishu-flow-nodes" aria-label="审批节点">{flow.nodes.map(node => <li key={node.id} data-status={node.status}>
      <div className="feishu-node-heading"><strong>{node.name || '审批节点'}</strong><span className="order-detail-node-status">{nodeLabels[node.status] || '状态待确认'}</span></div>
      {Boolean(node.tasks?.length) && <ul className="receipt-approval-tasks" aria-label="节点审批人">{node.tasks.map(task => <li key={task.id}><span>{task.name || '姓名未返回'}</span><span>{nodeLabels[task.status] || '状态待确认'}</span>{Number(task.finishedAt || task.startedAt) > 0 && <span>{approvalTime(task.finishedAt || task.startedAt)}</span>}</li>)}</ul>}
    </li>)}</ol>}
    {Boolean(flow?.timeline?.length) && <div className="receipt-approval-history"><h4>审批动态</h4><ol aria-label="审批动态">{flow.timeline.map(event => <li key={event.id}><div><strong>{eventLabels[event.type] || '审批记录'}</strong>{event.name && <span>{event.name}</span>}{Number(event.at) > 0 && <span>{approvalTime(event.at)}</span>}</div>{event.comment && <p>{event.comment}</p>}</li>)}</ol></div>}
  </section>;
}

function ApprovalDetails({record, api, approvals, DialogLayer, Icon, onClose, orders}) {
  const [error, setError] = useState('');
  const busy = Boolean(approvals.busy[record.id]);
  async function act(action) {
    if (busy) return;
    if (action === 'cancel' && !window.confirm('撤回这笔收款审批？撤回后不会计入已收金额。')) return;
    setError('');
    try { await approvals.request(record.id, action); }
    catch (failure) { setError(failure.message || '操作失败，请核对审批状态'); }
  }
  return <DialogLayer kind="drawer" open label="收款审批详情" onClose={onClose}>
    <div className="drawer receipt-detail-drawer receipt-approval-drawer order-detail-drawer">
      <div className="modal-title"><h2>收款审批详情</h2><button type="button" aria-label="关闭收款审批详情" onClick={onClose}><Icon name="close"/></button></div>
      <div className="modal-body">
        <div className="receipt-detail-heading"><div><span>本次申请收款</span><strong>{money(record.amount)}</strong></div><Status record={record}/></div>
        <p className="receipt-approval-notice">审批通过后计入订单已收金额。收款审批不触发聚水潭发货。</p>
        {(record.message || record.bookingError) && <p className="system-error" role="status">{record.bookingError || record.message}</p>}
        <dl className="receipt-detail-fields">{[['客户名称', record.customer], ['收款方式', record.method], ['收款发生日期', record.receiptDate], ['登记人', record.operator], ['提交时间', time(record.createdAt)]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
        {record.allocations?.map(allocation => <section className="receipt-approval-allocation" key={allocation.orderId}>
          <div className="receipt-approval-allocation-heading"><h3>{allocation.orderNumber||orderNumberForId(allocation.orderId,orders)}</h3><span>本次分配 <strong>{money(allocation.amount)}</strong></span></div>
          <ReceiptProductDetails items={allocation.items || []} source="snapshot"/>
        </section>)}
        {record.note && <section><h3>备注</h3><p className="order-detail-note">{record.note}</p></section>}
        <ReceiptVoucherGallery attachments={record.attachments} empty/>
        <ReceiptApprovalFlow api={api} record={record}/>
        {error && <p className="system-error" role="alert">{error}</p>}
      </div>
      <div className="modal-footer">
        <button type="button" className="secondary" onClick={onClose}>关闭</button>
        {approvalInstanceUrl(record.instanceCode)&&<a className="secondary receipt-approval-feishu-link" href={approvalInstanceUrl(record.instanceCode)} target="_blank" rel="noreferrer">在飞书查看</a>}
        {receiptApprovalActive(record) && <button type="button" className="secondary" disabled={busy} onClick={() => act('sync')}>{busy ? '处理中…' : record.status === 'APPROVED' ? '重试入账' : '同步审批状态'}</button>}
        {record.canCancel && <button type="button" className="secondary" disabled={busy} onClick={() => act('cancel')}>{record.cancelPending ? '核对撤回结果' : '撤回申请'}</button>}
        {record.canRetryOriginal && <button type="button" className="primary" disabled={busy} onClick={() => act('retry')}>重试原申请</button>}
      </div>
    </div>
  </DialogLayer>;
}

export default function ReceiptApprovalPanel({approvals, api, DialogLayer, Icon, orders=[]}) {
  const [viewingId, setViewingId] = useState(null);
  const viewing = approvals.rows.find(row => row.id === viewingId);
  if (!approvals.config?.enabled && !approvals.rows.length && !approvals.error && !approvals.loading) return null;
  const active = approvals.rows.filter(receiptApprovalActive).length;
  return <>
    <section className="panel receipt-approval-panel" aria-label="收款审批">
      <div className="receipt-approval-panel-heading"><div><h2>收款审批{active > 0 && <span>{active} 笔处理中</span>}</h2><p>审批通过后入账，审批中的金额不计入下方收款流水。</p></div><button type="button" className="secondary" onClick={approvals.retry} disabled={approvals.loading}>刷新</button></div>
      {approvals.error && <p className="system-error" role="alert">{approvals.error} <button type="button" className="link-btn" onClick={approvals.retry}>重新加载</button></p>}
      <div className="table-scroll"><table className="data-table"><thead><tr>{['客户名称', '本次收款', '关联订单', '收款日期', '审批状态', '登记人', '操作'].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{approvals.rows.map(record => <tr key={record.id}>
        <td><span className="badge badge-blue">{record.customer}</span></td><td>{money(record.amount)}</td><td><span className="receipt-approval-orders">{(record.allocations || []).map(row => row.orderNumber||orderNumberForId(row.orderId,orders)).join('、')}</span></td><td>{record.receiptDate || '—'}</td><td><Status record={record}/></td><td>{record.operator || '—'}</td><td><button type="button" className="link-btn" aria-label={'查看收款审批 ' + record.id} onClick={() => setViewingId(record.id)}>查看详情</button></td>
      </tr>)}</tbody></table>{!approvals.rows.length && !approvals.error && <div className="empty" role="status">{approvals.loading ? '正在读取收款审批…' : '暂无收款审批申请'}</div>}</div>
    </section>
    {viewing && <ApprovalDetails key={viewing.id} record={viewing} api={api} approvals={approvals} orders={orders} DialogLayer={DialogLayer} Icon={Icon} onClose={() => setViewingId(null)}/>}
  </>;
}
