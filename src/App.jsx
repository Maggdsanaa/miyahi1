import OrderForm from './OrderForm';
import Admin from './Admin';
import Driver from './Driver';

export default function App() {
  const path = window.location.pathname;
  if (path === '/admin') return <Admin />;
  if (path.startsWith('/driver')) return <Driver />;
  return <OrderForm />;
}
