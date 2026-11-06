import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500', 'avg<200'],
    },
};

export default function () {
    const url = 'http://localhost:8080/shipments';

    const payload = JSON.stringify({
        customerId: 1,
        parcelId: 1,
        reference: `REF-${Math.floor(Math.random() * 100000)}`,
        distanceKm: 15,
        urgent: false,
        pickup: true,
        couponValid: false
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    // 1. Створення shipment
    const createRes = http.post(url, payload, params);
    check(createRes, {
        'created status is 200 or 201': (r) => r.status === 200 || r.status === 201,
    });

    let shipmentId;
    try {
        shipmentId = createRes.json().id;
    } catch (e) {
        shipmentId = null;
    }

    // 2. Отримання shipment за згенерованим ID
    if (shipmentId) {
        const getRes = http.get(`${url}/${shipmentId}`);
        check(getRes, {
            'get status is 200': (r) => r.status === 200,
        });
    }

    sleep(0.1);
}