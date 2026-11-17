import React, { useRef, useState } from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';
import { customerOptions } from './customerOptions';
import { moneyCents } from './orderMoney';

const statusLabels = { Sent: '已发货', Archive: '已归档', Cancelled: '已取消' };
const positiveId = /^[1-9][0-9]{0,18}$/;
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
    if (!positiveId.test(shop) || !ids.length || ids.some(id => !positiveId.test(id))) {
      setError('请填写数字店铺编号和聚水潭内部订单号。');
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
        value={shopId} disabled={disabled} onChange={event => { setShopId(event.target.value); setError(''); }} /></label>
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

function SettlementForm({ api, customers, Icon, onClose, onCreated, pending }) {
  const today = currentDate();
  const [cycle, setCycle] = useState('day');
  const [start, setStart] = useState(today);
  const [end, setEnd] = useState(today);
  const [month, setMonth] = useState('');
  const [customerId, setCustomerId] = useState('');
  const [draftSales, setDraftSales] = useState([]);
  const [preview, setPreview] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [total, setTotal] = useState('');
  const [adjustmentReason, setAdjustmentReason] = useState('');
  const [note, setNote] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [action, setAction] = useState('');
  const [error, setError] = useState('');
  const busy = Boolean(action);
  const [periodStart, periodEnd] = cycle === 'month' ? monthPeriod(month) : [start, end];
  const periodValid = periodStart && periodEnd && periodStart <= periodEnd && periodEnd <= today;
  const invalidPeriod = periodStart && periodEnd && !periodValid;
  const chosen = preview?.sales.filter(sale => selected.has(sale.id)) ?? [];
  const erpCents = salesAmountCents(chosen);
  const totalCents = moneyCents(total);
  const requiresReason = erpCents == null || totalCents !== erpCents;
  const canCreate = Boolean(customerId && chosen.length && totalCents > 0 && confirmed &&
    (!requiresReason || adjustmentReason.trim()));

  function clearPreview() {
    setPreview(null);
    setSelected(new Set());
    setTotal('');
    setAdjustmentReason('');
    setConfirmed(false);
    setError('');
  }

  function selectSale(id, checked) {
    const next = new Set(selected);
    if (checked) next.add(id);
    else next.delete(id);
    setSelected(next);
    const cents = salesAmountCents(preview.sales.filter(sale => next.has(sale.id)));
    setTotal(next.size && cents != null ? String(cents / 100) : '');
    setAdjustmentReason('');
    setConfirmed(false);
    setError('');
  }

  async function loadPreview() {
    if (pending.current || !periodValid || !draftSales.length) return;
    pending.current = true;
    clearPreview();
    setAction('preview');
    try {
      const result = await api('jst/settlements/preview', { cycle, periodStart, periodEnd, sales: draftSales });
      setPreview(result);
      setSelected(new Set(result.sales.map(sale => sale.id)));
      setTotal(result.erpAmount == null ? '' : String(result.erpAmount));
    } catch (problem) {
      setError(problem.message);
    } finally {
      pending.current = false;
      setAction('');
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (!canCreate || pending.current) return;
    pending.current = true;
    setAction('create');
    setError('');
    try {
      const state = await api('jst/settlements', {
        cycle, periodStart, periodEnd, sales: chosen.map(saleRef), customerId,
        total, expectedErpAmount: erpCents == null ? null : erpCents / 100,
        adjustmentReason: requiresReason ? adjustmentReason : '', note, customerConfirmed: true,
      });
      onCreated(state);
    } catch (problem) {
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
    <div className="modal-body jst-form" aria-busy={busy}>
      <div className="field"><span>付款客户</span><PaymentMethodPicker label="付款客户" value={customerId}
        options={customerOptions(customers)} disabled={busy}
        onChange={value => { setCustomerId(value); setConfirmed(false); setError(''); }} /></div>
      <div className="settlement-field-row">
        <label className="field"><span>结算方式</span><select value={cycle} disabled={busy}
          onChange={event => { setCycle(event.target.value); clearPreview(); }}>
          <option value="day">日结</option><option value="month">月结</option>
        </select></label>
        {cycle === 'month'
          ? <label className="field"><span>发货账期</span><input type="month" required value={month}
              max={today.slice(0, 7)} disabled={busy} onChange={event => { setMonth(event.target.value); clearPreview(); }} /></label>
          : <><label className="field"><span>发货开始日</span><input type="date" required value={start}
                max={today} disabled={busy} onChange={event => { setStart(event.target.value); clearPreview(); }} /></label>
              <label className="field"><span>发货结束日</span><input type="date" required value={end}
                min={start} max={today} disabled={busy} onChange={event => { setEnd(event.target.value); clearPreview(); }} /></label></>}
      </div>
      {invalidPeriod && <p role="alert">{cycle === 'month'
        ? '月结账期须为已结束的自然月。'
        : '发货结束日须在开始日与今天之间。'}</p>}
      <section className="jst-document-section">
        <h3>聚水潭销售单</h3>
        <SaleBatchInput sales={draftSales} disabled={busy}
          onAdd={batch => { setDraftSales([...draftSales, ...batch]); clearPreview(); }} />
        {!!draftSales.length && <SettlementSalesTable sales={preview?.sales ?? draftSales} selected={selected}
          onSelect={preview ? selectSale : undefined} disabled={busy}
          onRemove={id => { setDraftSales(draftSales.filter(sale => sale.oId !== id)); clearPreview(); }} />}
        <div className="settlement-action-row">
          <button type="button" className="secondary" onClick={loadPreview}
            disabled={busy || !periodValid || !draftSales.length}>{action === 'preview' ? '正在查询…' : '查询销售单'}</button>
        </div>
      </section>
      {preview && <>
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
      <button type="submit" className="primary" disabled={busy || !canCreate}>{action === 'create' ? '正在提交…' : '提交审批'}</button></div>
  </form>;
}

export default function JushuitanSettlement({ open, api, customers, DialogLayer, Icon, onClose, onCreated }) {
  const pending = useRef(false);
  const close = () => { if (!pending.current) onClose(); };
  return <DialogLayer open={open} onClose={close} kind="drawer" label="聚水潭结算补录">
    {open && <SettlementForm api={api} customers={customers} Icon={Icon} onClose={close} onCreated={onCreated}
      pending={pending} />}
  </DialogLayer>;
}
