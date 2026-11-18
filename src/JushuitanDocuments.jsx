import React, { useEffect, useState } from 'react';
import { SaleBatchInput, SettlementSalesTable, saleAmount, saleRef, salesAmountCents } from './JushuitanSettlement';

const warehouseStatuses = { Archive: '已归档', Cancelled: '已取消', Delete: '已作废', OuterConfirming: '外部确认中' };
const purchaseStatuses = {
  Creating: '草拟', WaitConfirm: '待审核', Confirmed: '已确认',
  Finished: '已完成', Cancelled: '已取消', Delete: '已作废',
};

function Items({ items = [], quantityLabel }) {
  if (!items.length) return null;
  return <details className="jst-document-items">
    <summary>商品明细（{items.length} 行）</summary>
    <div className="table-scroll"><table className="ledger">
      <thead><tr><th scope="col">商品</th><th scope="col">SKU</th><th scope="col">{quantityLabel}</th></tr></thead>
      <tbody>{items.map((item, index) => <tr key={item.id || index}>
        <td>{item.name || '—'}</td><td>{item.sku || '—'}</td><td>{item.quantity ?? '—'}</td>
      </tr>)}</tbody>
    </table></div>
  </details>;
}

function WarehouseDocuments({ title, documents, outbound = false }) {
  const rows = documents?.rows ?? [];
  const statuses = {
    ...warehouseStatuses,
    WaitConfirm: outbound ? '待出库' : '待入库',
    Confirmed: outbound ? '已出库' : '已入库',
  };
  return <section className="jst-document-section">
    <h3>{title}</h3>
    {documents?.error && <p role="alert">{documents.error}</p>}
    {!rows.length && !documents?.error && <p className="form-hint">暂无{title}记录。</p>}
    {rows.map(row => {
      const details = [row.saleId && `销售单 ${row.saleId}`, row.warehouse, row.date,
        row.afterSaleId && `售后单 ${row.afterSaleId}`].filter(Boolean).join(' · ');
      return <article className="jst-document-card" key={`${row.saleId ?? ''}:${row.id}`}>
        <div className="jst-document-heading">
          <strong>{row.id}</strong>
          {row.status && <span className="badge badge-blue">{statuses[row.status] || row.status}</span>}
        </div>
        {details && <p className="form-hint">{details}</p>}
        <Items items={row.items} quantityLabel={outbound ? '出库数量' : '入库数量'} />
      </article>;
    })}
  </section>;
}

function DocumentNumber({ label, value, onChange }) {
  return <label className="field">
    <span>{label}</span>
    <input value={value} onChange={event => onChange(event.target.value)}
      required inputMode="numeric" pattern="[0-9]+" maxLength={20} />
  </label>;
}

