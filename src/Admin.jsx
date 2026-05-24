import { useState, useEffect, useCallback } from 'react';
import {
  getCustomers,
  getInactiveCustomers,
  getOrders,
  getTrucks,
  saveTruck,
  deleteTruck,
  saveCustomer,
  submitOrder,
  updateOrderTruck,
  markOrderDelivered,
  invalidateCache,
} from './data';
import { sendTelegram, buildInactiveReport } from './telegram';

const TABS = [
  { id: 'customers', label: '👤 الزبائن' },
  { id: 'inactive', label: '⏰ غير نشطين' },
  { id: 'orders', label: '📋 الطلبات' },
  { id: 'trucks', label: '🚛 الوايتات' },
  { id: 'register', label: '➕ تسجيل' },
];

function formatDate(d) {
  return d
    ? new Date(d).toLocaleDateString('ar-YE', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      })
    : '—';
}

export default function Admin() {
  const [tab, setTab] = useState('customers');
  const [loading, setLoading] = useState(false);
  const [customers, setCustomers] = useState([]);
  const [inactive, setInactive] = useState([]);
  const [trucks, setTrucks] = useState([]);
  const [orders, setOrders] = useState([]);

  const [registerForm, setRegisterForm] = useState({
    phone: '',
    name: '',
    notes: '',
    size: 'medium',
    deliveryPlace: 'ground',
    address: '',
  });
  const [registerDone, setRegisterDone] = useState(false);

  const [sendingReport, setSendingReport] = useState(false);
  const [reportSent, setReportSent] = useState(false);

  const [truckForm, setTruckForm] = useState({ name: '', phone: '' });
  const [editingTruckId, setEditingTruckId] = useState(null);
  const [truckSaved, setTruckSaved] = useState(false);

  const month = new Date().toLocaleDateString('ar-YE', {
    month: 'long',
    year: 'numeric',
  });

  const load = useCallback(
    async (force = false) => {
      if (force) invalidateCache();
      setLoading(true);
      try {
        const [c, inc, t, o] = await Promise.all([
          getCustomers(),
          getInactiveCustomers(),
          getTrucks(),
          getOrders(),
        ]);
        setCustomers(c);
        setInactive(inc);
        setTrucks(t);
        setOrders(o.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)));
      } finally {
        setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    load();
  }, [tab, load]);

  async function handleRegister(e) {
    e.preventDefault();
    await submitOrder(registerForm);
    setRegisterDone(true);
    setRegisterForm({
      phone: '',
      name: '',
      notes: '',
      size: 'medium',
      deliveryPlace: 'ground',
      address: '',
    });
    setTimeout(() => setRegisterDone(false), 3000);
    load();
  }

  async function handleSendReport() {
    setSendingReport(true);
    await sendTelegram(buildInactiveReport(inactive));
    setSendingReport(false);
    setReportSent(true);
    setTimeout(() => setReportSent(false), 4000);
  }

  async function handleSaveTruck(e) {
    e.preventDefault();
    await saveTruck(editingTruckId ? { ...truckForm, id: editingTruckId } : truckForm);
    setTruckForm({ name: '', phone: '' });
    setEditingTruckId(null);
    setTruckSaved(true);
    setTimeout(() => setTruckSaved(false), 2500);
    load();
  }

  function editTruck(t) {
    setEditingTruckId(t.id);
    setTruckForm({ name: t.name, phone: t.phone });
  }

  async function handleDeleteTruck(id) {
    if (!confirm('حذف هذا الوايت؟')) return;
    await deleteTruck(id);
    load();
  }

  async function handleAssignTruck(orderId, truckId) {
    await updateOrderTruck(orderId, truckId);
    load(true);
  }

  async function handleDeliver(orderId) {
    await markOrderDelivered(orderId);
    load(true);
  }

  return (
    <div className="min-h-screen bg-gray-50" dir="rtl">
      {/* Header */}
      <div className="bg-blue-700 text-white px-4 py-5 text-center">
        <h1 className="text-xl font-bold">💧 مياهي — لوحة الإدارة</h1>
      </div>

      {/* Tabs */}
      <div className="flex border-b bg-white sticky top-0 z-10 overflow-x-auto">
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex-1 py-3 text-xs font-medium whitespace-nowrap px-2 transition ${
              tab === t.id
                ? 'border-b-2 border-blue-600 text-blue-700'
                : 'text-gray-500'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Loading bar */}
      {loading && (
        <div className="text-center py-4 text-blue-500 text-sm">
          ⏳ جاري التحميل...
        </div>
      )}

      <div className="p-4 max-w-2xl mx-auto">
        {/* ── Customers ── */}
        {tab === 'customers' && (
          <div className="space-y-3">
            <div className="flex justify-between items-center">
              <h2 className="font-bold text-gray-700">
                إجمالي الزبائن: {customers.length}
              </h2>
              <button
                onClick={() => load(true)}
                className="text-blue-500 text-xs"
              >
                🔄 تحديث
              </button>
            </div>
            {customers.length === 0 && !loading && (
              <p className="text-gray-400 text-center py-10">
                لا يوجد زبائن مسجلين بعد
              </p>
            )}
            {customers.map((c) => (
              <div key={c.id} className="bg-white rounded-2xl p-4 shadow-sm">
                <div className="flex justify-between items-start">
                  <div>
                    <p className="font-bold text-gray-800">
                      {c.name || 'بدون اسم'}
                    </p>
                    <p className="text-sm text-gray-500 mt-0.5">
                      📞 {c.phone}
                    </p>
                    <p className="text-xs text-gray-400 mt-1">
                      آخر طلب: {formatDate(c.lastOrder)} • عدد الطلبات:{' '}
                      {c.orderCount}
                    </p>
                  </div>
                  <a
                    href={`https://wa.me/967${c.phone.replace(/^0/, '')}`}
                    target="_blank"
                    rel="noreferrer"
                    className="bg-green-500 text-white text-xs px-3 py-2 rounded-xl font-medium hover:bg-green-600 transition"
                  >
                    واتساب
                  </a>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ── Inactive ── */}
        {tab === 'inactive' && (
          <div className="space-y-3">
            <div className="bg-amber-50 border border-amber-200 rounded-2xl p-4 text-center">
              <p className="text-amber-700 font-medium text-sm">
                زبائن لم يطلبوا في {month}
              </p>
              <p className="text-3xl font-bold text-amber-600 mt-1">
                {inactive.length}
              </p>
            </div>
            {inactive.length > 0 && (
              <button
                onClick={handleSendReport}
                disabled={sendingReport}
                className="w-full bg-blue-600 text-white py-3 rounded-xl font-bold hover:bg-blue-700 transition disabled:opacity-60"
              >
                {sendingReport ? '⏳ جاري الإرسال...' : '📤 إرسال التقرير لتيليجرام'}
              </button>
            )}
            {reportSent && (
              <p className="text-center text-green-600 font-medium">
                ✅ تم إرسال التقرير!
              </p>
            )}
            {inactive.length === 0 && !loading && (
              <p className="text-green-600 text-center py-8 font-medium">
                ✅ جميع الزبائن طلبوا هذا الشهر!
              </p>
            )}
            {inactive.map((c) => (
              <div
                key={c.id}
                className="bg-white rounded-2xl p-4 shadow-sm flex justify-between items-center"
              >
                <div>
                  <p className="font-bold text-gray-800">
                    {c.name || 'بدون اسم'}
                  </p>
                  <p className="text-sm text-gray-500">📞 {c.phone}</p>
                  <p className="text-xs text-gray-400 mt-0.5">
                    آخر طلب: {formatDate(c.lastOrder)}
                  </p>
                </div>
                <a
                  href={`https://wa.me/967${c.phone.replace(/^0/, '')}?text=${encodeURIComponent('السلام عليكم، نتمنى تجديد طلب المياه 💧')}`}
                  target="_blank"
                  rel="noreferrer"
                  className="bg-green-500 text-white text-xs px-3 py-2 rounded-xl font-medium hover:bg-green-600 transition"
                >
                  تذكير واتساب
                </a>
              </div>
            ))}
          </div>
        )}

        {/* ── Orders ── */}
        {tab === 'orders' && (
          <div className="space-y-3">
            <div className="flex justify-between items-center">
              <div className="grid grid-cols-3 gap-2 flex-1">
                {[
                  {
                    label: 'جديد',
                    val: orders.filter((o) => !o.truckId).length,
                    bg: 'bg-blue-50',
                    text: 'text-blue-700',
                  },
                  {
                    label: 'قيد التوصيل',
                    val: orders.filter((o) => o.status === 'accepted').length,
                    bg: 'bg-amber-50',
                    text: 'text-amber-700',
                  },
                  {
                    label: 'تم التسليم',
                    val: orders.filter((o) => o.status === 'delivered').length,
                    bg: 'bg-green-50',
                    text: 'text-green-700',
                  },
                ].map((s) => (
                  <div
                    key={s.label}
                    className={`${s.bg} rounded-2xl p-3 text-center`}
                  >
                    <p className={`text-2xl font-black ${s.text}`}>{s.val}</p>
                    <p className={`text-xs ${s.text} mt-0.5`}>{s.label}</p>
                  </div>
                ))}
              </div>
              <button
                onClick={() => load(true)}
                className="text-blue-500 text-xs mr-2"
              >
                🔄
              </button>
            </div>
            {orders.length === 0 && !loading && (
              <p className="text-gray-400 text-center py-10">
                لا توجد طلبات بعد
              </p>
            )}
            {orders.map((o) => {
              const truck = trucks.find((t) => t.id === o.truckId);
              const badgeClass = o.truckId
                ? o.status === 'delivered'
                  ? 'bg-green-50 text-green-700'
                  : 'bg-amber-50 text-amber-700'
                : 'bg-blue-50 text-blue-700';
              const badgeLabel = o.truckId
                ? o.status === 'delivered'
                  ? '✅ تم التسليم'
                  : '🚛 قيد التوصيل'
                : 'جديد';

              return (
                <div key={o.id} className="bg-white rounded-2xl p-4 shadow-sm">
                  <div className="flex justify-between items-start mb-2">
                    <div>
                      <span
                        className={`text-xs font-bold px-2 py-1 rounded-full ${badgeClass}`}
                      >
                        {badgeLabel}
                      </span>
                      <p className="text-xs text-gray-400 mt-1">
                        {formatDate(o.createdAt)}
                      </p>
                    </div>
                    <p className="font-bold text-gray-800 text-sm">{o.phone}</p>
                  </div>
                  <p className="text-sm text-gray-600">
                    📦{' '}
                    {o.size === 'custom'
                      ? `كمية: ${o.customQty}`
                      : o.size === 'medium'
                      ? 'وسط (١٠٠٠٠ لتر)'
                      : 'صغير (١٠٠٠ لتر)'}
                    &nbsp;|&nbsp;
                    {o.deliveryPlace === 'roof'
                      ? `الدور ${o.floorNum || ''}`
                      : 'خزان أرضي'}
                  </p>
                  <p className="text-xs text-gray-400 mt-1 truncate">
                    📍 {o.address}
                  </p>
                  {truck && (
                    <p className="text-xs text-blue-600 mt-1 font-medium">
                      🚛 {truck.name}
                    </p>
                  )}
                  {o.lat && o.lng && (
                    <a
                      href={`https://www.google.com/maps?q=${o.lat},${o.lng}`}
                      target="_blank"
                      rel="noreferrer"
                      className="text-xs text-blue-500 hover:underline mt-1 block"
                    >
                      🗺 عرض الموقع
                    </a>
                  )}
                  {/* Assign truck */}
                  {!o.truckId && trucks.length > 0 && (
                    <div className="mt-2 flex gap-2 flex-wrap">
                      {trucks.map((t) => (
                        <button
                          key={t.id}
                          onClick={() => handleAssignTruck(o.id, t.id)}
                          className="text-xs bg-blue-100 text-blue-700 px-3 py-1 rounded-full hover:bg-blue-200 transition"
                        >
                          تعيين: {t.name}
                        </button>
                      ))}
                    </div>
                  )}
                  {o.status === 'accepted' && (
                    <button
                      onClick={() => handleDeliver(o.id)}
                      className="mt-2 text-xs bg-green-100 text-green-700 px-3 py-1 rounded-full hover:bg-green-200 transition"
                    >
                      ✅ تأكيد التسليم
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* ── Trucks ── */}
        {tab === 'trucks' && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="bg-blue-50 rounded-2xl p-4 text-center">
                <p className="text-2xl font-black text-blue-700">
                  {trucks.length}
                </p>
                <p className="text-xs text-blue-600 mt-1">إجمالي الوايتات</p>
              </div>
              <div className="bg-green-50 rounded-2xl p-4 text-center">
                <p className="text-2xl font-black text-green-700">
                  {trucks.reduce((s, t) => s + (t.orderCount || 0), 0)}
                </p>
                <p className="text-xs text-green-600 mt-1">
                  إجمالي الطلبات المنجزة
                </p>
              </div>
            </div>

            <div className="bg-white rounded-2xl p-4 shadow-sm">
              <p className="font-bold text-gray-700 mb-3">
                {editingTruckId ? '✏️ تعديل الوايت' : '➕ إضافة وايت جديد'}
              </p>
              <form onSubmit={handleSaveTruck} className="space-y-3">
                <input
                  value={truckForm.name}
                  onChange={(e) =>
                    setTruckForm((f) => ({ ...f, name: e.target.value }))
                  }
                  required
                  placeholder="اسم السائق أو رقم الوايت"
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400"
                />
                <input
                  value={truckForm.phone}
                  onChange={(e) =>
                    setTruckForm((f) => ({ ...f, phone: e.target.value }))
                  }
                  placeholder="رقم الجوال (اختياري)"
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400"
                />
                <div className="flex gap-2">
                  <button
                    type="submit"
                    className="flex-1 bg-blue-600 text-white py-2 rounded-xl font-bold text-sm hover:bg-blue-700 transition"
                  >
                    {editingTruckId ? 'حفظ التعديل' : 'إضافة'}
                  </button>
                  {editingTruckId && (
                    <button
                      type="button"
                      onClick={() => {
                        setEditingTruckId(null);
                        setTruckForm({ name: '', phone: '' });
                      }}
                      className="px-4 bg-gray-100 text-gray-600 rounded-xl text-sm hover:bg-gray-200 transition"
                    >
                      إلغاء
                    </button>
                  )}
                </div>
                {truckSaved && (
                  <p className="text-green-600 text-center text-sm font-medium">
                    ✅ تم الحفظ!
                  </p>
                )}
              </form>
            </div>

            {trucks.map((t) => (
              <div key={t.id} className="bg-white rounded-2xl p-4 shadow-sm">
                <div className="flex justify-between items-start mb-3">
                  <div>
                    <p className="font-bold text-gray-800">{t.name}</p>
                    {t.phone && (
                      <p className="text-sm text-gray-500">📞 {t.phone}</p>
                    )}
                    <p className="text-xs text-gray-400 mt-0.5">
                      طلبات منجزة: {t.orderCount || 0}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => editTruck(t)}
                      className="text-xs bg-blue-100 text-blue-700 px-3 py-1.5 rounded-xl hover:bg-blue-200 transition"
                    >
                      تعديل
                    </button>
                    <button
                      onClick={() => handleDeleteTruck(t.id)}
                      className="text-xs bg-red-100 text-red-600 px-3 py-1.5 rounded-xl hover:bg-red-200 transition"
                    >
                      حذف
                    </button>
                  </div>
                </div>
                {t.token ? (
                  <button
                    onClick={() => {
                      const link = `${window.location.origin}/driver/${t.token}`;
                      navigator.clipboard.writeText(link);
                      alert(`✅ تم نسخ الرابط:\n${link}`);
                    }}
                    className="w-full bg-blue-50 border border-blue-200 text-blue-700 text-xs py-2 px-3 rounded-xl font-medium hover:bg-blue-100 transition text-right"
                  >
                    🔗 نسخ رابط {t.name}
                  </button>
                ) : (
                  <p className="text-xs text-gray-400 text-center">أضف الوايت مجدداً للحصول على رابط</p>
                )}
              </div>
            ))}
          </div>
        )}

        {/* ── Register order ── */}
        {tab === 'register' && (
          <div className="bg-white rounded-2xl p-4 shadow-sm">
            <p className="font-bold text-gray-700 mb-4">
              ➕ تسجيل طلب مكتمل
            </p>
            <form onSubmit={handleRegister} className="space-y-3">
              <input
                value={registerForm.phone}
                onChange={(e) =>
                  setRegisterForm((f) => ({ ...f, phone: e.target.value }))
                }
                required
                placeholder="رقم الجوال *"
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400"
              />
              <input
                value={registerForm.name}
                onChange={(e) =>
                  setRegisterForm((f) => ({ ...f, name: e.target.value }))
                }
                placeholder="اسم الزبون (اختياري)"
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400"
              />
              <input
                value={registerForm.address}
                onChange={(e) =>
                  setRegisterForm((f) => ({ ...f, address: e.target.value }))
                }
                placeholder="العنوان (اختياري)"
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400"
              />
              <textarea
                value={registerForm.notes}
                onChange={(e) =>
                  setRegisterForm((f) => ({ ...f, notes: e.target.value }))
                }
                placeholder="ملاحظة (اختياري)"
                rows={2}
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm outline-none focus:border-blue-400 resize-none"
              />
              <button
                type="submit"
                className="w-full bg-blue-600 text-white py-3 rounded-xl font-bold hover:bg-blue-700 transition"
              >
                تسجيل الطلب
              </button>
              {registerDone && (
                <p className="text-green-600 text-center font-medium">
                  ✅ تم التسجيل!
                </p>
              )}
            </form>
          </div>
        )}
      </div>
    </div>
  );
}
