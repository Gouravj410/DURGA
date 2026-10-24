/**
 * DURGA Responder Command Portal — Client Logic
 * Real-time SSE telemetry, Leaflet GIS mapping, Audio Playback, and Audit Trail.
 */

let map = null;
let victimMarker = null;
let geofenceCircle = null;
let policeMarkers = [];
let routePolyline = null;

let authToken = localStorage.getItem("durga_responder_token");
let currentResponder = JSON.parse(localStorage.getItem("durga_responder_user") || "null");

let activeIncidents = [];
let selectedIncidentId = null;
let sseSource = null;

// DOM Elements
const incidentsListEl = document.getElementById("incidents-list");
const activeCountEl = document.getElementById("active-incident-count");
const sseLabelEl = document.getElementById("live-stream-label");
const sseDotEl = document.getElementById("live-stream-dot");
const authContainerEl = document.getElementById("auth-status-container");

const loginModal = document.getElementById("login-modal");
const loginPhoneInput = document.getElementById("login-phone");
const loginOtpInput = document.getElementById("login-otp");
const loginErrorEl = document.getElementById("login-error");
const btnSubmitLogin = document.getElementById("btn-submit-login");

const resolveModal = document.getElementById("resolve-modal");
const resolveNotesInput = document.getElementById("resolve-notes");
const btnCancelResolve = document.getElementById("btn-cancel-resolve");
const btnConfirmResolve = document.getElementById("btn-confirm-resolve");

const btnAcknowledge = document.getElementById("btn-acknowledge");
const btnResolve = document.getElementById("btn-resolve");
const btnOpenNav = document.getElementById("btn-open-nav");

const selectedTitleEl = document.getElementById("selected-incident-title");
const selectedSubEl = document.getElementById("selected-incident-sub");

// Telemetry Elements
const telStatus = document.getElementById("tel-status");
const telAccuracy = document.getElementById("tel-accuracy");
const telPhone = document.getElementById("tel-phone");
const telTrigger = document.getElementById("tel-trigger");
const telStationName = document.getElementById("tel-station-name");
const telStationDist = document.getElementById("tel-station-dist");
const telStationPhone = document.getElementById("tel-station-phone");
const telAiClass = document.getElementById("tel-ai-class");
const telAiScore = document.getElementById("tel-ai-score");
const audioPlayer = document.getElementById("audio-player");
const audioStatusLabel = document.getElementById("audio-status-label");
const auditLogList = document.getElementById("audit-log-list");

// ==============================================================================
// 1. Initialization
// ==============================================================================

window.addEventListener("DOMContentLoaded", () => {
  initMap();
  initAuthUI();
  fetchPoliceStations();
  fetchActiveIncidents();
  connectSse();
  setupEventListeners();

  // Periodic fallback refresh every 4 seconds
  setInterval(fetchActiveIncidents, 4000);
});

function initMap() {
  // Center on Pune, Maharashtra by default
  map = L.map("map-container").setView([18.5204, 73.8567], 13);

  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: "&copy; OpenStreetMap contributors",
  }).addTo(map);
}

function initAuthUI() {
  if (authToken && currentResponder) {
    authContainerEl.innerHTML = `
      <div style="display:flex; align-items:center; gap:8px;">
        <span style="font-size:12px; font-weight:600; color:#34d399;">🛡️ ${escapeHtml(currentResponder.name)}</span>
        <button id="btn-logout" class="btn" style="padding:4px 8px; font-size:11px;">Logout</button>
      </div>
    `;
    document.getElementById("btn-logout").addEventListener("click", handleLogout);
    loginModal.style.display = "none";
  } else {
    authContainerEl.innerHTML = `
      <button id="btn-show-login" class="btn btn-primary" style="padding:4px 10px; font-size:12px;">Responder Login</button>
    `;
    document.getElementById("btn-show-login").addEventListener("click", () => {
      loginModal.style.display = "flex";
    });
    // Prompt login
    loginModal.style.display = "flex";
  }
}

// ==============================================================================
// 2. Real-Time Telemetry (SSE & Polling)
// ==============================================================================

