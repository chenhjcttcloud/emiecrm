import React from 'react';
import {logisticsParcels} from './orderLogisticsModel';

export function LogisticsSummary({company,trackingNumber,summary=true}){
 if(!company&&!trackingNumber)return <span className="form-hint">聚水潭尚未返回物流摘要</span>;
 return <div className="jst-logistics-summary"><span>物流公司：{company||'待回传'}</span><span>{summary?'运单摘要':'运单号'}：{trackingNumber||'待回传'}</span></div>;
}

export default function OrderLogistics({shipments,empty='暂无聚水潭出库物流回传。'}){
 const rows=logisticsParcels(shipments);
 if(!rows.length)return <p className="form-hint">{empty}</p>;
 return <div className="order-logistics-parcels">{rows.map(row=><article className="jst-logistics-parcel" key={row.key}>
  <strong>出库单 {row.id||'编号待回传'}</strong><dl className="order-detail-fields">
   <div><dt>实际物流公司</dt><dd>{row.company||'待回传'}</dd></div><div><dt>运单号</dt><dd>{row.trackingNumber||'待回传'}</dd></div>
  </dl></article>)}</div>;
}
