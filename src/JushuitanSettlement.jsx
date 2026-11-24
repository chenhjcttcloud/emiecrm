import React, { useEffect, useRef, useState } from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';
import Pagination from './Pagination';
import { customerOptions } from './customerOptions';
import { moneyCents } from './orderMoney';
import { submissionProblem } from './feishuApprovalModel';
import { createSettlementApprovalRequest, submitSettlementApproval } from './settlementApprovalSubmission';

const statusLabels = { Sent: '已发货', Archive: '已归档', Cancelled: '已取消' };
const positiveId = /^[1-9][0-9]{0,18}$/;
const validShopId = /^(?:0|[1-9][0-9]{0,18})$/;
const formatMoney = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const saleAmount = amount => amount == null ? '—' : `¥ ${formatMoney.format(amount)}`;
export const saleRef = sale => ({ shopId: sale.shopId, oId: sale.oId ?? sale.id });

function currentDate() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
}

function monthPeriod(month) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) return ['', ''];
  const [year, value] = month.split('-').map(Number);
  const days = new Date(Date.UTC(year, value, 0)).getUTCDate();
  return [`${month}-01`, `${month}-${days}`];
}

export function SaleBatchInput({ sales, disabled, onAdd }) {
  const [shopId, setShopId] = useState('');
  const [numbers, setNumbers] = useState('');
  const [error, setError] = useState('');

  function addBatch() {
    setError('');
    const shop = shopId.trim();
    const ids = numbers.trim().split(/[\s,，、;；]+/).filter(Boolean);
    if (!validShopId.test(shop) || !ids.length || ids.some(id => !positiveId.test(id))) {
      setError('请填写非负整数店铺编号（线下为0）和正整数聚水潭内部订单号。');
      return;
    }
    const existingIds = new Set(sales.map(sale => saleRef(sale).oId));
    if (new Set(ids).size !== ids.length || ids.some(id => existingIds.has(id))) {
      setError('同一内部订单号只能添加一次。');
      return;
    }
    if (sales.length + ids.length > 500) {
      setError('每张结算单最多关联 500 张销售单。');
      return;
    }
    onAdd(ids.map(oId => ({ shopId: shop, oId })));
    setNumbers('');
  }

  return <div className="settlement-batch">
    <div className="settlement-batch-inputs">
      <label className="field"><span>聚水潭店铺编号</span><input inputMode="numeric" maxLength={19}
        value={shopId} disabled={disabled} onChange={event => { setShopId(event.target.value); setError(''); }} /><small>线下店铺编号为 0；内部订单号须大于 0。</small></label>
      <label className="field"><span>聚水潭内部订单号</span><textarea rows={2} maxLength={11000} value={numbers}
        placeholder="多个单号可用换行或逗号分隔" disabled={disabled}
        onChange={event => { setNumbers(event.target.value); setError(''); }} /></label>
    </div>
    {error && <p role="alert">{error}</p>}
    <button type="button" className="secondary" disabled={disabled || !shopId.trim() || !numbers.trim()}
      onClick={addBatch}>加入待核对列表</button>
  </div>;
}

export function SettlementSalesTable({ sales, selected, onSelect, onRemove, disabled = false }) {
  return <div className="table-scroll">
    <table className="ledger settlement-sales">
      <thead><tr>
        {onSelect && <th scope="col">选择</th>}
        <th scope="col">聚水潭内部单号</th><th scope="col">店铺</th>
        <th scope="col">发货日</th><th scope="col">发货仓</th><th scope="col">状态</th>
        <th scope="col">聚水潭金额</th>{onRemove && <th scope="col">操作</th>}
      </tr></thead>
      <tbody>{sales.map(sale => {
        const { oId, shopId } = saleRef(sale);
        return <tr key={`${shopId}-${oId}`}>
          {onSelect && <td><input type="checkbox" aria-label={`选择销售单 ${oId}`}
            checked={selected.has(oId)} disabled={disabled} onChange={event => onSelect(oId, event.target.checked)} /></td>}
          <td>{oId}{sale.soId && <small>线上单号 {sale.soId}</small>}
            {!!sale.items?.length && <details className="jst-document-items settlement-sale-items">
              <summary>商品明细（{sale.items.length} 行）</summary>
              <ul>{sale.items.map((item, index) => <li key={`${item.sku}-${index}`}>
                {item.name || item.sku || '—'}{item.quantity != null && ` × ${item.quantity}`}
                {item.name && item.sku && <small>{item.sku}</small>}
              </li>)}</ul>
            </details>}
          </td><td>{shopId}</td>
          <td>{sale.shippedOn || '—'}</td><td>{sale.warehouseName || sale.warehouseId || '—'}</td>
          <td>{statusLabels[sale.status] || sale.status || '待查询'}</td><td>{saleAmount(sale.amount)}</td>
          {onRemove && <td><button type="button" className="link-btn" disabled={disabled}
            aria-label={`移除销售单 ${oId}`} onClick={() => onRemove(oId)}>移除</button></td>}
        </tr>;
      })}</tbody>
    </table>
  </div>;
}