function connectSse() {
  if (sseSource) {
    sseSource.close();
  }

  sseSource = new EventSource("/api/v1/events/stream");

  sseSource.onopen = () => {
    sseLabelEl.textContent = "SSE FEED: ACTIVE";
    sseDotEl.classList.add("active");
  };

  sseSource.onerror = () => {
    sseLabelEl.textContent = "SSE FEED: RECONNECTING";
    sseDotEl.classList.remove("active");
  };

  sseSource.addEventListener("incident_created", (e) => {
    const data = JSON.parse(e.data);
    console.log("[SSE] Incident created:", data);
    fetchActiveIncidents();
    playAlertTone();
  });

  sseSource.addEventListener("incident_escalated", (e) => {
    const data = JSON.parse(e.data);
    console.log("[SSE] Incident escalated (Repeat SOS):", data);
    fetchActiveIncidents();
    playAlertTone();
  });

  sseSource.addEventListener("location_updated", (e) => {
    const data = JSON.parse(e.data);
    if (selectedIncidentId === data.incidentId) {
      updateVictimMarker(data.latitude, data.longitude, data.accuracy);
    }
  });

  sseSource.addEventListener("audio_ready", (e) => {
    const data = JSON.parse(e.data);
    if (selectedIncidentId === data.incidentId) {
      loadIncidentAudio(data.incidentId);
    }
  });

  sseSource.addEventListener("incident_acknowledged", () => fetchActiveIncidents());
  sseSource.addEventListener("incident_resolved", () => fetchActiveIncidents());
  sseSource.addEventListener("incident_cancelled", () => fetchActiveIncidents());
}

function playAlertTone() {
  try {
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = "sine";
    osc.frequency.setValueAtTime(880, audioCtx.currentTime); // High alert beep
    gain.gain.setValueAtTime(0.2, audioCtx.currentTime);
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.35);
  } catch (e) {
    // Audio context may require user gesture
  }
}

// ==============================================================================
// 3. API Requests & Incident Queue
// ==============================================================================

async function fetchActiveIncidents() {
  try {
    const res = await fetch("/api/v1/incidents/active");
    if (!res.ok) return;
    const incidents = await res.json();
    activeIncidents = incidents;
    renderIncidentsQueue(incidents);

    // If an incident is currently selected, re-render its details
    if (selectedIncidentId) {
      const current = incidents.find((i) => i.id === selectedIncidentId);
      if (current) {
        selectIncident(current, false);
      }
    } else if (incidents.length > 0) {
      selectIncident(incidents[0], true);
    }
  } catch (err) {
    console.error("Error fetching active incidents:", err);
  }
}

function renderIncidentsQueue(incidents) {
  activeCountEl.textContent = incidents.length;
  if (incidents.length === 0) {
    incidentsListEl.innerHTML = `
      <div style="padding: 24px; text-align: center; color: var(--text-dim);">
        No active emergency incidents reported.
      </div>
    `;
    return;
  }

  incidentsListEl.innerHTML = incidents
    .map((inc) => {
      const isSelected = inc.id === selectedIncidentId;
      const isRepeat = inc.status === "REPEAT_ACTIVE";
      const timeStr = new Date(inc.created_at).toLocaleTimeString();

      return `
      <div class="incident-card ${isSelected ? "active" : ""} ${isRepeat ? "repeat-active" : ""}"
           onclick="handleIncidentCardClick('${inc.id}')">
        <div class="incident-card-top">
          <span class="incident-id">${escapeHtml(inc.id)}</span>
          <span class="status-badge ${escapeHtml(inc.status)}">${escapeHtml(inc.status)}</span>
        </div>
        <div class="incident-card-body">
          <strong>${escapeHtml(inc.user_name)}</strong> (${escapeHtml(inc.user_phone)})
        </div>
        <div class="incident-meta-row">
          <span>${escapeHtml(inc.nearest_police_station || "Nearest PS")}</span>
          <span>${timeStr}</span>
        </div>
      </div>
    `;
    })
    .join("");
}

window.handleIncidentCardClick = (id) => {
  const inc = activeIncidents.find((i) => i.id === id);
  if (inc) {
    selectIncident(inc, true);
  }
};

