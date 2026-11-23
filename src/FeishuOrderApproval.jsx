import React, {useEffect, useRef, useState} from 'react';
import OrderSelect from './OrderSelect';
import {approvalInstanceUrl, approvalLabels, approvalStatus, approvalTime, canSubmitApproval,
  completedApproval, isApprovalOrder, isSalesApprovalOrder, submissionProblem} from './feishuApprovalModel';
import './feishuApproval.css';

const pollingDelay = rows => rows.some(row => ['QUEUED', 'SUBMITTING'].includes(row.status)) ? 5000 : 30000;

export function useFeishuApprovals(api, active, onStateUpdate) {
  const [config, setConfig] = useState(null);
  const [records, setRecords] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState({});
  const [reload, setReload] = useState(0);
  const operations = useRef(new Set());
  const version = useRef(0);
  const completed = useRef(new Set());
  const onState = useRef(onStateUpdate);
  onState.current = onStateUpdate;

  useEffect(() => {
    if (!active) return;
    let current = true, loading = false, timer;
    async function load() {
      if (!current || loading) return;
      clearTimeout(timer);
      if (operations.current.size || document.visibilityState === 'hidden') {
        timer = setTimeout(load, 5000);
        return;
      }
      loading = true;
      const requestedVersion = version.current;
      let delay = 30000;
      try {
        const results = await Promise.allSettled([api('feishu/approvals/config'), api('feishu/approvals')]);
        const failure = results.find(result => result.status === 'rejected');
        if (failure) throw failure.reason;
        if (!current || requestedVersion !== version.current) return;
        const [settings, rows] = results.map(result => result.value);
        delay = pollingDelay(rows);
        setConfig(settings);
        setRecords(Object.fromEntries(rows.map(row => [row.orderId, row])));
        setError('');
        const finished = new Set(rows.filter(completedApproval).map(row => `${row.orderId}:${row.instanceCode}:${row.status}`));
        if ([...finished].some(key => !completed.current.has(key))) {
          const next = await api('state');
          if (!current || requestedVersion !== version.current) return;
          onState.current(next);
        }
        completed.current = finished;
      } catch (failure) {
        if (current && requestedVersion === version.current) setError(failure.message || '读取飞书审批失败');
      } finally {
        loading = false;
        if (current) timer = setTimeout(load, delay);
      }
    }
    void load();
    window.addEventListener('focus', load);
    document.addEventListener('visibilitychange', load);
    return () => {
      current = false;
      clearTimeout(timer);
      window.removeEventListener('focus', load);
      document.removeEventListener('visibilitychange', load);
    };
  }, [api, active, reload]);

  function remember(record) {
    version.current++;
    setRecords(previous => ({...previous, [record.orderId]: record}));
    setReload(count => count + 1);
  }

  async function request(orderId, action, body = {}) {
    if (operations.current.has(orderId)) return null;
    operations.current.add(orderId);
    version.current++;
    setBusy(previous => ({...previous, [orderId]: true}));
    try {
      const result = await api(action === 'delete' ? `orders/${encodeURIComponent(orderId)}/delete` : `feishu/approvals/orders/${encodeURIComponent(orderId)}/${action}`, body);
      if (action === 'delete') {onState.current(result);return result;}
      const record = ['create-and-submit', 'cancel'].includes(action) ? result.approval : result;
      if (record) remember(record);
      if (result.state) onState.current(result.state);
      else {
        try { onState.current(await api('state')); }
        catch { setError('审批结果已记录，订单状态刷新失败，请刷新页面。'); }
      }
      return result;
    } finally {
      operations.current.delete(orderId);
      version.current++;
      setBusy(previous => ({...previous, [orderId]: false}));
      setReload(count => count + 1);
    }
  }
  return {config, records, error, busy, request, remember, retry: () => setReload(count => count + 1)};
}

