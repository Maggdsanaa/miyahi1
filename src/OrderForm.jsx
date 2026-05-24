import { useState, useMemo } from 'react';
import { submitOrder } from './data';
import { sendTelegram, buildOrderMessage } from './telegram';

const WA_NUMBER = '967774403305';

const SIZES = [
  { id: 'small', label: 'صغير', sub: 'مناسب للاستخدام المنزلي', liters: '١٠٠٠ لتر', icon: '🪣' },
  { id: 'medium', label: 'وسط', sub: 'الأكثر طلباً', liters: '١٠٠٠٠ لتر', icon: '🛢️', popular: true },
  { id: 'custom', label: 'كمية مخصصة', sub: 'حدد الكمية التي تريدها', liters: '', icon: '✏️' },
];

const PLACES = [
  { id: 'ground', label: 'خزان أرضي', icon: '🏠' },
  { id: 'roof', label: 'الدور', icon: '🏗' },
];

export default function OrderForm() {
  const [form, setForm] = useState({
    size: '',
    customQty: '',
    deliveryPlace: '',
    floorNum: '',
    address: '',
    phone: '',
    notes: '',
    lat: null,
    lng: null,
  });
  const [gpsState, setGpsState] = useState('idle'); // idle | loading | done | denied
  const [gpsError, setGpsError] = useState('');
  const [submitError, setSubmitError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const bubbles = useMemo(
    () =>
      Array.from({ length: 12 }, (_, i) => ({
        id: i,
        size: Math.random() * 60 + 20,
        left: Math.random() * 90 + 5,
        delay: Math.random() * 6,
        dur: Math.random() * 6 + 6,
      })),
    []
  );

  function set(key, val) {
    setForm((f) => ({ ...f, [key]: val }));
  }

  async function handleGPS() {
    if (!navigator.geolocation) {
      setGpsState('denied');
      setGpsError('متصفحك لا يدعم تحديد الموقع');
      return;
    }
    setGpsState('loading');
    setGpsError('');
    navigator.geolocation.getCurrentPosition(
      async ({ coords: { latitude, longitude } }) => {
        setForm((f) => ({
          ...f,
          address: `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`,
          lat: latitude,
          lng: longitude,
        }));
        setGpsState('done');
        try {
          const data = await (
            await fetch(
              `https://nominatim.openstreetmap.org/reverse?lat=${latitude}&lon=${longitude}&format=json&accept-language=ar`
            )
          ).json();
          const parts = [
            data.address?.suburb || data.address?.neighbourhood || data.address?.quarter,
            data.address?.road || data.address?.street,
            data.address?.city || data.address?.town || data.address?.village,
          ].filter(Boolean);
          setForm((f) => ({
            ...f,
            address: parts.length ? parts.join('، ') : data.display_name,
          }));
        } catch {}
      },
      (err) => {
        setGpsState('denied');
        setGpsError(
          err.code === 1
            ? 'رفضت الإذن — افتح إعدادات المتصفح وأذن بالموقع'
            : err.code === 2
            ? 'تعذّر تحديد الموقع — تأكد من تفعيل GPS'
            : 'انتهت مهلة التحديد — حاول مرة أخرى'
        );
      },
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 }
    );
  }

  function waText() {
    const sizeLabel =
      form.size === 'small'
        ? 'صغير (١٠٠٠ لتر)'
        : form.size === 'medium'
        ? 'وسط (١٠٠٠٠ لتر)'
        : `كمية مخصصة: ${form.customQty}`;
    const place =
      form.deliveryPlace === 'ground'
        ? 'خزان أرضي'
        : `الدور${form.floorNum ? ` — رقم الدور: ${form.floorNum}` : ''}`;
    const mapLine =
      form.lat && form.lng
        ? `\n🗺 الموقع: https://maps.google.com/?q=${form.lat},${form.lng}`
        : '';
    return `🚛 *طلب جديد - مياهي*\n\n📦 الحجم: ${sizeLabel}\n🏗 مكان التوصيل: ${place}\n📍 العنوان: ${form.address}${mapLine}\n📞 الجوال: ${form.phone}${form.notes ? `\n📝 ملاحظات: ${form.notes}` : ''}`;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    if (
      !form.size ||
      !form.deliveryPlace ||
      !form.address ||
      !form.phone ||
      (form.size === 'custom' && !form.customQty) ||
      (form.deliveryPlace === 'roof' && !form.floorNum)
    )
      return;

    setSubmitting(true);
    setSubmitError('');
    try {
      await submitOrder(form);
      sendTelegram(buildOrderMessage(form));
      window.open(`https://wa.me/${WA_NUMBER}?text=${encodeURIComponent(waText())}`, '_blank');
    } catch (err) {
      setSubmitError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      className="min-h-screen"
      dir="rtl"
      style={{ fontFamily: "'Tajawal', 'Cairo', 'Segoe UI', sans-serif" }}
    >
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Tajawal:wght@400;500;700;800;900&display=swap');
        @keyframes float-up {
          0%   { transform: translateY(0) scale(1); opacity: 0.15; }
          50%  { opacity: 0.25; }
          100% { transform: translateY(-110vh) scale(1.1); opacity: 0; }
        }
        .bubble { animation: float-up linear infinite; position: absolute; bottom: -80px;
          border-radius: 50%; background: rgba(255,255,255,0.18); backdrop-filter: blur(2px);
          border: 1px solid rgba(255,255,255,0.3); }
        .size-card { transition: all .2s; cursor: pointer; }
        .size-card:hover { transform: translateY(-2px); }
        .size-card.selected { border-color: #1d6fe8; background: linear-gradient(135deg,#e8f0fe,#dbeafe);
          box-shadow: 0 0 0 3px rgba(29,111,232,0.2); }
        .place-card { transition: all .2s; cursor: pointer; }
        .place-card.selected { border-color: #1d6fe8; background: #eff6ff; }
        .wa-btn { background: linear-gradient(135deg,#22c55e,#16a34a);
          box-shadow: 0 4px 20px rgba(34,197,94,0.4); transition: all .2s; }
        .wa-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 28px rgba(34,197,94,0.5); }
      `}</style>

      {/* Hero */}
      <section
        style={{
          background: 'linear-gradient(160deg,#0369a1 0%,#0284c7 30%,#38bdf8 70%,#7dd3fc 100%)',
          minHeight: '92vh',
          position: 'relative',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {bubbles.map((b) => (
          <div
            key={b.id}
            className="bubble"
            style={{
              width: b.size,
              height: b.size,
              left: `${b.left}%`,
              animationDelay: `${b.delay}s`,
              animationDuration: `${b.dur}s`,
            }}
          />
        ))}

        {/* Nav */}
        <nav
          style={{
            padding: '16px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            position: 'relative',
            zIndex: 10,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div
              style={{
                width: 36, height: 36, borderRadius: '50%',
                background: 'rgba(255,255,255,0.2)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 18,
              }}
            >
              💧
            </div>
            <span style={{ color: '#fff', fontWeight: 800, fontSize: 18 }}>مياهي</span>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <a
              href="#order"
              style={{
                background: 'rgba(255,255,255,0.2)', backdropFilter: 'blur(8px)',
                color: '#fff', padding: '6px 14px', borderRadius: 20,
                fontSize: 13, fontWeight: 600, textDecoration: 'none',
                border: '1px solid rgba(255,255,255,0.3)',
              }}
            >
              🚛 وايتات مياه الشرب
            </a>
            <a
              href="#order"
              style={{
                background: '#22c55e', color: '#fff', padding: '6px 14px',
                borderRadius: 20, fontSize: 13, fontWeight: 700,
                textDecoration: 'none',
              }}
            >
              اطلب الآن
            </a>
          </div>
        </nav>

        {/* Hero text */}
        <div
          style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center',
            padding: '20px 20px 40px', position: 'relative', zIndex: 10,
          }}
        >
          <div
            style={{
              width: 80, height: 80, borderRadius: '50%',
              background: 'rgba(255,255,255,0.2)', backdropFilter: 'blur(10px)',
              border: '2px solid rgba(255,255,255,0.4)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 36, marginBottom: 16,
            }}
          >
            💧
          </div>
          <h1 style={{ color: '#fff', fontWeight: 900, fontSize: 36, margin: '0 0 8px', textAlign: 'center' }}>
            مياهي
          </h1>
          <p style={{ color: 'rgba(255,255,255,0.85)', fontSize: 16, margin: '0 0 32px', textAlign: 'center' }}>
            خدمة توصيل المياه — سريع وموثوق
          </p>
          <a
            href="#order"
            style={{
              background: '#fff', color: '#0369a1', padding: '14px 32px',
              borderRadius: 16, fontSize: 16, fontWeight: 800,
              textDecoration: 'none', boxShadow: '0 8px 30px rgba(0,0,0,0.15)',
            }}
          >
            🚛 اطلب الآن
          </a>
        </div>
      </section>

      {/* Order Form */}
      <section id="order" style={{ background: '#f0f9ff', padding: '32px 16px' }}>
        <div style={{ maxWidth: 480, margin: '0 auto' }}>
          <h2
            style={{
              textAlign: 'center', fontWeight: 800, fontSize: 22,
              color: '#0c4a6e', marginBottom: 20,
            }}
          >
            🚛 اطلب توصيل المياه
          </h2>

          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Size */}
            <div style={{ background: '#fff', borderRadius: 20, padding: '18px 16px' }}>
              <p style={{ fontWeight: 700, color: '#0c4a6e', marginBottom: 14, fontSize: 14 }}>
                اختر الحجم *
              </p>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {SIZES.map((s) => (
                  <div key={s.id}>
                    <div
                      className={`size-card${form.size === s.id ? ' selected' : ''}`}
                      onClick={() => set('size', s.id)}
                      style={{
                        border: '2px solid #e2e8f0', borderRadius: 14,
                        padding: '12px 14px', display: 'flex',
                        alignItems: 'center', gap: 12,
                      }}
                    >
                      <span style={{ fontSize: 26 }}>{s.icon}</span>
                      <div style={{ flex: 1 }}>
                        <p style={{ fontWeight: 700, color: '#0c4a6e', fontSize: 15, margin: 0 }}>
                          {s.label}
                          {s.popular && (
                            <span
                              style={{
                                marginRight: 8, background: '#fbbf24', color: '#78350f',
                                fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 10,
                              }}
                            >
                              الأكثر طلباً
                            </span>
                          )}
                        </p>
                        <p style={{ color: '#64748b', fontSize: 12, margin: '2px 0 0' }}>{s.sub}</p>
                        {s.liters && (
                          <span style={{ fontSize: 13, fontWeight: 700, color: '#0369a1' }}>
                            {s.liters}
                          </span>
                        )}
                      </div>
                      <div
                        style={{
                          width: 20, height: 20, borderRadius: '50%',
                          border: `2px solid ${form.size === s.id ? '#1d6fe8' : '#cbd5e1'}`,
                          background: form.size === s.id ? '#1d6fe8' : 'transparent',
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                        }}
                      >
                        {form.size === s.id && (
                          <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#fff' }} />
                        )}
                      </div>
                    </div>
                    {s.id === 'custom' && form.size === 'custom' && (
                      <input
                        type="text"
                        value={form.customQty}
                        onChange={(e) => set('customQty', e.target.value)}
                        required
                        placeholder="مثال: ٣ طن أو ٥٠٠٠ لتر..."
                        style={{
                          width: '100%', border: '1.5px solid #1d6fe8', borderRadius: 10,
                          padding: '10px 12px', fontSize: 13, outline: 'none',
                          boxSizing: 'border-box', fontFamily: 'inherit',
                          color: '#334155', marginTop: 6,
                        }}
                      />
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* Delivery place */}
            <div style={{ background: '#fff', borderRadius: 20, padding: '18px 16px' }}>
              <p style={{ fontWeight: 700, color: '#0c4a6e', marginBottom: 14, fontSize: 14 }}>
                بيانات الطلب
              </p>
              <p style={{ fontSize: 12, color: '#64748b', marginBottom: 8 }}>مكان التوصيل *</p>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 16 }}>
                {PLACES.map((p) => (
                  <div
                    key={p.id}
                    className={`place-card${form.deliveryPlace === p.id ? ' selected' : ''}`}
                    onClick={() => set('deliveryPlace', p.id)}
                    style={{
                      border: '2px solid #e2e8f0', borderRadius: 12,
                      padding: '12px', textAlign: 'center', cursor: 'pointer',
                    }}
                  >
                    <div style={{ fontSize: 22, marginBottom: 4 }}>{p.icon}</div>
                    <div style={{ fontSize: 13, fontWeight: 600, color: '#0c4a6e' }}>{p.label}</div>
                  </div>
                ))}
              </div>
              {form.deliveryPlace === 'roof' && (
                <div style={{ marginBottom: 16 }}>
                  <p style={{ fontSize: 12, color: '#64748b', marginBottom: 6 }}>رقم الدور *</p>
                  <input
                    type="text"
                    value={form.floorNum}
                    onChange={(e) => set('floorNum', e.target.value)}
                    required
                    placeholder="مثال: الدور الثاني، الدور الثالث..."
                    style={{
                      width: '100%', border: '1.5px solid #1d6fe8', borderRadius: 10,
                      padding: '10px 12px', fontSize: 13, outline: 'none',
                      boxSizing: 'border-box', fontFamily: 'inherit', color: '#334155',
                    }}
                  />
                </div>
              )}

              {/* GPS */}
              <p style={{ fontSize: 12, color: '#64748b', marginBottom: 8 }}>موقع التوصيل *</p>
              <button
                type="button"
                onClick={handleGPS}
                disabled={gpsState === 'loading'}
                style={{
                  width: '100%', display: 'flex', alignItems: 'center',
                  justifyContent: 'center', gap: 8, padding: '12px',
                  borderRadius: 12, border: '2px dashed',
                  borderColor: gpsState === 'done' ? '#22c55e' : gpsState === 'denied' ? '#ef4444' : '#93c5fd',
                  background: gpsState === 'done' ? '#f0fdf4' : gpsState === 'denied' ? '#fef2f2' : '#eff6ff',
                  color: gpsState === 'done' ? '#15803d' : gpsState === 'denied' ? '#dc2626' : '#1d4ed8',
                  fontSize: 13, fontWeight: 600, cursor: 'pointer', marginBottom: 4,
                }}
              >
                <span>
                  {gpsState === 'loading' ? '🔄' : gpsState === 'done' ? '✅' : gpsState === 'denied' ? '❌' : '📍'}
                </span>
                {gpsState === 'loading'
                  ? 'جاري تحديد موقعك...'
                  : gpsState === 'done'
                  ? 'تم تحديد الموقع — اضغط لإعادة التحديد'
                  : gpsState === 'denied'
                  ? 'اضغط للمحاولة مرة أخرى'
                  : 'تحديد موقعي تلقائياً'}
              </button>
              {gpsError && (
                <p style={{ color: '#ef4444', fontSize: 11, margin: '4px 0 8px' }}>⚠️ {gpsError}</p>
              )}
              {form.lat && form.lng && (
                <a
                  href={`https://www.google.com/maps?q=${form.lat},${form.lng}`}
                  target="_blank"
                  rel="noreferrer"
                  style={{ fontSize: 12, color: '#0369a1', display: 'block', marginBottom: 8 }}
                >
                  🗺 معاينة الموقع على الخريطة
                </a>
              )}
              <input
                type="text"
                value={form.address}
                onChange={(e) => set('address', e.target.value)}
                required
                placeholder="أو اكتب عنوانك يدوياً (الحي، الشارع...)"
                style={{
                  width: '100%', border: '1.5px solid #e2e8f0', borderRadius: 10,
                  padding: '10px 12px', fontSize: 13, outline: 'none',
                  boxSizing: 'border-box', fontFamily: 'inherit', color: '#334155',
                }}
              />

              {/* Phone */}
              <p style={{ fontSize: 12, color: '#64748b', margin: '16px 0 8px' }}>رقم الجوال *</p>
              <input
                type="tel"
                value={form.phone}
                onChange={(e) => set('phone', e.target.value)}
                required
                placeholder="07XXXXXXXX"
                style={{
                  width: '100%', border: '1.5px solid #e2e8f0', borderRadius: 10,
                  padding: '10px 12px', fontSize: 13, outline: 'none',
                  boxSizing: 'border-box', fontFamily: 'inherit', color: '#334155',
                }}
              />

              {/* Notes */}
              <p style={{ fontSize: 12, color: '#64748b', margin: '16px 0 8px' }}>ملاحظات (اختياري)</p>
              <textarea
                value={form.notes}
                onChange={(e) => set('notes', e.target.value)}
                placeholder="أي تفاصيل إضافية..."
                rows={2}
                style={{
                  width: '100%', border: '1.5px solid #e2e8f0', borderRadius: 10,
                  padding: '10px 12px', fontSize: 13, outline: 'none',
                  boxSizing: 'border-box', fontFamily: 'inherit', color: '#334155',
                  resize: 'none',
                }}
              />
            </div>

            <button
              type="submit"
              className="wa-btn"
              style={{
                width: '100%', color: '#fff', padding: '16px',
                borderRadius: 16, fontSize: 16, fontWeight: 800,
                border: 'none', cursor: 'pointer',
              }}
              disabled={submitting}
            >
              {submitting ? '⏳ جاري التحقق...' : '📲 تأكيد الطلب عبر واتساب'}
            </button>

            {submitError && (
              <div style={{ background: '#fef2f2', border: '1.5px solid #fca5a5', borderRadius: 14, padding: '12px 16px', textAlign: 'center' }}>
                <p style={{ color: '#dc2626', fontWeight: 700, fontSize: 14, margin: '0 0 4px' }}>⚠️ لا يمكن إرسال الطلب</p>
                <p style={{ color: '#b91c1c', fontSize: 13, margin: 0 }}>{submitError}</p>
              </div>
            )}
          </form>
        </div>
      </section>
    </div>
  );
}
