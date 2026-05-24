const BOT_TOKEN = '8644864895:AAHTlFmailHNNJyOU4PzCwgEONOeGOuWZTY';
const CHAT_ID = '6606492664';

export async function sendTelegram(text) {
  try {
    const res = await fetch(
      `https://api.telegram.org/bot${BOT_TOKEN}/sendMessage`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chat_id: CHAT_ID, text, parse_mode: 'HTML' }),
      }
    );
    return res.ok;
  } catch (e) {
    console.error('Telegram error:', e);
    return false;
  }
}

export function buildOrderMessage(form) {
  const sizes = {
    small: 'صغير (١٠٠٠ لتر)',
    medium: 'وسط (١٠٠٠٠ لتر)',
    custom: `كمية مخصصة: ${form.customQty || ''}`,
  };
  const place =
    form.deliveryPlace === 'ground'
      ? 'خزان أرضي'
      : `الدور${form.floorNum ? ` — رقم الدور: ${form.floorNum}` : ''}`;
  const time = new Date().toLocaleString('ar-YE', { timeZone: 'Asia/Aden' });
  const mapLink =
    form.lat && form.lng
      ? `\n🗺 <a href="https://www.google.com/maps?q=${form.lat},${form.lng}">رابط الموقع على الخريطة</a>`
      : '';

  return `🚛 <b>طلب جديد - مياهي</b>

📦 الحجم: ${sizes[form.size] || form.size}
🏗 مكان التوصيل: ${place}
📍 الموقع: ${form.address}${mapLink}
📞 الجوال: ${form.phone}
${form.notes ? `📝 ملاحظات: ${form.notes}` : ''}
⏰ ${time}`;
}

export function buildInactiveReport(customers) {
  if (!customers.length) return '✅ جميع الزبائن طلبوا هذا الشهر!';
  const month = new Date().toLocaleDateString('ar-YE', {
    month: 'long',
    year: 'numeric',
  });
  const list = customers
    .map((c, i) => `${i + 1}. ${c.name || 'بدون اسم'} — 📞 ${c.phone}`)
    .join('\n');
  return `⏰ <b>زبائن لم يطلبوا هذا الشهر (${month})</b>\n\n${list}`;
}