async function selectIncident(inc, panMap = true) {
  selectedIncidentId = inc.id;

  // Highlight card in list
  document.querySelectorAll(".incident-card").forEach((card) => {
    card.classList.toggle("active", card.innerText.includes(inc.id));
  });

  // Action Bar Update
  selectedTitleEl.textContent = `${inc.user_name} — ${inc.id}`;
  selectedSubEl.textContent = `Triggered via ${inc.trigger_source} at ${new Date(inc.created_at).toLocaleTimeString()}`;

  // Telemetry Panel
  telStatus.textContent = inc.status;
  telStatus.className = `telemetry-value ${inc.status}`;
  telAccuracy.textContent = inc.location_accuracy ? `±${inc.location_accuracy.toFixed(1)} m` : "Acquiring...";
  telPhone.textContent = inc.user_phone || "Unknown";
  telTrigger.textContent = inc.trigger_source || "MANUAL_SOS_BUTTON";

  telStationName.textContent = inc.nearest_police_station || "Central Police Station";
  telStationDist.textContent = inc.nearest_police_station_distance
    ? `${inc.nearest_police_station_distance} km`
    : "Computing...";
  telStationPhone.textContent = "+91-20-26123456";

  telAiClass.textContent = inc.distress_classification || "Pending Local Inference";
  telAiScore.textContent = inc.distress_score ? `${(inc.distress_score * 100).toFixed(1)}%` : "N/A";

  // Enable/Disable Action Buttons
  btnOpenNav.disabled = !(inc.latitude && inc.longitude);
  btnAcknowledge.disabled = !authToken || inc.status !== "ACTIVE" && inc.status !== "REPEAT_ACTIVE";
  btnResolve.disabled = !authToken || inc.status === "RESOLVED";

  // Map Rendering
  if (inc.latitude && inc.longitude) {
    updateVictimMarker(inc.latitude, inc.longitude, inc.location_accuracy);
    if (panMap) {
      map.setView([inc.latitude, inc.longitude], 15);
    }
  }

  // Audio Playback
  loadIncidentAudio(inc.id);

  // Audit Logs
  loadAuditLogs(inc.id);
}

