import React, { useEffect, useRef, useState } from 'react';
import Pagination from './Pagination';
import PaymentMethodPicker from './PaymentMethodPicker';
import { customerOptions } from './customerOptions';
import {
  customerImportFields, suggestCustomerMapping, validateCustomerMapping, makeCustomerImportRows,
  initialCustomerDecisions, customerDecisionSummary, customerImportTemplate, validateCustomerFile, mergeCustomerImportCandidates,
} from './customerImportModel';
import './customerImport.css';

const labels = { NEW: '可新增', MATCHED: '已有关联', REVIEW: '待确认', INVALID: '资料需处理' };
const statuses = ['达成合作', '跟进中', '潜在客户', '暂停合作'];
const emptyDefaults = owner => ({ entity: '', owner: owner || '', status: '潜在客户', channel: '' });

function downloadTemplate() {
  const url = URL.createObjectURL(new Blob([customerImportTemplate()], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = '客户导入模板.csv';
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export default function CustomerImportDialog({
  open, onClose, api, uploadFile, customers = [], owners = [], environment = 'production', onImported,
  DialogLayer, Icon,
}) {
  const [parsed, setParsed] = useState(null);
  const [filename, setFilename] = useState('');
  const [mapping, setMapping] = useState({});
  const [defaults, setDefaults] = useState(() => emptyDefaults());
  const [scope, setScope] = useState({ environment, customerType: 'offline' });
  const [preview, setPreview] = useState(null);
  const [decisions, setDecisions] = useState({});
  const [action, setAction] = useState('');
  const [error, setError] = useState('');
  const [page, setPage] = useState(1);
  const [success, setSuccess] = useState('');
  const sequence = useRef(0);
  const openRef = useRef(open);
  const submitting = useRef(false);
  const fileInput = useRef(null);
  const previewPanel = useRef(null);
  openRef.current = open;

  useEffect(() => {
    sequence.current++;
    if (open) {
      setParsed(null); setFilename(''); setMapping({}); setDefaults(emptyDefaults());
      setScope({ environment: environment === 'sandbox' ? 'sandbox' : 'production', customerType: 'offline' });
      setPreview(null); setDecisions({}); setAction(''); setError(''); setSuccess(''); setPage(1);
    }
    return () => { sequence.current++; };
  }, [open]);

  useEffect(() => {
    if (!preview) return;
    const frame = requestAnimationFrame(() => previewPanel.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }));
    return () => cancelAnimationFrame(frame);
  }, [preview]);

  const busy = Boolean(action);
  const current = id => openRef.current && sequence.current === id;
  const close = () => { if (!submitting.current) onClose(); };
  const invalidate = () => {
    sequence.current++; setPreview(null); setDecisions({}); setPage(1); setError(''); setSuccess('');
  };

  async function chooseFile(file) {
    if (!file || busy) return;
    invalidate(); setParsed(null); setFilename(''); setMapping({});
    const problem = validateCustomerFile(file);
    if (problem) { setError(problem); return; }
    const id = ++sequence.current;
    setAction('parse');
    try {
      const data = await uploadFile(file);
      if (!current(id)) return;
      if (!Array.isArray(data?.headers) || !data.headers.length || !Array.isArray(data?.rows) || !data.rows.length) throw Error('文件未包含可导入的客户数据。');
      setParsed(data); setFilename(file.name); setMapping(suggestCustomerMapping(data.headers));
    } catch (problem) { if (current(id)) setError(problem.message || '文件读取失败，请重试。'); }
    finally { if (current(id)) setAction(''); }
  }

  async function buildPreview() {
    if (busy || !parsed) return;
    setError(''); setSuccess('');
    let payload;
    try { payload = { rows: makeCustomerImportRows(parsed, mapping), defaults: { ...defaults }, ...scope }; }
    catch (problem) { setError(problem.message); return; }
    const id = ++sequence.current;
    setPreview(null); setDecisions({}); setAction('preview');
    try {
      const data = await api('customers/import/preview', payload);
      if (!current(id)) return;
      if (!Array.isArray(data?.rows) || data.rows.length !== payload.rows.length || !Number.isSafeInteger(data.revision)) throw Error('导入预览格式无效，请重试。');
      setPreview({ data, payload }); setDecisions(initialCustomerDecisions(data.rows)); setPage(1);
    } catch (problem) { if (current(id)) setError(problem.message || '预览失败，请稍后重试。'); }
    finally { if (current(id)) setAction(''); }
  }

  async function commitImport() {
    if (busy || submitting.current || !preview) return;
    const summary = customerDecisionSummary(preview.data.rows, decisions);
    if (!summary.created && !summary.linked) { setError('请至少选择一行新增或关联客户。'); return; }
    if (summary.missingTarget) { setError('请为每一条关联记录选择已有客户。'); return; }
    const id = ++sequence.current;
    submitting.current = true; setAction('submit'); setError('');
    try {
      const result = await api('customers/import', {
        ...preview.payload, revision: preview.data.revision,
        decisions: preview.data.rows.map(row => decisions[row.rowNumber] || { rowNumber: row.rowNumber, action: 'SKIP' }),
      });
      if (!current(id)) return;
      setPreview(null); setDecisions({}); setParsed(null); setFilename('');
      const message = `已新增 ${result.summary?.created || 0} 个客户，关联 ${result.summary?.linked || 0} 条编号，跳过 ${result.summary?.skipped || 0} 行。`;
      setSuccess(message);
      try { await onImported?.(result); }
      catch {
        if (current(id)) setError('导入已完成，但客户列表刷新失败。请关闭窗口后刷新客户列表。');
        return;
      }
      if (current(id)) onClose();
    } catch (problem) {
      if (!current(id)) return;
      setPreview(null); setDecisions({});
      setError(problem.status === 409 ? '客户数据已更新，请重新生成预览，核对后再导入。' : `${problem.message || '暂时无法确认导入结果'} 请重新生成预览后核对。`);
    } finally {
      submitting.current = false;
      if (current(id)) setAction('');
    }
  }

  function changeDecision(row, patch) {
    setDecisions(previous => ({
      ...previous,
      [row.rowNumber]: { rowNumber: row.rowNumber, ...previous[row.rowNumber], ...patch },
    }));
    setError('');
  }

  const rows = preview?.data.rows || [];
  const summary = customerDecisionSummary(rows, decisions);
  const mappingProblem = parsed ? validateCustomerMapping(parsed.headers, mapping) : '';
  const pageSize = 20;
  const currentPage = Math.min(page, Math.max(1, Math.ceil(rows.length / pageSize)));
  const options = customerOptions(mergeCustomerImportCandidates(customers, rows));
  const ownerNames = [...new Set(owners.filter(Boolean))];

  return <DialogLayer open={open} onClose={close} label="批量导入客户">
    <div className="modal customer-import-dialog" aria-busy={busy}>
      <div className="modal-title"><div><h2>批量导入客户</h2><p>先核对资料，再新增客户或关联聚水潭编号</p></div>
        <button type="button" disabled={action === 'submit'} onClick={close} aria-label="关闭客户导入"><Icon name="close" size={18} /></button></div>
      <div className="modal-body">
        <ol className="customer-import-steps" aria-label="导入步骤"><li className={!parsed ? 'is-current' : ''}>1 选择文件</li><li className={parsed && !preview ? 'is-current' : ''}>2 对应字段</li><li className={preview ? 'is-current' : ''}>3 核对并导入</li></ol>
        {error && <p role="alert" className="customer-import-error">{error}</p>}
        {success && <p role="status" className="customer-import-success">{success}</p>}
        <section className="customer-import-file">
          <div><strong>{filename || '选择客户资料文件'}</strong><p>{parsed ? `已读取 ${parsed.rows.length} 行客户资料、${parsed.headers.length} 列。` : '支持 Excel、CSV、TSV，每次最多 2000 行、文件不超过 5 MB。'}</p></div>
          <input ref={fileInput} type="file" aria-label="选择客户导入文件" accept=".xlsx,.xls,.csv,.tsv" disabled={busy}
            onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; void chooseFile(file); }} />
          <button type="button" className="secondary" disabled={busy} onClick={() => fileInput.current?.click()}><Icon name="upload" size={16} />{action === 'parse' ? '正在读取…' : parsed ? '重新选择文件' : '选择文件'}</button>
          <button type="button" className="link-btn" disabled={busy} onClick={downloadTemplate}>下载 CSV 模板</button>
        </section>
        <p className="form-hint customer-import-note">手机号和客户编号请在 Excel 中设置为文本，保留前导零及完整位数。导入只新增客户或追加编号关联，已有资料、订单和余额保持原值。</p>
        {parsed && <>
          <section className="customer-import-section">
            <h3>客户编号来源</h3>
            <div className="customer-import-scope">
              <label className="field"><span>聚水潭环境</span><select value={scope.environment} disabled={busy} onChange={event => { invalidate(); setScope({ ...scope, environment: event.target.value }); }}><option value="production">正式环境</option><option value="sandbox">沙箱环境</option></select></label>
              <label className="field"><span>客户编号类型</span><select value={scope.customerType} disabled={busy} onChange={event => { invalidate(); setScope({ ...scope, customerType: event.target.value }); }}><option value="offline">线下客户编号</option><option value="distributor">分销商编号</option><option value="buyer">订单买家编号（buyer_id）</option></select></label>
            </div>
            <p className="form-hint">请选择文件中编号所属的环境和类型。缺少外部编号也可以新增客户，补齐编号后再进行关联。订单买家请使用聚水潭生成的 buyer_id，不能用昵称、收件人或订单号代替。</p>
          </section>
          <section className="customer-import-section">
            <h3>对应文件列</h3><p>请核对自动识别结果；不需要的字段可选择“不导入”。</p>
            <div className="customer-import-mapping">
              {customerImportFields.map(field => <label className="field" key={field.key}><span>{field.label}{field.required && <em>*</em>}</span>
                <select aria-label={`${field.label}对应列`} value={mapping[field.key] ?? ''} disabled={busy} onChange={event => { invalidate(); setMapping({ ...mapping, [field.key]: event.target.value === '' ? '' : Number(event.target.value) }); }}>
                  <option value="">{field.required ? '请选择文件列' : '不导入'}</option>
                  {parsed.headers.map((header, index) => <option value={index} key={index}>{index + 1}. {header}</option>)}
                </select>
                {mapping[field.key] !== '' && mapping[field.key] != null && <small title={String(parsed.rows[0]?.values[mapping[field.key]] ?? '')}>首行：{String(parsed.rows[0]?.values[mapping[field.key]] ?? '') || '空白'}</small>}
              </label>)}
            </div>
            {mappingProblem && <p className="customer-import-error">{mappingProblem}</p>}
          </section>
          <section className="customer-import-section">
            <h3>补齐空白资料</h3><p>以下值仅用于文件中的空白字段。新增客户还需完整的名称、主体、负责人、状态、渠道、联系人和手机。</p>
            <div className="customer-import-defaults">
              <label className="field"><span>客户主体</span><input maxLength={200} value={defaults.entity} disabled={busy} placeholder="未填写时统一使用" onChange={event => { invalidate(); setDefaults({ ...defaults, entity: event.target.value }); }} /></label>
              <label className="field"><span>负责人</span><input list="customer-import-owners" maxLength={100} value={defaults.owner} disabled={busy} placeholder="选择或输入负责人" onChange={event => { invalidate(); setDefaults({ ...defaults, owner: event.target.value }); }} /><datalist id="customer-import-owners">{ownerNames.map(owner => <option value={owner} key={owner} />)}</datalist></label>
              <label className="field"><span>客户状态</span><select value={defaults.status} disabled={busy} onChange={event => { invalidate(); setDefaults({ ...defaults, status: event.target.value }); }}><option value="">不补齐</option>{statuses.map(status => <option key={status}>{status}</option>)}</select></label>
              <label className="field"><span>销售渠道</span><input maxLength={100} value={defaults.channel} disabled={busy} placeholder="未填写时统一使用" onChange={event => { invalidate(); setDefaults({ ...defaults, channel: event.target.value }); }} /></label>
            </div>
          </section>
        </>}
        {preview && <section ref={previewPanel} className="customer-import-section customer-import-preview">
          <h3>核对本次导入</h3>
          <div className="customer-import-counts">{[['可新增', preview.data.summary?.new || 0], ['已关联', preview.data.summary?.matched || 0], ['待确认', preview.data.summary?.review || 0], ['需处理', preview.data.summary?.invalid || 0]].map(([label, value]) => <span key={label}>{label}<strong>{value}</strong></span>)}</div>
          <p className="form-hint">同名客户不会自动合并。选择“关联已有客户”时只增加编号关联，不替换客户资料；资料不足或重复的行可以跳过。</p>
          <div className="table-scroll"><table className="customer-import-table"><thead><tr><th>文件行</th><th>客户资料</th><th>聚水潭编号</th><th>核对结果</th><th>本次操作</th></tr></thead><tbody>
            {rows.slice((currentPage - 1) * pageSize, currentPage * pageSize).map(row => {
              const decision = decisions[row.rowNumber] || { action: 'SKIP' };
              const preferred = new Set((row.matches || []).map(customer => customer.id));
              const candidates = (row.customerId ? options.filter(option => option.value === row.customerId) : [...options])
                .sort((a, b) => Number(preferred.has(b.value)) - Number(preferred.has(a.value)));
              const createable = row.createable ?? row.status === 'NEW';
              const linkable = row.linkable ?? (row.status === 'REVIEW' && Boolean(row.customer?.externalId));
              return <tr key={row.rowNumber}><td>{row.rowNumber}</td><td><strong>{row.customer?.name || '未填写名称'}</strong><small>{[row.customer?.entity, row.customer?.contact, row.customer?.phone].filter(Boolean).join(' · ') || '—'}</small></td>
                <td className="customer-import-id">{row.customer?.externalId || '未填写'}</td><td><span className={`customer-import-status status-${row.status.toLowerCase()}`}>{labels[row.status] || row.status}</span>{(row.status !== 'INVALID' || !row.errors?.length) && <small>{row.message}</small>}{row.matchesTruncated&&<small>共 {row.matchesTotal} 个候选，可在关联时搜索全部客户。</small>}{row.errors?.length > 0 && <ul className="customer-import-row-errors">{row.errors.map((message, i) => <li key={i}>{message}</li>)}</ul>}</td>
                <td><select aria-label={`第 ${row.rowNumber} 行操作`} value={decision.action} disabled={busy} onChange={event => changeDecision(row, { action: event.target.value, customerId: event.target.value === 'LINK' ? (row.customerId || (row.matches?.length === 1 ? row.matches[0].id : '')) : undefined })}>
                  <option value="SKIP">跳过此行</option>{createable && <option value="CREATE">新增客户</option>}{linkable && <option value="LINK">关联已有客户</option>}
                </select>{decision.action === 'LINK' && <PaymentMethodPicker label={`第 ${row.rowNumber} 行关联客户`} editable options={candidates} value={decision.customerId || ''} disabled={busy} onChange={customerId => changeDecision(row, { customerId })} />}</td></tr>;
            })}
          </tbody></table></div>
          <Pagination total={rows.length} page={currentPage} pageSize={pageSize} onPage={setPage} label="客户导入预览分页" />
        </section>}
      </div>
      <div className="modal-footer">
        {preview && <p className="customer-import-final-count" aria-live="polite">本次新增 <strong>{summary.created}</strong> 个、关联 <strong>{summary.linked}</strong> 条、跳过 <strong>{summary.skipped}</strong> 行</p>}
        <button type="button" className="secondary" disabled={action === 'submit'} onClick={close}>取消</button>
        {preview && <button type="button" className="secondary" disabled={busy} onClick={buildPreview}>重新核对</button>}
        {!preview ? <button type="button" className="primary" disabled={busy || !parsed || Boolean(mappingProblem)} onClick={buildPreview}>{action === 'preview' ? '正在核对…' : '生成导入预览'}</button>
          : <button type="button" className="primary" disabled={busy || (!summary.created && !summary.linked) || Boolean(summary.missingTarget)} onClick={commitImport}>{action === 'submit' ? '正在导入…' : '确认导入'}</button>}
      </div>
    </div>
  </DialogLayer>;
}
