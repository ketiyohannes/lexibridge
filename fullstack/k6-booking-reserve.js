import http from "k6/http";
import { check, sleep } from "k6";
import crypto from "k6/crypto";

export const options = {
  scenarios: {
    booking_reserve_load: {
      executor: "constant-arrival-rate",
      duration: "1m",
      rate: 5,
      timeUnit: "1s",
      preAllocatedVUs: 5,
      maxVUs: 30,
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<1000"],
    http_req_failed: ["rate<0.05"],
  },
};

const baseUrl = __ENV.BASE_URL || "http://localhost:8081";
const clientKey = __ENV.CLIENT_KEY || "demo-device";
const keyVersion = __ENV.KEY_VERSION || "1";
const sharedSecret = __ENV.SHARED_SECRET || "demo-device-shared-secret";

function isoOffset(minutes) {
  const d = new Date(Date.now() + minutes * 60 * 1000);
  return d.toISOString().replace("Z", "");
}

export default function () {
  const path = "/api/v1/bookings";
  const method = "POST";
  const timestamp = `${Math.floor(Date.now() / 1000)}`;
  const nonce = `${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 8)}`;
  const signaturePayload = `${method}|${path}|${timestamp}|${nonce}`;
  const signature = crypto.hmac("sha256", sharedSecret, signaturePayload, "hex");

  const payload = JSON.stringify({
    locationId: 1,
    createdBy: 1,
    customerName: `k6-user-${__VU}-${__ITER}`,
    customerPhone: "5550001234",
    startAt: isoOffset(60 + (__ITER % 8) * 15),
    durationMinutes: 60,
    orderNote: "k6 load",
  });

  const res = http.post(`${baseUrl}${path}`, payload, {
    headers: {
      "Content-Type": "application/json",
      "X-Client-Key": clientKey,
      "X-Key-Version": keyVersion,
      "X-Timestamp": timestamp,
      "X-Nonce": nonce,
      "X-Signature": signature,
    },
  });

  check(res, {
    "status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500,
  });

  sleep(0.2);
}