export function ApprovalStatus({order, record, config, error, Badge}) {
  if (order.status === '已取消' || record?.cancelPending) return <Badge tone="warning">{order.status === '已取消' ? '已取消' : '取消待确认'}</Badge>;
  const pending = ['QUEUED', 'SUBMITTING', 'PENDING'].includes(record?.status);
  const warning = ['FAILED', 'UNKNOWN', 'REJECTED', 'CANCELED', 'DELETED'].includes(record?.status) ||
    (!record && isApprovalOrder(order) && order.status === '审批中');
  return <Badge tone={pending ? 'green' : warning ? 'warning' : 'cyan'}>{approvalStatus(order, record, config, error)}</Badge>;
}

export function ApprovalActions({order, approvals, onSubmit, onMessage, canWrite}) {
  const record = approvals.records[order.id];
  if (!isApprovalOrder(order)) return null;
  const busy = approvals.busy[order.id];
  async function synchronize(body = {}) {
    try {
      const result = await approvals.request(order.id, body.retryOriginal ? 'submit' : 'sync', body);
      if (result) onMessage(approvalLabels[result.status] || '审批状态已更新');
    } catch (failure) { onMessage(failure.message); }
  }
  return <span className="feishu-row-actions">
    {canWrite && canSubmitApproval(order, record, approvals.config) && !approvals.error &&
      <button type="button" className="link-btn" disabled={busy} onClick={onSubmit}>{record ? '重新发起审批' : '发起飞书审批'}</button>}
    {record && <button type="button" className="link-btn" disabled={busy}
      onClick={() => synchronize()}>{busy ? '同步中…' : '同步审批'}</button>}
    {canWrite && record?.status === 'UNKNOWN' && record.canRetryOriginal && record.originalVersion &&
      <button type="button" className="link-btn" disabled={busy}
        onClick={() => synchronize({retryOriginal: true, originalVersion: record.originalVersion})}>重试原审批</button>}
    {approvalInstanceUrl(record?.instanceCode) && <a className="link-btn" target="_blank" rel="noreferrer"
      href={approvalInstanceUrl(record.instanceCode)}>查看飞书审批</a>}
  </span>;
}

export function ApprovalFormFields({order, config, disabled, customer, products = [], onChange, onDiscountChange}) {
  const draft = order.approvalDraft ?? {};
  const change = (key, value) => onChange?.({...draft, [key]: value});
  if (isSalesApprovalOrder(order) && (config?.salesRecipientRequired || config?.salesDiscountRequired)) return <section className="feishu-approval-extra" aria-label="飞书审批信息">
    <h3>飞书审批信息</h3>
    {config.salesRecipientRequired && <>
      <div className="feishu-recipient-grid">
        <label className="field"><span>收货人 <em>*</em></span>
          <input type="text" name="recipientName" aria-label="收货人" required maxLength={100} disabled={disabled}
            value={draft.recipientName ?? customer?.contact ?? ''} onChange={event => change('recipientName', event.target.value)}/>
        </label>
        <label className="field"><span>收货电话 <em>*</em></span>
          <input type="tel" name="recipientPhone" aria-label="收货电话" required maxLength={50} disabled={disabled}
            value={draft.recipientPhone ?? customer?.phone ?? ''} onChange={event => change('recipientPhone', event.target.value)}/>
        </label>
      </div>
      <label className="field"><span>收货地址 <em>*</em></span>
        <textarea name="recipientAddress" aria-label="收货地址" required maxLength={500} disabled={disabled}
          value={draft.recipientAddress ?? ''} onChange={event => change('recipientAddress', event.target.value)} placeholder="请输入完整收货地址"/>
      </label>
    </>}
    {config.salesDiscountRequired && <>
      <div className="feishu-discount-heading"><h4>商品折扣</h4><span>默认0，可修改</span></div>
      <div className="feishu-discounts">{(order.items ?? []).map((item, index) => {
        const product = products.find(entry => entry.id === item.product);
        return <label className="field" key={index}><span>{index + 1}. {product?.name || '商品'} · 折扣</span>
          <input type="number" name={'approvalDiscount-' + index} aria-label={'商品折扣（第' + (index + 1) + '行）'} min="0" max="999999999999.99" step="any" placeholder="0" disabled={disabled}
            value={item.approvalDiscount ?? '0'} onChange={event => onDiscountChange?.(index, event.target.value)}/>
        </label>;
      })}</div>
      <p className="form-hint">折扣仅填写到飞书审批，不影响CRM订单总额；订单总额仍按数量和销售价计算。</p>
    </>}
  </section>;
  if (order.type !== '预收款订单' || !config?.prepaymentReceiptForm) return null;
  return <div className="feishu-approval-extra" key={order.id || 'new'}>
    <label className="field"><span>收款发生日期 <em>*</em></span>
      <input type="date" name="receiptDate" aria-label="收款发生日期" required disabled={disabled}
        value={draft.receiptDate || ''} onChange={event => change('receiptDate', event.target.value)}/>
    </label>
    <OrderSelect name="receiptCategory" label="收款类别" required disabled={disabled} placeholder="请选择收款类别"
      value={draft.receiptCategory || ''} onChange={value => change('receiptCategory', value)}
      options={(config.prepaymentReceiptCategories ?? []).map(value => ({value, label: value}))}/>
    <p className="form-hint">请确认本次审批的发生日期和类别，审批通过后仍需登记实际到账。</p>
  </div>;
}

