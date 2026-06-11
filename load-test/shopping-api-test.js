import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 10 },
    { duration: '3m', target: 10 },
    { duration: '1m', target: 50 },
    { duration: '3m', target: 50 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = 'http://192.168.64.10:32000';

export function setup() {
  // 테스트용 계정 생성
  http.post(`${BASE_URL}/api/users/register`, JSON.stringify({
    email: 'loadtest@test.com',
    password: 'test1234',
    name: '부하테스트'
  }), { headers: { 'Content-Type': 'application/json' } });

  // 로그인
  const loginRes = http.post(`${BASE_URL}/api/users/login`, JSON.stringify({
    email: 'loadtest@test.com',
    password: 'test1234'
  }), { headers: { 'Content-Type': 'application/json' } });

  const token = JSON.parse(loginRes.body).accessToken;
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };

  // 상품 5개 미리 등록 (stock 충분히)
  const productIds = [];
  for (let i = 1; i <= 5; i++) {
    const res = http.post(`${BASE_URL}/api/products`, JSON.stringify({
      name: `loadtest_product_${i}`,
      description: '부하 테스트용 상품',
      price: 10000 * i,
      stock: 999999,
    }), { headers });
    productIds.push(JSON.parse(res.body).id);
  }

  return { token, productIds };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  const rand = Math.random();

  if (rand < 0.7) {
    // 70% - 상품 목록 조회
    const res = http.get(`${BASE_URL}/api/products`, { headers });
    check(res, { 'products 200': (r) => r.status === 200 });

  } else if (rand < 0.9) {
    // 20% - 주문 생성
    const productId = data.productIds[Math.floor(Math.random() * data.productIds.length)];
    const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify({
      productId,
      quantity: 1,
    }), { headers });
    check(res, { 'order created': (r) => r.status === 201 || r.status === 200 });

  } else {
    // 10% - 주문 목록 조회
    const res = http.get(`${BASE_URL}/api/orders`, { headers });
    check(res, { 'orders 200': (r) => r.status === 200 });
  }

  sleep(1);
}