export function salesAmountCents(sales) {
  let total = 0;
  for (const sale of sales) {
    const cents = moneyCents(sale.amount);
    if (cents == null || !Number.isSafeInteger(total + cents)) return null;
    total += cents;
  }
  return total;
}

function SettlementForm({ api, customers, Icon, onClose, onCreated, pending, attempt, approvals }) {
  const today = currentDate();
  const [cycle, setCycle] = useState('day');
  const [start, setStart] = useState(today);
  const [end, setEnd] = useState(today);
  const [month, setMonth] = useState('');
  const [customerId, setCustomerId] = useState('');
  const [shops, setShops] = useState([]);
  const [shopsLoading, setShopsLoading] = useState(true);
  const [shopError, setShopError] = useState('');
  const [shopAttempt, setShopAttempt] = useState(0);
  const [shopId, setShopId] = useState('');
  const [searchStart, setSearchStart] = useState('');
  const [searchEnd, setSearchEnd] = useState('');
  const [candidates, setCandidates] = useState(null);
  const [candidatePage, setCandidatePage] = useState(1);
  const [selected, setSelected] = useState(new Map());
  const [total, setTotal] = useState('');
  const [adjustmentReason, setAdjustmentReason] = useState('');
  const [note, setNote] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [action, setAction] = useState('');
  const [error, setError] = useState('');
  const [unconfirmed, setUnconfirmed] = useState(false);
  const busy = Boolean(action) || unconfirmed;
  const approvalProblem = submissionProblem(approvals.config, {type: '销售订单', source: 'jushuitan-settlement'}, approvals.error);
  const [periodStart, periodEnd] = cycle === 'month' ? monthPeriod(month) : [start, end];
  const periodValid = periodStart && periodEnd && periodStart <= periodEnd && periodEnd <= today;
  const invalidPeriod = periodStart && periodEnd && !periodValid;
  const queryStart = searchStart || periodStart;
  const queryEnd = searchEnd || periodEnd;
  const days = (Date.parse(queryEnd) - Date.parse(queryStart)) / 86400000 + 1;
  const queryValid = periodValid && queryStart >= periodStart && queryEnd <= periodEnd && days >= 1 && days <= 31;
  const chosen = [...selected.values()];

  useEffect(() => {
    let active = true;
    setShopsLoading(true);
    setShopError('');
    api('jst/settlements/shops').then(rows => {
      if (active) setShops(rows);
    }).catch(problem => {
      if (active) setShopError(problem.message);
    }).finally(() => {
      if (active) setShopsLoading(false);
    });
    return () => { active = false; };
  }, [api, shopAttempt]);
  const erpCents = salesAmountCents(chosen);
  const totalCents = moneyCents(total);
  const requiresReason = erpCents == null || totalCents !== erpCents;
  const canCreate = Boolean(customerId && chosen.length && totalCents > 0 && confirmed &&
    (!requiresReason || adjustmentReason.trim()));

  function clearCandidates() {
    setCandidates(null);
    setCandidatePage(1);
    setError('');
  }

  function changePeriod() {
    setSearchStart('');
    setSearchEnd('');
    clearCandidates();
    setSelected(new Map());
    setTotal('');
    setAdjustmentReason('');
    setConfirmed(false);
  }

  function selectSale(sale, checked) {
    if (checked && !selected.has(sale.id) && selected.size >= 500) {
      setError('每张结算单最多关联 500 张销售单。');
      return;
    }
    const next = new Map(selected);
    if (checked) next.set(sale.id, sale);
    else next.delete(sale.id);
    setSelected(next);
    const cents = salesAmountCents([...next.values()]);
    setTotal(next.size && cents != null ? String(cents / 100) : '');
    setAdjustmentReason('');
    setConfirmed(false);
    setError('');
  }

  async function loadCandidates() {
    if (pending.current || !queryValid || !shopId) return;
    pending.current = true;
    clearCandidates();
    setAction('search');
    try {
      const rows = await api('jst/settlements/candidates', {
        cycle, periodStart, periodEnd, shopId, searchStart: queryStart, searchEnd: queryEnd,
      });
      setCandidates(rows);
    } catch (problem) {
      setError(problem.message);
    } finally {
      pending.current = false;
      setAction('');
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (!canCreate || pending.current || (!unconfirmed && approvalProblem)) return;
    pending.current = true;
    setAction('create');
    setError('');
    try {
      attempt.current ??= createSettlementApprovalRequest({
        cycle, periodStart, periodEnd, sales: chosen.map(saleRef), customerId,
        total, expectedErpAmount: erpCents == null ? null : erpCents / 100,
        adjustmentReason: requiresReason ? adjustmentReason : '', note, customerConfirmed: true,
      });
      const result = await submitSettlementApproval(api, attempt.current);
      attempt.current = null;
      setUnconfirmed(false);
      onCreated(result);
    } catch (problem) {
      setUnconfirmed(Boolean(problem.uncertain));
      if (!problem.uncertain) attempt.current = null;
      setError(problem.message);
    } finally {
      pending.current = false;
      setAction('');
    }
  }

  return <form className="drawer settlement-drawer" onSubmit={submit}>
    <div className="modal-title"><h2>聚水潭结算补录</h2>
      <button type="button" aria-label="关闭结算补录" disabled={busy} onClick={onClose}><Icon name="close" /></button>
    </div>
    <div className="modal-body jst-form" aria-busy={Boolean(action)}>
      <p className="form-hint">结算补录须经飞书审批通过后生效；审批内容包含结算账期、金额和关联聚水潭销售单。</p>
      {approvalProblem && <p role="alert">{approvalProblem}</p>}
      <div className="field"><span>付款客户</span><PaymentMethodPicker label="付款客户" value={customerId}
        options={customerOptions(customers)} disabled={busy}
        onChange={value => { setCustomerId(value); setConfirmed(false); setError(''); }} /></div>
      <div className="settlement-field-row">
        <label className="field"><span>结算方式</span><select value={cycle} disabled={busy}
          onChange={event => { setCycle(event.target.value); changePeriod(); }}>
          <option value="day">日结</option><option value="month">月结</option>
        </select></label>
        {cycle === 'month'
          ? <label className="field"><span>发货账期</span><input type="month" required value={month}
              max={today.slice(0, 7)} disabled={busy} onChange={event => { setMonth(event.target.value); changePeriod(); }} /></label>
          : <><label className="field"><span>发货开始日</span><input type="date" required value={start}
                max={today} disabled={busy} onChange={event => { setStart(event.target.value); changePeriod(); }} /></label>
              <label className="field"><span>发货结束日</span><input type="date" required value={end}
                min={start} max={today} disabled={busy} onChange={event => { setEnd(event.target.value); changePeriod(); }} /></label></>}
      </div>
      {invalidPeriod && <p role="alert">{cycle === 'month'
        ? '月结账期须为已结束的自然月。'
        : '发货结束日须在开始日与今天之间。'}</p>}
      <section className="jst-document-section">
        <h3>聚水潭销售单</h3>
        <div className="field"><span>聚水潭店铺</span>
          <PaymentMethodPicker label="聚水潭店铺" value={shopId}
            options={shops.map(shop => ({ value: String(shop.id), label: Number(shop.id) === 0 ? `${shop.name || '线下'}（编号 0）` : shop.name || String(shop.id), keywords: String(shop.id) }))}
            disabled={busy || shopsLoading} onChange={value => { setShopId(value); clearCandidates(); }} />
        </div>
        {shopsLoading && <p role="status">正在加载店铺…</p>}
        {shopError && <p role="alert">{shopError} <button type="button" className="link-btn" disabled={busy}
          onClick={() => setShopAttempt(value => value + 1)}>重新加载店铺</button></p>}
        {!shopsLoading && !shopError && !shops.length && <p>聚水潭没有返回可选店铺。</p>}
        {periodValid && <details open={days > 31 ? true : undefined}>
          <summary>缩小查询日期范围</summary>
          <div className="settlement-field-row">
            <label className="field"><span>查询开始日</span><input type="date" value={queryStart} min={periodStart}
              max={periodEnd} disabled={busy} onChange={event => { setSearchStart(event.target.value); clearCandidates(); }} /></label>
            <label className="field"><span>查询结束日</span><input type="date" value={queryEnd} min={queryStart}
              max={periodEnd} disabled={busy} onChange={event => { setSearchEnd(event.target.value); clearCandidates(); }} /></label>
          </div>
          {!queryValid && <p role="alert">查询日期须在发货账期内，每次最多查询 31 天。</p>}
        </details>}
        <div className="settlement-action-row">
          <button type="button" className="secondary" onClick={loadCandidates} disabled={busy || !queryValid || !shopId}>
            {action === 'search' ? '正在查询…' : '查询可选销售单'}
          </button>
        </div>
        {candidates?.length > 0 && <>
          <SettlementSalesTable sales={candidates.slice((candidatePage - 1) * 10, candidatePage * 10)}
            selected={selected} disabled={busy}
            onSelect={(id, checked) => selectSale(candidates.find(sale => sale.id === id), checked)} />
          <Pagination total={candidates.length} page={candidatePage} pageSize={10}
            onPage={setCandidatePage} label="可选销售单分页" />
        </>}
        {candidates?.length === 0 && <p>该店铺在所选日期内没有可选销售单。</p>}
        {!!chosen.length && <details>
          <summary>查看已选销售单（{chosen.length} 张）</summary>
          <SettlementSalesTable sales={chosen} disabled={busy}
            onRemove={id => selectSale(selected.get(id), false)} />
        </details>}
      </section>
      {!!chosen.length && <>
        <div className="read-field">所选 {chosen.length} 张销售单 · 聚水潭合计 <strong>{saleAmount(erpCents == null ? null : erpCents / 100)}</strong></div>
        <label className="field"><span>确认结算金额</span><input required inputMode="decimal" value={total} disabled={busy}
          onChange={event => setTotal(event.target.value)} /></label>
        {requiresReason && <label className="field"><span>差额说明</span><textarea required rows={2} maxLength={500}
          value={adjustmentReason} disabled={busy} onChange={event => setAdjustmentReason(event.target.value)} /></label>}
        <label className="field"><span>备注</span><textarea rows={2} maxLength={500} value={note}
          disabled={busy} onChange={event => setNote(event.target.value)} /></label>
        <label className="settlement-confirm"><input type="checkbox" checked={confirmed} disabled={busy}
          onChange={event => setConfirmed(event.target.checked)} />确认所有选中的销售单均属于所选付款客户</label>
      </>}
      {error && <p role="alert">{error}</p>}
    </div>
    <div className="modal-footer"><button type="button" className="secondary" disabled={busy} onClick={onClose}>取消</button>
      <button type="submit" className="primary" disabled={Boolean(action) || !canCreate || (!unconfirmed && Boolean(approvalProblem))}>{action === 'create' ? '正在提交…' : unconfirmed ? '重试本次提交' : '提交飞书审批'}</button></div>
  </form>;
}

export default function JushuitanSettlement({ open, api, customers, DialogLayer, Icon, onClose, onCreated, approvals }) {
  const pending = useRef(false);
  const attempt = useRef(null);
  const close = () => { if (!pending.current && !attempt.current) onClose(); };
  return <DialogLayer open={open} onClose={close} kind="drawer" label="聚水潭结算补录">
    {open && <SettlementForm api={api} customers={customers} Icon={Icon} onClose={close} onCreated={onCreated}
      pending={pending} attempt={attempt} approvals={approvals} />}
  </DialogLayer>;
}