function SettlementLinks({ order, sales, canEdit, busy, onSave }) {
  const [draft, setDraft] = useState(null);
  const [confirmed, setConfirmed] = useState(false);
  const previousSales = sales.map(saleRef);
  const sum = salesAmountCents(sales);
  const settlement = order.settlement ?? {};
  const paid = Number(order.paid || 0) > 0;
  const previousById = new Map(previousSales.map(ref => [ref.oId, ref.shopId]));
  const changed = draft && (draft.length !== previousSales.length ||
    draft.some(ref => previousById.get(ref.oId) !== ref.shopId));

  function edit(next) {
    setDraft(next);
    setConfirmed(false);
  }

  return <>
    <p className="form-hint">
      {settlement.cycle === 'month' ? '月结' : '日结'} · 发货期间 {settlement.periodStart} 至 {settlement.periodEnd}
      {' · '}结算金额 {saleAmount(order.total)}
    </p>
    <p className="form-hint">补录时聚水潭合计 {saleAmount(settlement.erpAmount)}</p>
    <p className="form-hint">已关联 {sales.length} 张销售单 · 聚水潭合计 {sales.length && sum != null ? saleAmount(sum / 100) : '—'}</p>
    {settlement.adjustmentReason && <p className="form-hint">差额说明 {settlement.adjustmentReason}</p>}
    {!draft && sales.length > 0 && <SettlementSalesTable sales={sales} />}
    {!draft && canEdit && <button type="button" className="secondary" disabled={busy}
      onClick={() => edit(previousSales)}>调整销售单关联</button>}
    {paid && !canEdit && <p className="form-hint">已登记收款的结算单由财务调整关联。</p>}
    {draft && <div className="settlement-link-editor">
      <p className="form-hint">调整关联须保持新旧销售单合计一致，原结算金额、差额说明和收款流水将保留。金额不同请核对后重新补录。</p>
      <SaleBatchInput sales={draft} disabled={busy}
        onAdd={batch => edit([...draft, ...batch])} />
      {!!draft.length && <SettlementSalesTable sales={draft.map(ref =>
        sales.find(sale => sale.id === ref.oId && sale.shopId === ref.shopId) ?? ref)}
        disabled={busy} onRemove={id => edit(draft.filter(ref => ref.oId !== id))} />}
      <label className="settlement-confirm"><input type="checkbox" checked={confirmed} disabled={busy}
        onChange={event => setConfirmed(event.target.checked)} />确认调整后的销售单均属于该付款客户</label>
      <div className="settlement-action-row">
        <button type="button" className="secondary" disabled={busy} onClick={() => edit(null)}>放弃调整</button>
        <button type="button" className="primary" disabled={!changed || !draft.length || !confirmed || busy}
          onClick={() => onSave({ sales: draft, previousSales, customerConfirmed: true }, () => edit(null))}>保存关联</button>
      </div>
    </div>}
  </>;
}

