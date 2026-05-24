const API_URL = '/api/data';
const CACHE_TTL = 20000;
let cache = null;
let cacheTime = 0;

async function getData() {
  if (cache && Date.now() - cacheTime < CACHE_TTL) return cache;
  const res = await fetch(API_URL, { headers: { Accept: 'application/json' } });
  cache = await res.json();
  cacheTime = Date.now();
  return cache;
}

async function saveData(data) {
  cache = data;
  cacheTime = Date.now();
  await fetch(API_URL, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(data),
  });
}

export function invalidateCache() {
  cacheTime = 0;
}

// ─── Customers ───────────────────────────────────────────────
export async function getCustomers() {
  return (await getData()).customers || [];
}

export async function getInactiveCustomers() {
  const customers = await getCustomers();
  const now = new Date();
  return customers.filter((c) => {
    if (!c.lastOrder) return true;
    const last = new Date(c.lastOrder);
    return !(
      last.getMonth() === now.getMonth() &&
      last.getFullYear() === now.getFullYear()
    );
  });
}

export async function saveCustomer(customer) {
  const data = await getData();
  const list = data.customers || [];
  if (customer.id) {
    const idx = list.findIndex((c) => c.id === customer.id);
    if (idx >= 0) list[idx] = { ...list[idx], ...customer };
    else list.push(customer);
  } else {
    list.push({ ...customer, id: Date.now() });
  }
  data.customers = list;
  await saveData(data);
}

// ─── Orders ──────────────────────────────────────────────────
export async function getOrders() {
  return (await getData()).orders || [];
}

export async function submitOrder(form) {
  const data = await getData();

  // رفض الطلب إذا كان للزبون طلب نشط
  const activeOrder = (data.orders || []).find(
    (o) => o.phone === form.phone && o.status !== 'delivered'
  );
  if (activeOrder) {
    throw new Error('لديك طلب قيد التوصيل، انتظر حتى يتم تسليمه');
  }

  const order = {
    ...form,
    id: Date.now(),
    createdAt: new Date().toISOString(),
    status: 'new',
  };
  data.orders = [...(data.orders || []), order];

  // update customer registry
  const customers = data.customers || [];
  const idx = customers.findIndex((c) => c.phone === form.phone);
  if (idx >= 0) {
    customers[idx].name = form.name || customers[idx].name;
    customers[idx].lastOrder = order.createdAt;
    customers[idx].orderCount = (customers[idx].orderCount || 0) + 1;
  } else {
    customers.push({
      id: Date.now() + 1,
      phone: form.phone,
      name: form.name || '',
      lastOrder: order.createdAt,
      orderCount: 1,
    });
  }
  data.customers = customers;
  await saveData(data);
  return order;
}

export async function updateOrderTruck(orderId, truckId) {
  invalidateCache();
  const data = await getData();
  const idx = (data.orders || []).findIndex((o) => o.id === orderId);
  if (idx < 0) return false;
  // رفض إذا استلمه سائق آخر قبله
  if (data.orders[idx].truckId) return false;
  data.orders[idx].truckId = Number(truckId);
  data.orders[idx].status = 'accepted';
  data.orders[idx].acceptedAt = new Date().toISOString();
  await saveData(data);
  return true;
}

export async function markOrderDelivered(orderId) {
  const data = await getData();
  const idx = (data.orders || []).findIndex((o) => o.id === orderId);
  if (idx >= 0) {
    data.orders[idx].status = 'delivered';
    data.orders[idx].deliveredAt = new Date().toISOString();
    const truck = (data.trucks || []).find(
      (t) => t.id === data.orders[idx].truckId
    );
    if (truck) {
      const ti = data.trucks.findIndex((t) => t.id === truck.id);
      data.trucks[ti].orderCount = (data.trucks[ti].orderCount || 0) + 1;
    }
  }
  await saveData(data);
}

// ─── Trucks ──────────────────────────────────────────────────
export async function getTrucks() {
  return (await getData()).trucks || [];
}

export async function saveTruck(truck) {
  const data = await getData();
  const list = data.trucks || [];
  if (truck.id) {
    const idx = list.findIndex((t) => t.id === truck.id);
    if (idx >= 0) list[idx] = { ...list[idx], ...truck };
    else list.push(truck);
  } else {
    const token = Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 10);
    list.push({ ...truck, id: Date.now(), orderCount: 0, token });
  }
  data.trucks = list;
  await saveData(data);
}

export async function getTruckByToken(token) {
  const trucks = await getTrucks();
  return trucks.find((t) => t.token === token) || null;
}

export async function deleteTruck(truckId) {
  const data = await getData();
  data.trucks = (data.trucks || []).filter((t) => t.id !== truckId);
  await saveData(data);
}