const nodeLabels = {APPROVED: '已通过', DONE: '已处理', PENDING: '审批中', UPCOMING: '待审批',
  REJECTED: '已驳回', CANCELED: '已撤回', DELETED: '已删除', TRANSFERRED: '已转交',
  ROLLBACK: '已退回', SKIPPED: '已跳过'};
const eventLabels = {START: '发起审批', PASS: '同意', APPROVE: '同意', APPROVED: '审批通过',
  AUTO_PASS: '自动同意', REJECT: '驳回', REJECTED: '审批驳回', DISAGREE: '不同意', AUTO_REJECT: '自动驳回',
  CANCEL: '撤回审批', CANCELED: '撤回审批', DELETE: '删除审批', DELETED: '删除审批', COMMENT: '添加意见',
  TRANSFER: '转交', TRANSFERRED: '转交', ADD_APPROVER: '加签', ADD_APPROVER_BEFORE: '前加签',
  ADD_APPROVER_AFTER: '后加签', DELETE_APPROVER: '移除审批人', ROLLBACK: '退回',
  ROLLBACK_SELECTED: '退回指定节点', REVOKE: '撤销', CC: '抄送', MODIFY: '修改申请'};

export function ApprovalFlow({order, approvals, api, Badge}) {
  const record = approvals.records[order.id];
  const [loaded, setLoaded] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const flowKey = JSON.stringify([order.id, record?.instanceCode, record?.status]);
  const flow = loaded?.key === flowKey ? loaded.value : null;
  const supported = isApprovalOrder(order);
  useEffect(() => {
    if (!supported || !order.id || !record?.instanceCode) return;
    let current = true, timer;
    async function load() {
      setLoading(true);
      setError('');
      try {
        const result = await api(`feishu/approvals/orders/${encodeURIComponent(order.id)}/flow`);
        if (current) setLoaded({key: flowKey, value: result});
      } catch (failure) {
        if (current) setError(failure.message || '读取飞书审批流程失败');
      } finally {
        if (current) {
          setLoading(false);
          if (['PENDING', 'SUBMITTING', 'UNKNOWN'].includes(record?.status)) timer = setTimeout(load, 30000);
        }
      }
    }
    void load();
    return () => { current = false; clearTimeout(timer); };
  }, [api, supported, flowKey, reload]);

  if (!supported) return null;
  const nodes = flow?.nodes ?? [], timeline = flow?.timeline ?? [];
  const problem = submissionProblem(approvals.config, order, approvals.error);
  return <section className="feishu-flow" aria-label="飞书审批流程">
    <div className="feishu-flow-heading"><h3>飞书审批流程</h3>
      <ApprovalStatus order={order} record={record} config={approvals.config} error={approvals.error} Badge={Badge}/>
    </div>
    {order.status === '已取消' && <p className="feishu-flow-hint">订单已取消，不再发起审批。已有审批记录保留供查阅。</p>}
    {order.status !== '已取消' && (!record || (!record.instanceCode && problem)) && <p className="feishu-flow-hint" role="status">{problem || (order.id ? '尚未发起飞书审批。提交后会按飞书模板流转，并同步审批结果。' : '保存草稿不会发起审批；提交后会在此显示审批人和流程。')}</p>}
    {record?.message && <p className="feishu-flow-error" role="alert">{record.message}</p>}
    {record && !record.instanceCode && <p className="feishu-flow-hint" role="status">
      {record.status === 'UNKNOWN' ? '提交结果待核对，系统会沿用原审批编号恢复，请勿另建订单重复提交。' :
        record.status === 'FAILED' ? '审批尚未成功发起，请核对错误信息后重新提交。' : '订单已保存，正在发起飞书审批…'}
    </p>}
    {record?.instanceCode && <div className="feishu-flow-tools">
      <a href={approvalInstanceUrl(record.instanceCode)} target="_blank" rel="noreferrer">在飞书中查看</a>
      <button type="button" className="link-btn" disabled={loading} onClick={() => setReload(count => count + 1)}>{loading ? '读取中…' : '刷新流程'}</button>
    </div>}
    {loading && !flow && <p className="feishu-flow-hint" role="status">正在读取审批人和流程…</p>}
    {error && <p className="feishu-flow-error" role="alert">{error} <button type="button" className="link-btn" onClick={() => setReload(count => count + 1)}>重试</button></p>}
    {Boolean(nodes.length) && <ol className="feishu-flow-nodes" aria-label="审批节点">
      {nodes.map(node => <li key={node.id} data-status={node.status}>
        <div className="feishu-node-heading"><strong>{node.name || '审批节点'}</strong><span>{nodeLabels[node.status] || '状态待核对'}</span></div>
        {node.tasks?.length ? <ul aria-label="节点审批人">{node.tasks.map(task => <li key={task.id}>
          <span className="feishu-approver-avatar" aria-hidden="true">{(task.name || '?').slice(-1)}</span>
          <span>{task.name || '飞书暂未返回姓名'}<small>{nodeLabels[task.status] || '状态待核对'}</small></span>
          <time>{approvalTime(task.finishedAt || task.startedAt)}</time>
        </li>)}</ul> : <p className="feishu-flow-hint">审批人由飞书流程确定</p>}
      </li>)}
    </ol>}
    {flow && ['UNAVAILABLE', 'PARTIAL'].includes(flow.previewStatus) && <p className="feishu-flow-hint">飞书暂未返回完整的后续节点，流程以飞书审批为准。</p>}
    {flow && !loading && !error && !nodes.length && !timeline.length && <p className="feishu-flow-hint">飞书暂未返回审批节点。</p>}
    {Boolean(timeline.length) && <details className="feishu-flow-timeline" open><summary>审批动态</summary>
      <ol>{timeline.map(event => <li key={event.id}><div><strong>{eventLabels[event.type] || event.type || '审批记录'}</strong>
        {event.name && <span>{event.name}</span>}<time>{approvalTime(event.at)}</time></div>{event.comment && <p>{event.comment}</p>}</li>)}</ol>
    </details>}
    {Boolean(record?.history?.length) && <details className="feishu-flow-history"><summary>历史审批（{record.history.length}）</summary>
      <ol>{record.history.map((entry, index) => <li key={entry.instanceCode || index}>
        <span>{approvalLabels[entry.status] || '历史审批'}</span><time>{approvalTime(entry.updatedAt || entry.createdAt)}</time>
        {approvalInstanceUrl(entry.instanceCode) && <a href={approvalInstanceUrl(entry.instanceCode)} target="_blank" rel="noreferrer">查看</a>}
      </li>)}</ol>
    </details>}
  </section>;
}