function updateVictimMarker(lat, lon, accuracy) {
  if (victimMarker) {
    map.removeLayer(victimMarker);
  }
  if (geofenceCircle) {
    map.removeLayer(geofenceCircle);
  }

  // Red Incident Marker
  const customIcon = L.divIcon({
    className: "custom-victim-pin",
    html: `<div style="background:#dc2626; width:18px; height:18px; border-radius:50%; border:3px solid #fff; box-shadow:0 0 12px rgba(220,38,38,0.8);"></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  });

  victimMarker = L.marker([lat, lon], { icon: customIcon }).addTo(map);
  victimMarker.bindPopup(`<strong>Victim Location</strong><br>Accuracy: ±${accuracy || 10}m`).openPopup();

  // 1.0 km Geofence Radius Circle (Confirmed project scope)
  geofenceCircle = L.circle([lat, lon], {
    color: "#dc2626",
    fillColor: "#ef4444",
    fillOpacity: 0.12,
    radius: 1000, // exactly 1,000 meters
    weight: 2,
    dashArray: "4, 6",
  }).addTo(map);
}

async function loadIncidentAudio(incidentId) {
  const audioUrl = `/api/v1/incidents/${incidentId}/audio`;
  audioPlayer.src = audioUrl;
  audioStatusLabel.textContent = `Streaming protected audio from incident: ${incidentId}`;
}

async function loadAuditLogs(incidentId) {
  try {
    const res = await fetch(`/api/v1/incidents/${incidentId}`);
    if (!res.ok) return;
    const data = await res.json();

    if (data.auditTrail && data.auditTrail.length > 0) {
      auditLogList.innerHTML = data.auditTrail
        .map((log) => {
          const time = new Date(log.timestamp).toLocaleTimeString();
          return `
          <div class="audit-entry">
            <span class="audit-time">[${time}]</span>
            <span class="audit-action">${escapeHtml(log.action)}</span> by
            <span style="color:#60a5fa;">${escapeHtml(log.actor_role)}</span>
            <div class="audit-details">${escapeHtml(log.details || "")}</div>
          </div>
        `;
        })
        .join("");
    } else {
      auditLogList.innerHTML = `<div style="color:var(--text-dim); padding:6px 0;">No audit records found.</div>`;
    }
  } catch (err) {
    console.error("Error loading audit logs:", err);
  }
}

async function fetchPoliceStations() {
  try {
    const res = await fetch("/api/v1/police-stations");
    if (!res.ok) return;
    const stations = await res.json();

    for (const s of stations) {
      const stationIcon = L.divIcon({
        className: "custom-station-pin",
        html: `<div style="background:#2563eb; color:#fff; font-size:10px; font-weight:bold; width:22px; height:22px; border-radius:4px; display:flex; align-items:center; justify-content:center; border:2px solid #fff;">PS</div>`,
        iconSize: [22, 22],
        iconAnchor: [11, 11],
      });

      const m = L.marker([s.latitude, s.longitude], { icon: stationIcon }).addTo(map);
      m.bindPopup(`<strong>${escapeHtml(s.name)}</strong><br>${escapeHtml(s.address)}<br>Phone: ${escapeHtml(s.phone)}<br><small>Verified: ${s.verified_date}</small>`);
      policeMarkers.push(m);
    }
  } catch (err) {
    console.error("Error fetching police stations:", err);
  }
}

// ==============================================================================
// 4. Action Handlers (Acknowledge, Resolve, Navigation)
// ==============================================================================

function setupEventListeners() {
  // Navigation
  btnOpenNav.addEventListener("click", () => {
    const inc = activeIncidents.find((i) => i.id === selectedIncidentId);
    if (inc && inc.latitude && inc.longitude) {
      const navUrl = `https://www.google.com/maps/dir/?api=1&destination=${inc.latitude},${inc.longitude}`;
      window.open(navUrl, "_blank");
    }
  });

  // Acknowledge
  btnAcknowledge.addEventListener("click", async () => {
    if (!selectedIncidentId || !authToken) return;
    try {
      btnAcknowledge.disabled = true;
      const res = await fetch(`/api/v1/incidents/${selectedIncidentId}/acknowledge`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${authToken}`,
          "Content-Type": "application/json",
        },
      });
      if (res.ok) {
        fetchActiveIncidents();
      } else {
        const err = await res.json();
        alert(`Error acknowledging incident: ${err.error}`);
      }
    } catch (e) {
      alert(`Network error: ${e.message}`);
    } finally {
      btnAcknowledge.disabled = false;
    }
  });

  // Open Resolve Modal
  btnResolve.addEventListener("click", () => {
    if (!selectedIncidentId || !authToken) return;
    resolveNotesInput.value = "On-site responder arrived. Citizen verified safe.";
    resolveModal.style.display = "flex";
  });

  btnCancelResolve.addEventListener("click", () => {
    resolveModal.style.display = "none";
  });

  // Confirm Resolution
  btnConfirmResolve.addEventListener("click", async () => {
    if (!selectedIncidentId || !authToken) return;
    const notes = resolveNotesInput.value.trim();
    if (!notes) {
      alert("Resolution notes are mandatory for the audit log.");
      return;
    }

    try {
      btnConfirmResolve.disabled = true;
      const res = await fetch(`/api/v1/incidents/${selectedIncidentId}/resolve`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${authToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ notes }),
      });

      if (res.ok) {
        resolveModal.style.display = "none";
        fetchActiveIncidents();
      } else {
        const err = await res.json();
        alert(`Error resolving incident: ${err.error}`);
      }
    } catch (e) {
      alert(`Network error: ${e.message}`);
    } finally {
      btnConfirmResolve.disabled = false;
    }
  });

  // Login Submit
  btnSubmitLogin.addEventListener("click", handleLogin);
}

async function handleLogin() {
  const phone = loginPhoneInput.value.trim();
  const otp = loginOtpInput.value.trim();

  loginErrorEl.style.display = "none";
  btnSubmitLogin.disabled = true;

  try {
    const res = await fetch("/api/v1/auth/verify-otp", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ phone, otp, role: "RESPONDER" }),
    });

    const data = await res.json();
    if (res.ok && data.token) {
      authToken = data.token;
      currentResponder = data.user;
      localStorage.setItem("durga_responder_token", data.token);
      localStorage.setItem("durga_responder_user", JSON.stringify(data.user));

      loginModal.style.display = "none";
      initAuthUI();
      fetchActiveIncidents();
    } else {
      loginErrorEl.textContent = data.error || "Login failed.";
      loginErrorEl.style.display = "block";
    }
  } catch (err) {
    loginErrorEl.textContent = `Network error: ${err.message}`;
    loginErrorEl.style.display = "block";
  } finally {
    btnSubmitLogin.disabled = false;
  }
}

function handleLogout() {
  authToken = null;
  currentResponder = null;
  localStorage.removeItem("durga_responder_token");
  localStorage.removeItem("durga_responder_user");
  initAuthUI();
  fetchActiveIncidents();
}

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