function DocumentDrawer({ order, api, canWrite, canSettle, Icon, onClose }) {
  const [documents, setDocuments] = useState(null);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [shopId, setShopId] = useState('');
  const [oId, setOId] = useState('');
  const [poId, setPoId] = useState('');
  const endpoint = `jst/orders/${encodeURIComponent(order.id)}/documents`;

  useEffect(() => {
    let active = true;
    api(endpoint).then(data => {
      if (active) setDocuments(data);
    }).catch(problem => {
      if (active) setError(problem.message);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [api, endpoint, refresh]);

  function reload() {
    setError('');
    setLoading(true);
    setRefresh(value => value + 1);
  }

  async function update(kind, input, afterSave) {
    if (loading || saving) return;
    setSaving(true);
    setError('');
    try {
      await api(`${endpoint}/${kind}`, input);
      afterSave?.();
      reload();
    } catch (problem) {
      setError(problem.message);
    } finally {
      setSaving(false);
    }
  }

  function linkSale(event) {
    event.preventDefault();
    update('sale', { shopId, oId }, () => { setShopId(''); setOId(''); });
  }

  function linkPurchase(event) {
    event.preventDefault();
    if (window.confirm(`确认将采购单 ${poId} 关联到 CRM 订单 ${order.id}？`)) {
      update('purchases', { poId }, () => setPoId(''));
    }
  }

  const sale = documents?.salesOrder;
  const settlement = order.source === 'jushuitan-settlement';
  const sales = documents?.salesOrders ?? (sale ? [sale] : []);
  const busy = loading || saving;
  return <div className="drawer">
    <div className="modal-title">
      <h2>关联单据</h2>
      <button type="button" aria-label="关闭关联单据" onClick={onClose}><Icon name="close" /></button>
    </div>
    <div className="modal-body jst-form jst-documents" aria-busy={busy}>
      <div className="read-field">CRM 订单<p>{order.id}{order.customer && ` · ${order.customer}`}</p></div>
      {error && <p role="alert">{error}</p>}
      {loading && <p className="form-hint" role="status">正在查询单据…</p>}
      {saving && <p className="form-hint" role="status">正在保存关联…</p>}
      {documents && <>
        <section className="jst-document-section">
          <h3>聚水潭销售订单</h3>
          {settlement ? <SettlementLinks key={refresh} order={order} sales={sales}
            canEdit={canWrite && (!Number(order.paid || 0) || canSettle)} busy={busy}
            onSave={(input, onSaved) => update('sales', input, onSaved)} />
            : <>{sale ? <div className="jst-document-card">
            <div className="jst-document-heading">
              <strong>内部订单号 {sale.id}</strong>
              <span className="badge badge-blue">{sale.source === 'manual' ? '手动关联' : '来自发货记录'}</span>
            </div>
            <p className="form-hint">店铺编号 {sale.shopId}{sale.soId && ` · 线上单号 ${sale.soId}`}</p>
            {canWrite && sale.source === 'manual' && <button type="button" className="link-btn" disabled={busy}
              onClick={() => { if (window.confirm(`确认解除聚水潭销售订单 ${sale.id} 的关联？`)) update('sale', { remove: true }); }}>
              解除销售订单关联
            </button>}
          </div> : <p className="form-hint">尚未关联聚水潭销售订单。</p>}
          {canWrite && !sale && <form className="jst-document-link" onSubmit={linkSale}>
            <fieldset disabled={busy}>
              <DocumentNumber label="店铺编号" value={shopId} onChange={setShopId} />
              <DocumentNumber label="聚水潭内部订单号" value={oId} onChange={setOId} />
              <button className="secondary" type="submit">关联销售订单</button>
            </fieldset>
          </form>}</>}
        </section>
        {sales.length > 0 && <>
          <WarehouseDocuments title="销售出库单" documents={documents.outbound} outbound />
          <WarehouseDocuments title="退货入库单" documents={documents.returns} />
        </>}
        <section className="jst-document-section">
          <h3>采购单</h3>
          {!documents.purchases?.length && <p className="form-hint">暂无已关联采购单。</p>}
          {documents.purchases?.map(purchase => <article className="jst-document-card" key={purchase.id}>
            <div className="jst-document-heading">
              <strong>采购单 {purchase.id}</strong>
              {purchase.status && <span className="badge badge-blue">{purchaseStatuses[purchase.status] || purchase.status}</span>}
              {canWrite && <button type="button" className="link-btn" disabled={busy}
                onClick={() => { if (window.confirm(`确认解除采购单 ${purchase.id} 的关联？`)) update('purchases', { poId: purchase.id, remove: true }); }}>
                解除采购单关联
              </button>}
            </div>
            <p className="form-hint">{[purchase.supplier, purchase.date, purchase.sourceOrderId && `来源单号 ${purchase.sourceOrderId}`].filter(Boolean).join(' · ')}</p>
            {purchase.error && <p role="alert">{purchase.error}</p>}
            <Items items={purchase.items} quantityLabel="采购数量" />
            {purchase.receipts && !purchase.error && <WarehouseDocuments title="采购入库单" documents={purchase.receipts} />}
          </article>)}
          {canWrite && <form className="jst-document-link" onSubmit={linkPurchase}>
            <p className="form-hint">请先核对采购单与这笔 CRM 订单的业务关系。</p>
            <fieldset disabled={busy}>
              <DocumentNumber label="采购单号" value={poId} onChange={setPoId} />
              <button className="secondary" type="submit">关联采购单</button>
            </fieldset>
          </form>}
        </section>
      </>}
    </div>
    <div className="modal-footer">
      <button type="button" className="secondary" onClick={onClose}>关闭</button>
      <button type="button" className="primary" disabled={busy} onClick={reload}>刷新单据</button>
    </div>
  </div>;
}

export default function JushuitanDocuments({ order, api, canWrite, canSettle, Icon, DialogLayer, onClose }) {
  return <DialogLayer open={Boolean(order)} onClose={onClose} kind="drawer" label="关联单据">
    {order && <DocumentDrawer key={order.id} order={order} api={api} canWrite={canWrite} canSettle={canSettle}
      Icon={Icon} onClose={onClose} />}
  </DialogLayer>;
}
