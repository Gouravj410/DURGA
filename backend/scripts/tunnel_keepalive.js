/**
 * DURGA Tunnel Activity Heartbeat Keeper
 * Periodically issues genuine HTTP GET requests through the public reverse proxy tunnel
 * to prevent the remote edge gateway from timing out due to zero traffic (tunnel activity timeout).
 */

const https = require("https");

const TUNNEL_URL = process.argv[2] || "https://ec06773af1c187.lhr.life/api/v1/ping";
const INTERVAL_MS = 60 * 1000; // 60 seconds

console.log(`[TUNNEL KEEPALIVE] Monitoring active endpoint: ${TUNNEL_URL}`);

function sendHeartbeat() {
  try {
    const req = https.get(TUNNEL_URL, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        const time = new Date().toLocaleTimeString();
        if (res.statusCode === 200) {
          console.log(`[${time}] Heartbeat SUCCESS (HTTP 200) - Tunnel active`);
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
setInterval(sendHeartbeat, INTERVAL_MS);
