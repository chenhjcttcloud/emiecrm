import React, {useEffect, useId, useRef, useState} from 'react';
import './OrderActionMenu.css';

export default function OrderActionMenu({orderId, actions, Icon, disabled}) {
  const id = useId(), trigger = useRef(null), menu = useRef(null);
  const [open, setOpen] = useState(false);
  const supportsPopover = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype;
  const usable = () => [...(menu.current?.querySelectorAll('button:not(:disabled),a[href]') ?? [])];
  function close() {
    if (supportsPopover && menu.current?.matches(':popover-open')) menu.current.hidePopover();
    setOpen(false);
  }
  function position() {
    const rect = trigger.current.getBoundingClientRect(), gap = 6, inset = 12;
    const width = Math.min(200, window.innerWidth - inset * 2);
    const below = window.innerHeight - rect.bottom - gap - inset, above = rect.top - gap - inset;
    const upward = below < actions.length * 36 + 12 && above > below;
    Object.assign(menu.current.style, {width: width + 'px',
      left: Math.max(inset, Math.min(rect.right - width, window.innerWidth - width - inset)) + 'px',
      top: upward ? 'auto' : rect.bottom + gap + 'px', bottom: upward ? window.innerHeight - rect.top + gap + 'px' : 'auto',
      maxHeight: Math.max(0, upward ? above : below) + 'px'});
    if (!supportsPopover) setOpen(value => !value);
  }
  useEffect(() => {
    if (!open) return;
    usable()[0]?.focus({preventScroll: true});
    const dismiss = event => { if (!menu.current?.contains(event.target) && !trigger.current?.contains(event.target)) close(); };
    const onScroll = event => { if (!menu.current?.contains(event.target)) close(); };
    window.addEventListener('resize', close);
    window.addEventListener('scroll', onScroll, true);
    document.addEventListener('pointerdown', dismiss);
    return () => {window.removeEventListener('resize', close);window.removeEventListener('scroll', onScroll, true);document.removeEventListener('pointerdown', dismiss);};
  }, [open]);
  if (!actions.length) return '—';
  return <>
    <button ref={trigger} type="button" className="order-menu-trigger" disabled={disabled} popoverTarget={supportsPopover ? id : undefined}
      aria-label={'订单 ' + orderId + ' 的操作'} aria-expanded={open} aria-controls={id} onClick={position}>
      操作 <Icon name="chevron" size={14}/>
    </button>
    <div id={id} ref={menu} popover={supportsPopover ? 'auto' : undefined} hidden={!supportsPopover && !open}
      className={'order-action-menu' + (!supportsPopover && open ? ' is-open' : '')} role="group" aria-label="订单操作"
      onToggle={event => setOpen(event.currentTarget.matches(':popover-open'))}
      onKeyDown={event => {
        const entries = usable(), index = entries.indexOf(document.activeElement);
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
          event.preventDefault();
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 :
            (index + (event.key === 'ArrowDown' ? 1 : -1) + entries.length) % entries.length;
          entries[next]?.focus();
        } else if (event.key === 'Escape') {event.preventDefault();event.stopPropagation();close();trigger.current?.focus();}
      }}>
      {actions.map(action => action.href && !action.disabled ? <a key={action.key} href={action.href} target="_blank" rel="noreferrer" onClick={close}>{action.label}</a> :
        <button type="button" key={action.key} className={action.danger ? 'order-action-danger' : undefined} disabled={action.disabled}
          onClick={() => {close();trigger.current?.focus({preventScroll: true});action.onSelect?.();}}>{action.label}</button>)}
    </div>
  </>;
}
