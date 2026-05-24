import { useState, useEffect, useCallback } from 'react';
import { getTruckByToken, getOrders, updateOrderTruck, markOrderDelivered, invalidateCache } from './data';

function formatDate(d) {
  if (!d) return '—';
  return new Date(d).toLocaleDateString('ar-YE', {
    day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  });
}

function OrderSize({ o }) {
  const label = o.size === 'custom'
    ? `كمية مخصصة: ${o.customQty}`
    : o.size === 'medium' ? 'وسط (١٠٠٠٠ لتر)' : 'صغير (١٠٠٠ لتر)';
  const place = o.deliveryPlace === 'roof' ? `الدور ${o.floorNum || ''}` : 'خزان أرضي';
  return <>{label} — {place}</>;
}

export default function Driver() {
  const token = window.location.pathname.split('/driver/')[1] || '';

  const [truck, setTruck] = useState(null);
  const [notFound, setNotFound] = useState(false);
  const [allOrders, setAllOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [actioning, setActioning] = useState(null); // orderId being acted on
  const [tab, setTab] = useState('new'); // new | mine | delivered

  const load = useCallback(async (force = false) => {
    if (force) invalidateCache();
    setLoading(true);
    try {
      const t = await getTruckByToken(token);
      if (!t) { setNotFound(true); return; }
      setTruck(t);
      const orders = await getOrders();
      setAllOrders(orders.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)));
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  // طلبات جديدة لم يأخذها أحد
  const newOrders = allOrders.filter((o) => !o.truckId && o.status === 'new');
  // طلباتي المستلمة وقيد التوصيل
  const myOrders = allOrders.filter((o) => o.truckId === truck?.id && o.status === 'accepted');
  // طلباتي المسلّمة
  const myDelivered = allOrders.filter((o) => o.truckId === truck?.id && o.status === 'delivered');

  async function handleAccept(orderId) {
    setActioning(orderId);
    const success = await updateOrderTruck(orderId, truck.id);
    await load(true);
    setActioning(null);
    if (success) {
      setTab('mine');
    } else {
      alert('⚠️ سبقك سائق آخر واستلم هذا الطلب');
    }
  }

  async function handleDeliver(orderId) {
    setActioning(orderId);
    await markOrderDelivered(orderId);
    await load(true);
    setActioning(null);
  }

  // ── Guards ────────────────────────────────────────────────
  if (!token) return <ErrorScreen msg="اطلب رابطك الخاص من المشرف" />;
  if (notFound) return <ErrorScreen msg="الرابط غير موجود — تأكد منه أو اطلب رابطاً جديداً" />;
  if (loading && !truck) return <LoadingScreen />;

  const TABS = [
    { id: 'new',       label: `📋 جديدة (${newOrders.length})` },
    { id: 'mine',      label: `🚛 عندي (${myOrders.length})` },
    { id: 'delivered', label: `✅ سلّمت (${myDelivered.length})` },
  ];

  return (
    <div className="min-h-screen bg-gray-50" dir="rtl" style={{ fontFamily: "'Tajawal','Cairo',sans-serif" }}>

      {/* Header */}
      <div style={{ background: 'linear-gradient(135deg,#0369a1,#0284c7)', padding: '16px 16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', maxWidth: 480, margin: '0 auto' }}>
          <div>
            <p style={{ color: 'rgba(255,255,255,0.75)', fontSize: 12, margin: 0 }}>مرحباً،</p>
            <p style={{ color: '#fff', fontWeight: 800, fontSize: 20, margin: 0 }}>🚛 {truck?.name}</p>
          </div>
          <button onClick={() => load(true)}
            style={{ background: 'rgba(255,255,255,0.2)', border: 'none', borderRadius: 10, padding: '8px 14px', color: '#fff', fontSize: 16, cursor: 'pointer' }}>
            🔄
          </button>
        </div>

        {/* Stats */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10, maxWidth: 480, margin: '14px auto 0' }}>
          {[
            { label: 'طلبات جديدة', val: newOrders.length, emoji: '📋', color: '#fbbf24' },
            { label: 'عندي الآن',   val: myOrders.length,  emoji: '🚛', color: '#60a5fa' },
            { label: 'إجمالي المنجز', val: truck?.orderCount || myDelivered.length, emoji: '🏆', color: '#4ade80' },
          ].map((s) => (
            <div key={s.label} style={{ background: 'rgba(255,255,255,0.15)', borderRadius: 14, padding: '12px 8px', textAlign: 'center' }}>
              <p style={{ fontSize: 20, margin: '0 0 2px' }}>{s.emoji}</p>
              <p style={{ color: s.color, fontWeight: 900, fontSize: 22, margin: 0 }}>{s.val}</p>
              <p style={{ color: 'rgba(255,255,255,0.75)', fontSize: 10, margin: '2px 0 0' }}>{s.label}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b bg-white sticky top-0 z-10">
        {TABS.map((t) => (
          <button key={t.id} onClick={() => setTab(t.id)}
            className={`flex-1 py-3 text-xs font-medium whitespace-nowrap px-1 transition ${tab === t.id ? 'border-b-2 border-blue-600 text-blue-700' : 'text-gray-500'}`}>
            {t.label}
          </button>
        ))}
      </div>

      {loading && <div className="text-center py-3 text-blue-500 text-sm">⏳ جاري التحديث...</div>}

      <div style={{ maxWidth: 480, margin: '0 auto', padding: '16px' }}>

        {/* ── جديدة ── */}
        {tab === 'new' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {newOrders.length === 0 && !loading && (
              <EmptyCard icon="📭" title="لا توجد طلبات جديدة" sub="ستظهر الطلبات هنا فور وصولها" />
            )}
            {newOrders.map((o) => (
              <div key={o.id} style={{ background: '#fff', borderRadius: 20, padding: 16, boxShadow: '0 2px 12px rgba(0,0,0,0.07)', borderRight: '4px solid #3b82f6' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                  <span style={{ background: '#dbeafe', color: '#1e40af', fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 20 }}>🆕 طلب جديد</span>
                  <p style={{ color: '#6b7280', fontSize: 12, margin: 0 }}>{formatDate(o.createdAt)}</p>
                </div>
                <OrderDetails o={o} />
                <button
                  onClick={() => handleAccept(o.id)}
                  disabled={actioning === o.id}
                  style={{
                    width: '100%', marginTop: 12, border: 'none', borderRadius: 12, padding: '12px',
                    fontSize: 14, fontWeight: 700, cursor: 'pointer',
                    background: actioning === o.id ? '#dbeafe' : 'linear-gradient(135deg,#2563eb,#1d4ed8)',
                    color: actioning === o.id ? '#1e40af' : '#fff',
                    boxShadow: actioning === o.id ? 'none' : '0 4px 12px rgba(37,99,235,0.35)',
                  }}>
                  {actioning === o.id ? '⏳ جاري الاستلام...' : '🚛 استلام الطلب'}
                </button>
              </div>
            ))}
          </div>
        )}

        {/* ── عندي ── */}
        {tab === 'mine' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {myOrders.length === 0 && !loading && (
              <EmptyCard icon="🎉" title="ما عندك طلبات الآن" sub="استلم طلباً من تبويب الجديدة" />
            )}
            {myOrders.map((o) => (
              <div key={o.id} style={{ background: '#fff', borderRadius: 20, padding: 16, boxShadow: '0 2px 12px rgba(0,0,0,0.07)', borderRight: '4px solid #f59e0b' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                  <span style={{ background: '#fef3c7', color: '#92400e', fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 20 }}>🚛 قيد التوصيل</span>
                  <p style={{ color: '#6b7280', fontSize: 12, margin: 0 }}>{formatDate(o.createdAt)}</p>
                </div>
                <OrderDetails o={o} />
                <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                  {o.lat && o.lng && (
                    <a href={`https://www.google.com/maps?q=${o.lat},${o.lng}`} target="_blank" rel="noreferrer"
                      style={{ flex: 1, background: '#eff6ff', color: '#1d4ed8', borderRadius: 12, padding: '10px', textAlign: 'center', fontSize: 13, fontWeight: 600, textDecoration: 'none', border: '1.5px solid #bfdbfe' }}>
                      🗺 الخريطة
                    </a>
                  )}
                  <a href={`tel:${o.phone}`}
                    style={{ flex: 1, background: '#f0fdf4', color: '#15803d', borderRadius: 12, padding: '10px', textAlign: 'center', fontSize: 13, fontWeight: 600, textDecoration: 'none', border: '1.5px solid #bbf7d0' }}>
                    📞 اتصال
                  </a>
                  <button
                    onClick={() => handleDeliver(o.id)}
                    disabled={actioning === o.id}
                    style={{
                      flex: 2, border: 'none', borderRadius: 12, padding: '10px', fontSize: 13, fontWeight: 700, cursor: 'pointer',
                      background: actioning === o.id ? '#d1fae5' : 'linear-gradient(135deg,#22c55e,#16a34a)',
                      color: actioning === o.id ? '#15803d' : '#fff',
                      boxShadow: actioning === o.id ? 'none' : '0 4px 12px rgba(34,197,94,0.3)',
                    }}>
                    {actioning === o.id ? '⏳ جاري...' : '✅ تم التسليم'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ── سلّمت ── */}
        {tab === 'delivered' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {myDelivered.length === 0 && !loading && (
              <EmptyCard icon="📋" title="لا توجد طلبات مسلّمة بعد" sub="" />
            )}
            {myDelivered.map((o) => (
              <div key={o.id} style={{ background: '#fff', borderRadius: 16, padding: '14px 16px', boxShadow: '0 1px 6px rgba(0,0,0,0.05)', borderRight: '4px solid #22c55e', opacity: 0.85 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                  <span style={{ background: '#dcfce7', color: '#15803d', fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 20 }}>✅ تم التسليم</span>
                  <p style={{ color: '#9ca3af', fontSize: 11, margin: 0 }}>{formatDate(o.deliveredAt || o.createdAt)}</p>
                </div>
                <p style={{ fontWeight: 700, color: '#0369a1', fontSize: 15, margin: '0 0 2px' }}>📞 {o.phone}</p>
                <p style={{ color: '#6b7280', fontSize: 13, margin: 0 }}><OrderSize o={o} /></p>
                <p style={{ color: '#9ca3af', fontSize: 12, margin: '4px 0 0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>📍 {o.address}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Helper components ─────────────────────────────────────────
function OrderDetails({ o }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <span style={{ fontSize: 16 }}>📞</span>
        <a href={`tel:${o.phone}`} style={{ fontWeight: 700, color: '#0369a1', fontSize: 16, textDecoration: 'none' }}>{o.phone}</a>
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <span style={{ fontSize: 16 }}>📦</span>
        <p style={{ color: '#374151', fontSize: 14, margin: 0, fontWeight: 600 }}>
          {o.size === 'custom' ? `كمية مخصصة: ${o.customQty}` : o.size === 'medium' ? 'وسط (١٠٠٠٠ لتر)' : 'صغير (١٠٠٠ لتر)'}
          {' — '}
          {o.deliveryPlace === 'roof' ? `الدور ${o.floorNum || ''}` : 'خزان أرضي'}
        </p>
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
        <span style={{ fontSize: 16 }}>📍</span>
        <p style={{ color: '#374151', fontSize: 13, margin: 0 }}>{o.address}</p>
      </div>
      {o.lat && o.lng && (
        <a href={`https://www.google.com/maps?q=${o.lat},${o.lng}`} target="_blank" rel="noreferrer"
          style={{ fontSize: 12, color: '#2563eb', marginRight: 24 }}>
          🗺 فتح الموقع على الخريطة
        </a>
      )}
      {o.notes && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <span style={{ fontSize: 16 }}>📝</span>
          <p style={{ color: '#6b7280', fontSize: 13, margin: 0 }}>{o.notes}</p>
        </div>
      )}
    </div>
  );
}

function EmptyCard({ icon, title, sub }) {
  return (
    <div style={{ background: '#fff', borderRadius: 20, padding: 32, textAlign: 'center', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }}>
      <p style={{ fontSize: 48, margin: '0 0 8px' }}>{icon}</p>
      <p style={{ fontWeight: 700, color: '#374151', margin: '0 0 4px' }}>{title}</p>
      {sub && <p style={{ color: '#9ca3af', fontSize: 13, margin: 0 }}>{sub}</p>}
    </div>
  );
}

function ErrorScreen({ msg }) {
  return (
    <div dir="rtl" style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f9ff', fontFamily: "'Tajawal','Cairo',sans-serif" }}>
      <div style={{ textAlign: 'center', padding: 32 }}>
        <p style={{ fontSize: 56, margin: '0 0 12px' }}>❌</p>
        <p style={{ fontWeight: 800, fontSize: 18, color: '#0c4a6e', margin: '0 0 8px' }}>رابط غير صالح</p>
        <p style={{ color: '#64748b', fontSize: 14 }}>{msg}</p>
      </div>
    </div>
  );
}

function LoadingScreen() {
  return (
    <div dir="rtl" style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f9ff', fontFamily: "'Tajawal','Cairo',sans-serif" }}>
      <p style={{ color: '#0284c7', fontSize: 16 }}>⏳ جاري التحميل...</p>
    </div>
  );
}
