import React, {useId, useLayoutEffect, useRef, useState} from 'react';
import './OrderSelect.css';

const PANEL_HEIGHT = 324;
const VIEWPORT_MARGIN = 12;
const PANEL_GAP = 6;

export default function OrderSelect({
  label, options, value, onChange, placeholder = '请选择', searchPlaceholder = '搜索选项', required = false, disabled = false, name,
}) {
  const id = useId();
  const trigger = useRef(null);
  const panel = useRef(null);
  const list = useRef(null);
  const search = useRef(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [invalid, setInvalid] = useState(false);
  const supportsPopover = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype;
  const selectedIndex = options.findIndex(option => option.value === value);
  const selected = options[selectedIndex];
  const searchable = options.length >= 6;
  const keyword = query.trim().toLowerCase();
  const filtered = keyword ? options.filter(option =>
    [option.label, option.detail, option.badge, option.keywords, option.value].filter(Boolean).join(' ').toLowerCase().includes(keyword)) : options;
  const activeIndex = Math.min(active, filtered.length - 1);
  const listId = id + '-options';
  const optionId = index => listId + '-' + index;
  const showError = invalid && required && (!selected || !selected.value);

  function openMenu(fromEnd = false) {
    setQuery('');
    setActive(selectedIndex >= 0 ? selectedIndex : fromEnd ? Math.max(0, options.length - 1) : 0);
    setOpen(true);
  }

  function choose(next) {
    if (next !== value) onChange(next);
    setInvalid(false);
    setOpen(false);
    trigger.current?.focus({preventScroll: true});
  }

  useLayoutEffect(() => {
    if (disabled) {
      if (open) setOpen(false);
      return;
    }
    if (!open || !supportsPopover) return;
    const popup = panel.current;
    const viewport = window.visualViewport;
    const field = trigger.current.parentElement;
    const scrollBody = trigger.current.closest('.modal-body');
    popup.showPopover();

    function place() {
      const rect = trigger.current.getBoundingClientRect();
      const bounds = scrollBody?.getBoundingClientRect();
      if (bounds && (rect.bottom <= bounds.top || rect.top >= bounds.bottom)) {
        setOpen(false);
        return;
      }
      const left = viewport?.offsetLeft ?? 0;
      const top = viewport?.offsetTop ?? 0;
      const right = left + (viewport?.width ?? window.innerWidth);
      const bottom = top + (viewport?.height ?? window.innerHeight);
      const above = rect.top - top - VIEWPORT_MARGIN - PANEL_GAP;
      const below = bottom - rect.bottom - VIEWPORT_MARGIN - PANEL_GAP;
      const naturalHeight = list.current.scrollHeight + popup.clientHeight - list.current.clientHeight;
      const upward = below < Math.min(naturalHeight, PANEL_HEIGHT) && above > below;
      const width = Math.min(Math.max(rect.width, 240), 420, right - left - VIEWPORT_MARGIN * 2);
      Object.assign(popup.style, {
        width: width + 'px',
        left: Math.max(left + VIEWPORT_MARGIN, Math.min(rect.left, right - width - VIEWPORT_MARGIN)) + 'px',
        top: upward ? 'auto' : (rect.bottom + PANEL_GAP) + 'px',
        bottom: upward ? (window.innerHeight - rect.top + PANEL_GAP) + 'px' : 'auto',
        maxHeight: Math.max(0, Math.min(PANEL_HEIGHT, upward ? above : below)) + 'px',
      });
    }

    const onPointerDown = event => {
      if (!field.contains(event.target)) setOpen(false);
    };
    const onFocus = event => {
      if (!field.contains(event.target)) setOpen(false);
    };
    place();
    const focusTarget = window.matchMedia('(pointer: coarse)').matches ? list.current : search.current || list.current;
    focusTarget.focus({preventScroll: true});
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('focusin', onFocus);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    viewport?.addEventListener('resize', place);
    viewport?.addEventListener('scroll', place);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('focusin', onFocus);
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
      viewport?.removeEventListener('resize', place);
      viewport?.removeEventListener('scroll', place);
      if (popup.isConnected) popup.hidePopover();
    };
  }, [open, disabled, supportsPopover, searchable]);

  useLayoutEffect(() => {
    const container = list.current;
    const option = container?.children[activeIndex];
    if (!open || !option) return;
    const item = option.getBoundingClientRect();
    const bounds = container.getBoundingClientRect();
    if (item.top < bounds.top) container.scrollTop += item.top - bounds.top;
    if (item.bottom > bounds.bottom) container.scrollTop += item.bottom - bounds.bottom;
  }, [open, activeIndex, filtered.length]);

  function handlePanelKey(event) {
    if (event.nativeEvent.isComposing) return;
    const {key} = event;
    if (key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      trigger.current?.focus({preventScroll: true});
    } else if (key === 'ArrowUp' || key === 'ArrowDown') {
      event.preventDefault();
      setActive(Math.max(0, Math.min(activeIndex + (key === 'ArrowDown' ? 1 : -1), filtered.length - 1)));
    } else if (event.target === list.current && (key === 'Home' || key === 'End')) {
      event.preventDefault();
      setActive(key === 'Home' ? 0 : Math.max(0, filtered.length - 1));
    } else if (key === 'Enter' || (key === ' ' && event.target === list.current)) {
      event.preventDefault();
      if (filtered[activeIndex]) choose(filtered[activeIndex].value);
    }
  }

  const labelText = <>{label}{required && <em aria-hidden="true">*</em>}</>;
  const emptyOption = options.some(option => !option.value) ? null : <option value="">{placeholder}</option>;

  if (!supportsPopover) return <label className="field">
    <span>{labelText}</span>
    <select name={name} required={required} disabled={disabled} value={selected?.value ?? ''} onChange={event => onChange(event.target.value)}>
      {emptyOption}
      {options.map(option => <option key={option.value} value={option.value}>
        {[option.label, option.detail, option.badge].filter(Boolean).join(' · ')}
      </option>)}
    </select>
  </label>;

  return <div className="field order-select">
    <label htmlFor={id}>{labelText}</label>
    <button id={id} ref={trigger} type="button" className="order-select-trigger" disabled={disabled}
      aria-label={[label, required && '必填', selected?.label || placeholder].filter(Boolean).join('，')}
      aria-haspopup="listbox" aria-expanded={open && !disabled} aria-controls={open && !disabled ? listId : undefined}
      aria-invalid={showError || undefined} aria-describedby={showError ? id + '-error' : undefined}
      onClick={() => open ? setOpen(false) : openMenu()}
      onKeyDown={event => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        event.preventDefault();
        openMenu(event.key === 'ArrowUp');
      }}>
      <span className={selected ? 'order-select-value' : 'order-select-value is-placeholder'}>{selected?.label || placeholder}</span>
      {selected?.detail && <span className="order-select-code">{selected.detail}</span>}
      {selected?.badge && <span className="order-select-badge">{selected.badge}</span>}
      <span className="order-select-chevron" aria-hidden="true"/>
    </button>
    {!required && name && <input type="hidden" name={name} disabled={disabled} value={selected?.value ?? ''}/>}
    {required && <select name={name} className="order-select-validation" tabIndex={-1} aria-hidden="true" required disabled={disabled}
      value={selected?.value ?? ''} onChange={event => choose(event.target.value)}
      onInvalid={event => {
        event.preventDefault();
        setInvalid(true);
        if (event.target.form?.querySelector(':invalid') === event.target) trigger.current?.focus();
      }}>
      {emptyOption}
      {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>}
    {showError && <p id={id + '-error'} className="order-select-error" role="alert">{placeholder}</p>}
    {open && !disabled && <div className="order-select-panel" ref={panel} popover="manual" onKeyDown={handlePanelKey}>
      {searchable && <div className="order-select-search">
        <input type="search" ref={search} role="combobox" aria-label={searchPlaceholder} aria-autocomplete="list"
          aria-expanded="true" aria-controls={listId} aria-activedescendant={activeIndex >= 0 ? optionId(activeIndex) : undefined}
          placeholder={searchPlaceholder} autoComplete="off" value={query}
          onChange={event => { setQuery(event.target.value); setActive(0); }}/>
      </div>}
      <ul ref={list} className="order-select-options" role="listbox" id={listId} aria-label={label}
        aria-activedescendant={activeIndex >= 0 ? optionId(activeIndex) : undefined} tabIndex={searchable ? -1 : 0}>
        {filtered.map((option, index) => <li key={option.value} id={optionId(index)} role="option"
          aria-selected={option.value === value} data-active={index === activeIndex || undefined}
          onPointerMove={() => setActive(index)} onClick={() => choose(option.value)}>
          <div className="order-select-option-text">
            <span>{option.label}</span>
            {option.detail && <span className="order-select-detail">{option.detail}</span>}
          </div>
          {option.badge && <span className="order-select-badge">{option.badge}</span>}
          {option.value === value && <span className="order-select-check" aria-hidden="true"/>}
        </li>)}
        {!filtered.length && <li className="order-select-empty" role="presentation">没有匹配的选项</li>}
      </ul>
    </div>}
  </div>;
}
