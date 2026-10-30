/**
 * DURGA Tunnel Activity Heartbeat Keeper
 * Periodically issues HTTP/HTTPS GET requests through a configured reverse proxy tunnel
 * or local endpoint to maintain gateway activity and monitor uptime.
 */

const http = require("http");
const https = require("https");

const rawUrl = process.argv[2] || process.env.DURGA_TUNNEL_URL || "http://localhost:3000/api/v1/ping";
const TUNNEL_URL = rawUrl.endsWith("/ping") ? rawUrl : `${rawUrl.replace(/\/+$/, "")}/ping`;
const INTERVAL_MS = parseInt(process.env.DURGA_KEEPALIVE_INTERVAL_MS, 10) || 60 * 1000; // 60s

console.log(`[TUNNEL KEEPALIVE] Monitoring active endpoint: ${TUNNEL_URL}`);

function sendHeartbeat() {
  try {
    const client = TUNNEL_URL.startsWith("https") ? https : http;
    const req = client.get(TUNNEL_URL, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        const time = new Date().toLocaleTimeString();
        if (res.statusCode === 200) {
          console.log(`[${time}] Heartbeat SUCCESS (HTTP 200) - Endpoint active`);
        } else {
          console.warn(`[${time}] Heartbeat WARN (HTTP ${res.statusCode})`);
        }
      });
    });

    req.on("error", (err) => {
      console.error(`[${new Date().toLocaleTimeString()}] Heartbeat ERROR: ${err.message}`);
    });

    req.setTimeout(10000, () => {
      req.destroy();
      console.warn(`[${new Date().toLocaleTimeString()}] Heartbeat TIMEOUT`);
    });
  } catch (e) {
    console.error("[TUNNEL KEEPALIVE FATAL]", e.message);
  }
}

// Initial ping + interval
sendHeartbeat();
const timer = setInterval(sendHeartbeat, INTERVAL_MS);

process.on("SIGINT", () => {
  clearInterval(timer);
  process.exit(0);
});
process.on("SIGTERM", () => {
  clearInterval(timer);
  process.exit(0);
});
