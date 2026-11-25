import React, {useId, useLayoutEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
import './RecipientRegionPicker.css';

const MAX_HEIGHT = 300, MARGIN = 12, GAP = 6;

export default function RecipientRegionPicker({name, label, value = '', options = [], onChange,
  disabled = false, required = true, parentHint = ''}) {
  const id = useId(), field = useRef(null), trigger = useRef(null), panel = useRef(null),
    search = useRef(null), list = useRef(null);
  const [open, setOpen] = useState(false), [query, setQuery] = useState(''),
    [active, setActive] = useState(0), [invalid, setInvalid] = useState(false);
  const supportsPopover = typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.showPopover === 'function';
  const keyword = query.trim(), filtered = options.filter(option => option.toLowerCase().includes(keyword.toLowerCase()));
  const entries = [...filtered.map(option => ({value:option, manual:false})),
    ...(keyword && !options.includes(keyword) ? [{value:keyword, manual:true}] : [])];
  const activeIndex = Math.max(0, Math.min(active, entries.length - 1));
  const showError = invalid && required && !String(value).trim();
  const blocked = disabled || Boolean(parentHint), expanded = open && !blocked;
  const descriptionId = showError ? id + '-error' : parentHint ? id + '-hint' : undefined;

  function openMenu(fromEnd = false) {
    setQuery('');
    const selected = options.indexOf(value);
    setActive(selected >= 0 ? selected : fromEnd ? Math.max(0, options.length - 1) : 0);
    setOpen(true);
  }

  function choose(next) {
    onChange?.(next.trim());
    setInvalid(false);setOpen(false);
    trigger.current?.focus({preventScroll:true});
  }

  useLayoutEffect(() => {
    if (!expanded) {if (open) setOpen(false);return;}
    const popup = panel.current, viewport = window.visualViewport;
    if (supportsPopover) popup.showPopover();
    function place() {
      const rect = trigger.current.getBoundingClientRect();
      const scrollBody = trigger.current.closest('.modal-body'), bounds = scrollBody?.getBoundingClientRect();
      if (bounds && (rect.bottom <= bounds.top || rect.top >= bounds.bottom)) {setOpen(false);return;}
      const left = viewport?.offsetLeft ?? 0, top = viewport?.offsetTop ?? 0,
        right = left + (viewport?.width ?? window.innerWidth), bottom = top + (viewport?.height ?? window.innerHeight);
      const above = Math.max(0, rect.top - top - MARGIN - GAP), below = Math.max(0, bottom - rect.bottom - MARGIN - GAP);
      const upward = below < MAX_HEIGHT && above > below;
      const width = Math.min(Math.max(rect.width, 260), 380, right - left - MARGIN * 2);
      Object.assign(popup.style, {width:width + 'px',
        left:Math.max(left + MARGIN, Math.min(rect.left, right - width - MARGIN)) + 'px',
        top:upward ? 'auto' : rect.bottom + GAP + 'px',
        bottom:upward ? window.innerHeight - rect.top + GAP + 'px' : 'auto',
        maxHeight:Math.min(MAX_HEIGHT, upward ? above : below) + 'px'});
    }
    const outside = event => {
      if (!field.current?.contains(event.target) && !popup.contains(event.target)) setOpen(false);
    };
    place();search.current?.focus({preventScroll:true});
    document.addEventListener('pointerdown', outside);document.addEventListener('focusin', outside);
    window.addEventListener('resize', place);window.addEventListener('scroll', place, true);
    viewport?.addEventListener('resize', place);viewport?.addEventListener('scroll', place);
    return () => {
      document.removeEventListener('pointerdown', outside);document.removeEventListener('focusin', outside);
      window.removeEventListener('resize', place);window.removeEventListener('scroll', place, true);
      viewport?.removeEventListener('resize', place);viewport?.removeEventListener('scroll', place);
      if (supportsPopover && popup.isConnected) popup.hidePopover();
    };
  }, [expanded, supportsPopover]);

  useLayoutEffect(() => {
    const option = list.current?.querySelector('[data-active="true"]');
    if (!expanded || !option) return;
    const item = option.getBoundingClientRect(), bounds = list.current.getBoundingClientRect();
    if (item.top < bounds.top) list.current.scrollTop += item.top - bounds.top;
    if (item.bottom > bounds.bottom) list.current.scrollTop += item.bottom - bounds.bottom;
  }, [expanded, activeIndex, query]);

  function handleKey(event) {
    if (event.nativeEvent.isComposing) return;
    if (event.key === 'Tab') {
      event.preventDefault();
      const clear = panel.current?.querySelector('button');
      if (!event.shiftKey && event.target === search.current && clear) {clear.focus();return;}
      if (event.shiftKey && event.target === clear) {search.current?.focus();return;}
      const scope = trigger.current?.closest('form') || trigger.current?.closest('[role="dialog"]');
      const controls = [...(scope?.querySelectorAll('button,input,select,textarea,a[href],[tabindex]') || [])]
        .filter(element => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length);
      const next = event.shiftKey ? trigger.current : controls[controls.indexOf(trigger.current) + 1] || controls[0];
      setOpen(false);next?.focus({preventScroll:true});
    } else if (event.key === 'Escape') {
      event.preventDefault();event.stopPropagation();setOpen(false);trigger.current?.focus({preventScroll:true});
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();setActive(Math.max(0, Math.min(activeIndex + (event.key === 'ArrowDown' ? 1 : -1), entries.length - 1)));
    } else if (event.key === 'Enter') {
      event.preventDefault();if (entries[activeIndex]) choose(entries[activeIndex].value);
    }
  }

  const popup = expanded && <div ref={panel} className="recipient-region-panel" popover={supportsPopover ? 'manual' : undefined} onKeyDown={handleKey}>
    <div className="recipient-region-search"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/></svg>
      <input ref={search} type="text" role="combobox" aria-label={'搜索或填写' + label} aria-autocomplete="list" aria-expanded="true"
        aria-controls={id + '-list'} aria-activedescendant={entries.length ? id + '-option-' + activeIndex : undefined}
        maxLength={50} value={query} autoComplete="off" placeholder={'搜索或填写' + label}
        onChange={event => {setQuery(event.target.value);setActive(0);}}/>
    </div>
    <ul ref={list} className="recipient-region-options" id={id + '-list'} role="listbox" aria-label={label + '选项'}>
      {entries.map((entry,index) => <li key={(entry.manual ? 'manual:' : 'option:') + entry.value} id={id + '-option-' + index}
        role="option" aria-selected={entry.value === value} data-active={index === activeIndex ? 'true' : undefined}
        className={entry.manual ? 'recipient-region-manual' : undefined} onPointerMove={() => setActive(index)} onClick={() => choose(entry.value)}>
        <span>{entry.manual ? '使用“' + entry.value + '”' : entry.value}</span>
        {entry.manual ? <small>手动填写</small> : entry.value === value && <span className="recipient-region-check" aria-hidden="true">✓</span>}
      </li>)}
      {!entries.length && <li className="recipient-region-empty" role="presentation">暂无选项，可直接填写地区名称</li>}
    </ul>
    <div className="recipient-region-tip"><span>可选择列表，或填写未收录地区后按回车确认</span>{value && <button type="button"
      aria-label={'清空' + label} onClick={() => choose('')} onKeyDown={event => {if (['Enter',' '].includes(event.key)) event.stopPropagation();}}>清空</button>}</div>
  </div>;

  return <div ref={field} className="field recipient-region-picker">
    <label htmlFor={id}>{label}{required && <em aria-hidden="true">*</em>}</label>
    <button ref={trigger} id={id} type="button" className="recipient-region-trigger" disabled={blocked}
      aria-label={label + '，' + (value || parentHint || '请选择或填写')} aria-haspopup="listbox" aria-expanded={expanded}
      aria-controls={expanded ? id + '-list' : undefined} aria-invalid={showError || undefined} aria-describedby={descriptionId}
      onClick={() => open ? setOpen(false) : openMenu()} onKeyDown={event => {
        if (event.key === 'Escape' && expanded) {event.preventDefault();event.stopPropagation();setOpen(false);}
        else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {event.preventDefault();openMenu(event.key === 'ArrowUp');}
      }}>
      <span className={value ? '' : 'is-placeholder'}>{value || parentHint || '请选择或填写'}</span><span className="recipient-region-chevron" aria-hidden="true"/>
    </button>
    <input className="recipient-region-validation" type="text" name={name} value={value} required={required} maxLength={50}
      disabled={disabled} tabIndex={-1} aria-hidden="true" autoComplete="off" onChange={event => choose(event.target.value)}
      onInvalid={event => {event.preventDefault();setInvalid(true);if (event.currentTarget.form?.querySelector(':invalid') === event.currentTarget) trigger.current?.focus();}}/>
    {parentHint && <small className="recipient-region-parent-hint" id={id + '-hint'}>{parentHint.replace('请先选择','选择')}后，可选择或手动填写{label}</small>}
    {showError && <p className="recipient-region-error" id={id + '-error'} role="alert">请选择或填写{label}</p>}
    {popup && createPortal(popup, document.body)}
  </div>;
}
