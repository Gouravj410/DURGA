/**
 * DURGA - Dynamic Unified Response & Geo-Alert System
 * Interactive Laptop Showcase & System Simulation Engine
 */

// --- 1. Police Stations Dataset (Local Registry) ---
const POLICE_STATIONS = [
  {
    id: "PS-001",
    name: "Central Police Station",
    latitude: 18.5204,
    longitude: 73.8567,
    phone: "+91-20-26123456",
    address: "Shivajinagar, Pune, Maharashtra 411005"
  },
  {
    id: "PS-002",
    name: "Cantonment Police Station",
    latitude: 18.5089,
    longitude: 73.8795,
    phone: "+91-20-26361234",
    address: "Camp Area, Pune, Maharashtra 411001"
  },
  {
    id: "PS-003",
    name: "Kothrud Police Station",
    latitude: 18.5074,
    longitude: 73.8077,
    phone: "+91-20-25381100",
    address: "Paud Road, Kothrud, Pune, Maharashtra 411038"
  },
  {
    id: "PS-004",
    name: "Hinjawadi IT Park Police Station",
    latitude: 18.5912,
    longitude: 73.7389,
    phone: "+91-20-22934444",
    address: "Phase 1, Hinjawadi, Pune, Maharashtra 411057"
  },
  {
    id: "PS-005",
    name: "Viman Nagar Police Station",
    latitude: 18.5679,
    longitude: 73.9143,
    phone: "+91-20-26631010",
    address: "Viman Nagar, Pune, Maharashtra 411014"
  }
];

// --- 2. Location Presets ---
const LOCATION_PRESETS = {
  "shivajinagar": { name: "FC Road, Shivajinagar", lat: 18.5236, lng: 73.8415 },
  "hinjawadi": { name: "Quadron Business Park, Hinjawadi", lat: 18.5980, lng: 73.7220 },
  "kothrud": { name: "MIT Campus, Kothrud", lat: 18.5140, lng: 73.8150 },
  "camp": { name: "MG Road, Cantonment", lat: 18.5120, lng: 73.8780 },
  "vimannagar": { name: "Phoenix Mall, Viman Nagar", lat: 18.5605, lng: 73.9180 }
};

// --- 3. Master State Machine ---
const EmergencyState = {
  IDLE: "IDLE",
  TRIGGERED: "TRIGGERED",
  VERIFYING: "VERIFYING",
  CANCELLED: "CANCELLED",
  RECORDING: "RECORDING",
  ANALYZING: "ANALYZING",
  LOCATION_ACQUIRING: "LOCATION_ACQUIRING",
  ESCALATING: "ESCALATING",
  ACTIVE: "ACTIVE",
  ACKNOWLEDGED: "ACKNOWLEDGED",
  RESPONDING: "RESPONDING",
  RESOLVED: "RESOLVED"
};

// Application Global State
const appState = {
  currentState: EmergencyState.IDLE,
  victimLocation: { ...LOCATION_PRESETS["shivajinagar"] },
  nearestStation: null,
  distanceKm: 0,
  countdownTimer: null,
  countdownRemaining: 5.0,
  audioRecordingTimer: null,
  audioDurationSec: 5.0,
  audioElapsedSec: 0.0,
  distressResult: {
    score: 0.0,
    classification: "Normal Ambient",
    confidence: 0.0,
    dbfs: -96.0,
    peak: 0,
    zcr: 0.0
  },
  incidentId: null,
  incidentTimestamp: null,
  isMuted: false,
  liveMicActive: false,
  audioContext: null,
  micStream: null,
  recordedAudioBuffer: null,
  playbackSource: null,
  isPlayingRecordedAudio: false,
  patrolMarker: null,
  patrolInterval: null
};

// --- 4. Mathematical Geolocation Utilities (Haversine Formula) ---
function calculateHaversineDistanceKm(lat1, lon1, lat2, lon2) {
  const EARTH_RADIUS_KM = 6371.0;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const rLat1 = lat1 * Math.PI / 180;
  const rLat2 = lat2 * Math.PI / 180;

  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.sin(dLon / 2) * Math.sin(dLon / 2) *
            Math.cos(rLat1) * Math.cos(rLat2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_KM * c;
}

function findNearestPoliceStation(lat, lon) {
  let nearest = null;
  let minDistance = Infinity;

  POLICE_STATIONS.forEach(station => {
    const dist = calculateHaversineDistanceKm(lat, lon, station.latitude, station.longitude);
    if (dist < minDistance) {
      minDistance = dist;
      nearest = station;
    }
  });

  return { station: nearest, distanceKm: minDistance };
}

// --- 5. Web Audio Synthesis Engine ---
function getAudioContext() {
  if (!appState.audioContext) {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (AudioCtx) {
      appState.audioContext = new AudioCtx();
    }
  }
  if (appState.audioContext && appState.audioContext.state === 'suspended') {
    appState.audioContext.resume();
  }
  return appState.audioContext;
}

function playTickSound() {
  if (appState.isMuted) return;
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    gain.gain.setValueAtTime(0.15, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.08);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.08);
  } catch (e) {
    console.warn("Audio tick sound skipped:", e);
  }
}

function playAlarmSiren() {
  if (appState.isMuted) return;
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(650, ctx.currentTime);
    osc.frequency.linearRampToValueAtTime(950, ctx.currentTime + 0.25);
    osc.frequency.linearRampToValueAtTime(650, ctx.currentTime + 0.5);
    gain.gain.setValueAtTime(0.2, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.5);
  } catch (e) {
    console.warn("Audio alarm sound skipped:", e);
  }
}

function playChimeSound() {
  if (appState.isMuted) return;
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(523.25, ctx.currentTime);
    osc.frequency.setValueAtTime(659.25, ctx.currentTime + 0.1);
    gain.gain.setValueAtTime(0.2, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.4);
  } catch (e) {
    console.warn("Audio chime skipped:", e);
  }
}

/**
 * Synthesizes a realistic 5-second distress emergency recording buffer
 * with human vocalization frequencies, struggle harmonics, and ambient noise.
 */
function createSynthesizedDistressBuffer() {
  const ctx = getAudioContext();
  if (!ctx) return null;
  const sampleRate = ctx.sampleRate;
  const duration = 5.0;
  const numFrames = sampleRate * duration;
  const buffer = ctx.createBuffer(1, numFrames, sampleRate);
  const data = buffer.getChannelData(0);

  for (let i = 0; i < numFrames; i++) {
    const t = i / sampleRate;
    // Base ambient background noise
    let sample = (Math.random() * 2 - 1) * 0.08;

    // Vocal scream bursts at t=1.2s to 2.4s and t=3.2s to 4.5s
    if ((t > 1.0 && t < 2.5) || (t > 3.0 && t < 4.7)) {
      const screamFreq = 950 + Math.sin(t * 18) * 280;
      const formant1 = Math.sin(2 * Math.PI * screamFreq * t);
      const formant2 = Math.sin(2 * Math.PI * (screamFreq * 2.1) * t) * 0.45;
      const breathNoise = (Math.random() * 2 - 1) * 0.25;
      sample += (formant1 + formant2 + breathNoise) * 0.7;
    }
    data[i] = Math.max(-1, Math.min(1, sample));
  }
  return buffer;
}

// --- 6. Mathematical Acoustic Distress Engine ---
function computeAcousticDistressFromBuffer(channelData) {
  const numSamples = channelData.length;
  let sumSquares = 0;
  let peakFloat = 0;
  let zeroCrossings = 0;
  let prevSample = 0;

  for (let i = 0; i < numSamples; i++) {
    const s = channelData[i];
    sumSquares += s * s;
    const absS = Math.abs(s);
    if (absS > peakFloat) peakFloat = absS;

    if (i > 0 && ((prevSample >= 0 && s < 0) || (prevSample < 0 && s >= 0))) {
      zeroCrossings++;
    }
    prevSample = s;
  }

  const rmsFloat = Math.sqrt(sumSquares / numSamples);
  const peak16bit = Math.round(peakFloat * 32767);
  const rms16bit = rmsFloat * 32767;
  const normalizedRms = Math.min(1.0, rmsFloat);
  const dbfs = rmsFloat > 0.0 ? 20 * Math.log10(rmsFloat) : -96.0;
  const zcr = zeroCrossings / numSamples;

  let score = 0.0;
  let classification = "Normal Ambient";
  let signals = [];

  signals.push(`RMS Acoustic Power: ${dbfs.toFixed(1)} dBFS`);
  signals.push(`Peak Transient: ${peak16bit} / 32,767 (${((peak16bit/32767)*100).toFixed(1)}%)`);

  if (dbfs > -22.0 || peak16bit > 18000) {
    score = Math.min(0.98, Math.max(0.70, 0.75 + (normalizedRms * 0.5)));
    classification = "High Distress (High-Intensity Vocalization / Screaming detected)";
    signals.push("Elevated Acoustic Transient Spike");
    if (zcr > 0.12) signals.push("High-Frequency Acoustic Turbulence");
  } else if (dbfs > -34.0 || peak16bit > 7000) {
    score = Math.min(0.69, Math.max(0.40, 0.42 + (normalizedRms * 0.4)));
    classification = "Moderate Distress (Elevated Voice / Shouting detected)";
    signals.push("Elevated Vocal Decibel Range");
  } else {
    score = Math.min(0.39, Math.max(0.10, 0.15 + (normalizedRms * 0.3)));
    classification = "Low Distress (Ambient sound / Low vocal activity)";
    signals.push("Low Ambient Audio Level");
  }

  return {
    score,
    classification,
    confidence: 0.92,
    dbfs,
    peak: peak16bit,
    zcr,
    signals
  };
}

// --- 7. UI Update & Telemetry Audit Logging ---
function logAuditEvent(message, type = "normal") {
  const feed = document.getElementById("auditFeedScroll");
  if (!feed) return;
  const time = new Date().toLocaleTimeString();
  const entry = document.createElement("div");
  entry.className = `audit-entry ${type}`;
  entry.innerHTML = `
    <span class="time">[${time}]</span>
    <span class="msg">${message}</span>
  `;
  feed.prepend(entry);
}

function updatePipelineStepper(state) {
  const steps = [
    { id: "step-sense", name: "SENSE", states: [EmergencyState.TRIGGERED, EmergencyState.VERIFYING] },
    { id: "step-verify", name: "VERIFY", states: [EmergencyState.VERIFYING] },
    { id: "step-record", name: "RECORD", states: [EmergencyState.RECORDING] },
    { id: "step-assess", name: "ASSESS", states: [EmergencyState.ANALYZING] },
    { id: "step-locate", name: "LOCATE", states: [EmergencyState.LOCATION_ACQUIRING] },
    { id: "step-cloud", name: "CLOUD", states: [EmergencyState.ESCALATING] },
    { id: "step-alert", name: "ALERT", states: [EmergencyState.ACTIVE, EmergencyState.ACKNOWLEDGED] },
    { id: "step-respond", name: "RESPOND", states: [EmergencyState.RESPONDING, EmergencyState.RESOLVED] }
  ];

  let activeIndex = -1;
  switch (state) {
    case EmergencyState.IDLE: activeIndex = -1; break;
    case EmergencyState.TRIGGERED:
    case EmergencyState.VERIFYING: activeIndex = 1; break;
    case EmergencyState.RECORDING: activeIndex = 2; break;
    case EmergencyState.ANALYZING: activeIndex = 3; break;
    case EmergencyState.LOCATION_ACQUIRING: activeIndex = 4; break;
    case EmergencyState.ESCALATING: activeIndex = 5; break;
    case EmergencyState.ACTIVE: activeIndex = 6; break;
    case EmergencyState.ACKNOWLEDGED:
    case EmergencyState.RESPONDING: activeIndex = 7; break;
    case EmergencyState.RESOLVED: activeIndex = 8; break;
    case EmergencyState.CANCELLED: activeIndex = -1; break;
  }

  const nodes = document.querySelectorAll(".step-node");
  nodes.forEach((node, idx) => {
    node.classList.remove("active", "completed");
    if (activeIndex > idx) {
      node.classList.add("completed");
    } else if (activeIndex === idx) {
      node.classList.add("active");
    }
  });
}

function setEmergencyState(newState) {
  appState.currentState = newState;
  updatePipelineStepper(newState);

  const userStatusPill = document.getElementById("userStatusPill");
  const contactStatusPill = document.getElementById("contactStatusPill");
  const responderStatusPill = document.getElementById("responderStatusPill");
  const userDeviceCard = document.getElementById("userDeviceCard");
  const contactAlertBanner = document.getElementById("contactAlertBanner");
  const countdownOverlay = document.getElementById("countdownOverlay");
  const cadIncidentCard = document.getElementById("cadIncidentCard");

  // Reset basic styles
  if (userStatusPill) userStatusPill.textContent = newState;
  if (contactStatusPill) contactStatusPill.textContent = newState;
  if (responderStatusPill) responderStatusPill.textContent = newState;

  switch (newState) {
    case EmergencyState.IDLE:
      userDeviceCard?.classList.remove("emergency-active");
      countdownOverlay?.classList.remove("show");
      contactAlertBanner?.classList.remove("show");
      cadIncidentCard?.classList.remove("active-alert");
      resetPoliceCadButtons();
      dismissNearbyMeshCitizenAlert();
      if (userStatusPill) { userStatusPill.textContent = "IDLE"; userStatusPill.className = "status-pill idle"; }
      if (contactStatusPill) { contactStatusPill.textContent = "IDLE"; contactStatusPill.className = "status-pill idle"; }
      if (responderStatusPill) { responderStatusPill.textContent = "IDLE"; responderStatusPill.className = "status-pill idle"; }
      logAuditEvent("System initialized in IDLE monitoring mode across all 4 mesh nodes.", "normal");
      break;

    case EmergencyState.TRIGGERED:
    case EmergencyState.VERIFYING:
      userDeviceCard?.classList.add("emergency-active");
      countdownOverlay?.classList.add("show");
      if (userStatusPill) { userStatusPill.textContent = "VERIFYING (5s)"; userStatusPill.className = "status-pill warning"; }
      startCountdownTimer();
      logAuditEvent("Gesture/SOS trigger activated on Victim device. 5-second countdown verification initiated.", "warning");
      break;

    case EmergencyState.CANCELLED:
      countdownOverlay?.classList.remove("show");
      userDeviceCard?.classList.remove("emergency-active");
      if (userStatusPill) { userStatusPill.textContent = "CANCELLED"; userStatusPill.className = "status-pill idle"; }
      logAuditEvent("False alarm cancelled by user within verification window. Incident aborted.", "success");
      setTimeout(() => setEmergencyState(EmergencyState.IDLE), 1500);
      break;

    case EmergencyState.RECORDING:
      countdownOverlay?.classList.remove("show");
      if (userStatusPill) { userStatusPill.textContent = "RECORDING (5s)"; userStatusPill.className = "status-pill warning"; }
      logAuditEvent("Verification window expired. Low-level 16-bit PCM Audio capture started (5 sec).", "warning");
      startAudioCapturePhase();
      break;

    case EmergencyState.ANALYZING:
      if (userStatusPill) { userStatusPill.textContent = "ANALYZING AI"; userStatusPill.className = "status-pill warning"; }
      logAuditEvent("Audio recording finished. AI Acoustic Scoring Engine computing RMS, dBFS, and ZCR.", "warning");
      analyzeCapturedAudio();
      break;

    case EmergencyState.LOCATION_ACQUIRING:
      if (userStatusPill) { userStatusPill.textContent = "LOCATING GPS"; userStatusPill.className = "status-pill warning"; }
      logAuditEvent("GPS coordinates locked. Computing nearest police station via Haversine formula.", "normal");
      acquireLocationAndComputeStation();
      break;

    case EmergencyState.ESCALATING:
      if (userStatusPill) { userStatusPill.textContent = "BROADCASTING"; userStatusPill.className = "status-pill warning"; }
      logAuditEvent("Packaging Incident Telemetry payload for Cloud & Community Mesh escalation.", "warning");
      setTimeout(() => setEmergencyState(EmergencyState.ACTIVE), 600);
      break;

    case EmergencyState.ACTIVE:
      if (userStatusPill) { userStatusPill.textContent = "SOS ACTIVE"; userStatusPill.className = "status-pill emergency"; }
      if (contactStatusPill) { contactStatusPill.textContent = "EMERGENCY"; contactStatusPill.className = "status-pill emergency"; }
      if (responderStatusPill) { responderStatusPill.textContent = "CAD ALERT"; responderStatusPill.className = "status-pill emergency"; }

      contactAlertBanner?.classList.add("show");
      cadIncidentCard?.classList.add("active-alert");
      playAlarmSiren();
      enablePoliceCadAcknowledge();
      updateCadIncidentDetails();
      triggerNearbyMeshCitizenAlert();

      logAuditEvent(`🚨 CRITICAL EMERGENCY ACTIVE! Broadcasted across 4-Node Ecosystem: Victim (Ananya) ➔ Nearby Citizen (Priya) ➔ Guardian (Rahul) ➔ Police CAD (${appState.nearestStation ? appState.nearestStation.name : 'Central Dispatch'}).`, "critical");
      break;

    case EmergencyState.ACKNOWLEDGED:
      if (responderStatusPill) { responderStatusPill.textContent = "ACKNOWLEDGED"; responderStatusPill.className = "status-pill warning"; }
      logAuditEvent("Police Dispatcher ACKNOWLEDGED the incoming emergency.", "warning");
      enablePoliceCadDispatch();
      break;

    case EmergencyState.RESPONDING:
      if (responderStatusPill) { responderStatusPill.textContent = "PATROL EN ROUTE"; responderStatusPill.className = "status-pill emergency"; }
      logAuditEvent("Patrol Unit DISPATCHED! Cruiser en route with sirens enabled.", "critical");
      enablePoliceCadResolve();
      startPatrolMovementSimulation();
      break;

    case EmergencyState.RESOLVED:
      if (userStatusPill) { userStatusPill.textContent = "RESOLVED"; userStatusPill.className = "status-pill idle"; }
      if (contactStatusPill) { contactStatusPill.textContent = "RESOLVED"; contactStatusPill.className = "status-pill idle"; }
      if (responderStatusPill) { responderStatusPill.textContent = "CASE ARCHIVED"; responderStatusPill.className = "status-pill idle"; }
      logAuditEvent("Incident successfully RESOLVED by responding units. Report archived.", "success");
      stopPatrolMovement();
      setTimeout(() => {
        setEmergencyState(EmergencyState.IDLE);
      }, 4000);
      break;
  }
}

// --- 8. Verification Countdown Logic ---
function startCountdownTimer() {
  if (appState.countdownTimer) clearInterval(appState.countdownTimer);
  appState.countdownRemaining = 5.0;

  const countNum = document.getElementById("countdownNumber");
  const progCircle = document.getElementById("countdownProgCircle");
  const circumference = 339; // 2 * PI * 54

  if (progCircle) progCircle.style.strokeDashoffset = "0";
  if (countNum) countNum.textContent = "5";

  playTickSound();

  appState.countdownTimer = setInterval(() => {
    appState.countdownRemaining -= 0.1;
    if (appState.countdownRemaining <= 0) {
      clearInterval(appState.countdownTimer);
      appState.countdownTimer = null;
      if (countNum) countNum.textContent = "0";
      setEmergencyState(EmergencyState.RECORDING);
    } else {
      const wholeSec = Math.ceil(appState.countdownRemaining);
      if (countNum && countNum.textContent !== String(wholeSec)) {
        countNum.textContent = String(wholeSec);
        playTickSound();
      }
      if (progCircle) {
        const fraction = (5.0 - appState.countdownRemaining) / 5.0;
        progCircle.style.strokeDashoffset = String(circumference * fraction);
      }
    }
  }, 100);
}

function cancelCountdown() {
  if (appState.countdownTimer) {
    clearInterval(appState.countdownTimer);
    appState.countdownTimer = null;
  }
  setEmergencyState(EmergencyState.CANCELLED);
}

// --- 9. Audio Capture & Waveform Visualizer ---
let audioAnimFrame = null;
let simulatedWavePhase = 0;

function startAudioCapturePhase() {
  appState.audioElapsedSec = 0;
  const audioIndicator = document.getElementById("audioIndicatorText");
  if (audioIndicator) audioIndicator.textContent = "RECORDING (5s PCM)...";
  renderWaveform(true);

  // Record for 5 seconds
  const startTime = Date.now();
  const captureInterval = setInterval(() => {
    const elapsed = (Date.now() - startTime) / 1000;
    appState.audioElapsedSec = Math.min(5.0, elapsed);

    if (elapsed >= 5.0) {
      clearInterval(captureInterval);
      if (audioIndicator) audioIndicator.textContent = "RECORDING COMPLETE";
      renderWaveform(false);
      setEmergencyState(EmergencyState.ANALYZING);
    }
  }, 100);
}

function renderWaveform(isActive) {
  const canvas = document.getElementById("waveformCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;

  if (!isActive) {
    ctx.fillStyle = "#070B13";
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = "#334155";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(0, h / 2);
    ctx.lineTo(w, h / 2);
    ctx.stroke();
    return;
  }

  function draw() {
    if (appState.currentState !== EmergencyState.RECORDING) return;
    simulatedWavePhase += 0.15;
    ctx.fillStyle = "#070B13";
    ctx.fillRect(0, 0, w, h);

    ctx.strokeStyle = "#8B5CF6";
    ctx.lineWidth = 2;
    ctx.beginPath();

    for (let x = 0; x < w; x++) {
      const normX = x / w;
      const freqMod = Math.sin(simulatedWavePhase + normX * 12);
      const amp = (Math.sin(simulatedWavePhase * 1.5 + normX * 24) * 0.6 + (Math.random() - 0.5) * 0.4) * (h * 0.38);
      const y = h / 2 + amp * freqMod;
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
    audioAnimFrame = requestAnimationFrame(draw);
  }

  draw();
}

function analyzeCapturedAudio() {
  // Generate distress buffer
  const buffer = createSynthesizedDistressBuffer();
  appState.recordedAudioBuffer = buffer;

  const channelData = buffer ? buffer.getChannelData(0) : new Float32Array(1000);
  const result = computeAcousticDistressFromBuffer(channelData);
  appState.distressResult = result;

  // Update UI meters
  const dbfsVal = document.getElementById("aiDbfsVal");
  const peakVal = document.getElementById("aiPeakVal");
  const zcrVal = document.getElementById("aiZcrVal");
  const classVal = document.getElementById("aiClassificationText");
  const scoreVal = document.getElementById("aiScoreVal");

  if (dbfsVal) dbfsVal.textContent = `${result.dbfs.toFixed(1)} dBFS`;
  if (peakVal) peakVal.textContent = `${result.peak.toLocaleString()} (${((result.peak/32767)*100).toFixed(0)}%)`;
  if (zcrVal) zcrVal.textContent = result.zcr.toFixed(3);
  if (classVal) classVal.textContent = result.classification;
  if (scoreVal) scoreVal.textContent = `${(result.score * 100).toFixed(0)}% (${(result.confidence * 100).toFixed(0)}% Conf)`;

  setTimeout(() => {
    setEmergencyState(EmergencyState.LOCATION_ACQUIRING);
  }, 800);
}

// --- 10. Geolocation & Haversine Nearest Police Station ---
function acquireLocationAndComputeStation() {
  const loc = appState.victimLocation;
  const nearestInfo = findNearestPoliceStation(loc.lat, loc.lng);
  appState.nearestStation = nearestInfo.station;
  appState.distanceKm = nearestInfo.distanceKm;

  // Update victim device location readouts
  const userGpsVal = document.getElementById("userGpsVal");
  const userStationVal = document.getElementById("userNearestStationVal");
  if (userGpsVal) userGpsVal.textContent = `${loc.lat.toFixed(4)}, ${loc.lng.toFixed(4)}`;
  if (userStationVal) userStationVal.textContent = `${nearestInfo.station.name} (${nearestInfo.distanceKm.toFixed(2)} km)`;

  // Generate Incident ID
  appState.incidentId = `INC-${Date.now().toString().slice(-6)}`;
  appState.incidentTimestamp = new Date().toLocaleTimeString();

  updateMapLocations();
  setTimeout(() => {
    setEmergencyState(EmergencyState.ESCALATING);
  }, 600);
}

// --- 11. Police CAD Operations ---
function updateCadIncidentDetails() {
  const cadId = document.getElementById("cadIncidentId");
  const cadTime = document.getElementById("cadTimestamp");
  const cadDist = document.getElementById("cadDistance");
  const cadStation = document.getElementById("cadStationName");
  const cadEta = document.getElementById("cadEta");
  const cadDistress = document.getElementById("cadDistressScore");

  if (cadId) cadId.textContent = appState.incidentId || "INC-2026-8812";
  if (cadTime) cadTime.textContent = appState.incidentTimestamp || new Date().toLocaleTimeString();
  if (cadDist) cadDist.textContent = `${appState.distanceKm.toFixed(2)} km`;
  if (cadStation && appState.nearestStation) cadStation.textContent = appState.nearestStation.name;
  if (cadDistress) cadDistress.textContent = `${(appState.distressResult.score * 100).toFixed(0)}% (SCREAM DETECTED)`;

  // ETA formula: Average police response speed ~ 35 km/h + 1 min dispatch overhead
  const etaMinutes = Math.max(1.5, (appState.distanceKm / 35 * 60) + 1.0).toFixed(1);
  if (cadEta) cadEta.textContent = `${etaMinutes} mins`;
}

function resetPoliceCadButtons() {
  const btnAck = document.getElementById("btnCadAck");
  const btnDisp = document.getElementById("btnCadDispatch");
  const btnRes = document.getElementById("btnCadResolve");
  if (btnAck) btnAck.disabled = true;
  if (btnDisp) btnDisp.disabled = true;
  if (btnRes) btnRes.disabled = true;
}

function enablePoliceCadAcknowledge() {
  const btnAck = document.getElementById("btnCadAck");
  const btnDisp = document.getElementById("btnCadDispatch");
  const btnRes = document.getElementById("btnCadResolve");
  if (btnAck) btnAck.disabled = false;
  if (btnDisp) btnDisp.disabled = true;
  if (btnRes) btnRes.disabled = true;
}

function enablePoliceCadDispatch() {
  const btnAck = document.getElementById("btnCadAck");
  const btnDisp = document.getElementById("btnCadDispatch");
  const btnRes = document.getElementById("btnCadResolve");
  if (btnAck) btnAck.disabled = true;
  if (btnDisp) btnDisp.disabled = false;
  if (btnRes) btnRes.disabled = true;
}

function enablePoliceCadResolve() {
  const btnAck = document.getElementById("btnCadAck");
  const btnDisp = document.getElementById("btnCadDispatch");
  const btnRes = document.getElementById("btnCadResolve");
  if (btnAck) btnAck.disabled = true;
  if (btnDisp) btnDisp.disabled = true;
  if (btnRes) btnRes.disabled = false;
}

// --- 12. Audio Playback in Emergency Contact Device ---
function toggleRecordedAudioPlayback() {
  if (appState.isPlayingRecordedAudio) {
    stopRecordedAudioPlayback();
    return;
  }

  const ctx = getAudioContext();
  if (!ctx || !appState.recordedAudioBuffer) {
    appState.recordedAudioBuffer = createSynthesizedDistressBuffer();
  }

  const source = ctx.createBufferSource();
  source.buffer = appState.recordedAudioBuffer;
  source.connect(ctx.destination);

  appState.playbackSource = source;
  appState.isPlayingRecordedAudio = true;

  const playBtn = document.getElementById("btnPlayAudio");
  if (playBtn) playBtn.innerHTML = "⏸";

  const progressBar = document.getElementById("playerProgressBar");
  const timeDisplay = document.getElementById("playerTimeDisplay");

  const startTime = ctx.currentTime;
  const duration = 5.0;

  const updateInterval = setInterval(() => {
    if (!appState.isPlayingRecordedAudio) {
      clearInterval(updateInterval);
      return;
    }
    const elapsed = ctx.currentTime - startTime;
    if (elapsed >= duration) {
      clearInterval(updateInterval);
      stopRecordedAudioPlayback();
    } else {
      if (progressBar) progressBar.style.width = `${(elapsed / duration) * 100}%`;
      if (timeDisplay) timeDisplay.textContent = `00:0${Math.floor(elapsed)} / 00:05`;
    }
  }, 100);

  source.onended = () => {
    stopRecordedAudioPlayback();
  };

  source.start();
}

function stopRecordedAudioPlayback() {
  if (appState.playbackSource) {
    try {
      appState.playbackSource.stop();
    } catch (e) {}
    appState.playbackSource = null;
  }
  appState.isPlayingRecordedAudio = false;
  const playBtn = document.getElementById("btnPlayAudio");
  const progressBar = document.getElementById("playerProgressBar");
  const timeDisplay = document.getElementById("playerTimeDisplay");
  if (playBtn) playBtn.innerHTML = "▶";
  if (progressBar) progressBar.style.width = "0%";
  if (timeDisplay) timeDisplay.textContent = "00:00 / 00:05";
}

// --- 13. Accelerometer Physics Simulation Canvas ---
let accelCanvasFrame = null;
let accelOffset = 0;
let isShaking = false;

function initAccelerometerCanvas() {
  const canvas = document.getElementById("accelCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");

  function draw() {
    accelOffset += 0.08;
    const w = canvas.width;
    const h = canvas.height;
    ctx.fillStyle = "#070B13";
    ctx.fillRect(0, 0, w, h);

    const midY = h / 2;
    const amp = isShaking ? 22 : 4;

    // X-Axis (Cyan)
    ctx.strokeStyle = "#06B6D4";
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    for (let x = 0; x < w; x++) {
      const y = midY + Math.sin(accelOffset + x * 0.1) * amp;
      if (x === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // Y-Axis (Magenta)
    ctx.strokeStyle = "#EC4899";
    ctx.beginPath();
    for (let x = 0; x < w; x++) {
      const y = midY + Math.sin(accelOffset * 1.3 + x * 0.12) * (amp * 0.85);
      if (x === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // Z-Axis (Yellow)
    ctx.strokeStyle = "#FACC15";
    ctx.beginPath();
    for (let x = 0; x < w; x++) {
      const y = midY + Math.cos(accelOffset * 0.9 + x * 0.08) * (amp * 1.1);
      if (x === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();

    accelCanvasFrame = requestAnimationFrame(draw);
  }
  draw();
}

function simulatePhoneShake() {
  if (appState.currentState !== EmergencyState.IDLE) return;
  isShaking = true;
  playTickSound();

  const shakeReadout = document.getElementById("accelGReadout");
  if (shakeReadout) shakeReadout.textContent = "3.2G (SPURT 3x)";

  setTimeout(() => {
    isShaking = false;
    if (shakeReadout) shakeReadout.textContent = "1.0G (Normal)";
    setEmergencyState(EmergencyState.TRIGGERED);
  }, 600);
}

// --- 14. Leaflet Map & Radar Fallback Engine ---
let leafletMap = null;
let victimMarker = null;
let nearbyCitizenMarker = null;
let meshPolyline = null;
let stationMarkers = [];
let dispatchPolyline = null;

function initMap() {
  const mapElem = document.getElementById("leafletMapContainer");
  if (!mapElem) return;

  const centerLat = appState.victimLocation.lat;
  const centerLng = appState.victimLocation.lng;

  if (window.L) {
    try {
      leafletMap = L.map('leafletMapContainer').setView([centerLat, centerLng], 13);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 19
      }).addTo(leafletMap);

      // Add police stations
      POLICE_STATIONS.forEach(st => {
        const icon = L.divIcon({
          className: 'custom-station-icon',
          html: `<div style="background:#2563EB; width:28px; height:28px; border-radius:50%; border:2px solid white; display:flex; align-items:center; justify-content:center; box-shadow:0 0 10px rgba(37,99,235,0.8); color:white; font-size:14px;">🛡️</div>`,
          iconSize: [28, 28]
        });
        const marker = L.marker([st.latitude, st.longitude], { icon }).addTo(leafletMap);
        marker.bindPopup(`<b>${st.name}</b><br>${st.address}<br>Tel: ${st.phone}`);
        stationMarkers.push(marker);
      });

      // Add victim marker
      const victimIcon = L.divIcon({
        className: 'custom-victim-icon',
        html: `<div style="background:#E11D48; width:30px; height:30px; border-radius:50%; border:3px solid white; display:flex; align-items:center; justify-content:center; box-shadow:0 0 18px #E11D48; color:white; font-size:14px; animation:pulse-badge 1s infinite;">🆘</div>`,
        iconSize: [30, 30]
      });
      victimMarker = L.marker([centerLat, centerLng], { icon: victimIcon }).addTo(leafletMap);
      victimMarker.bindPopup(`<b>VICTIM LOCATION</b><br>${appState.victimLocation.name}`);

      // Add nearby citizen marker (~350m away)
      const nearbyIcon = L.divIcon({
        className: 'custom-nearby-icon',
        html: `<div style="background:#0F172A; width:30px; height:30px; border-radius:50%; border:2px solid #FDA4AF; display:flex; align-items:center; justify-content:center; box-shadow:0 0 14px rgba(225,29,72,0.6); color:white; font-size:14px;">📡</div>`,
        iconSize: [30, 30]
      });
      nearbyCitizenMarker = L.marker([centerLat + 0.0022, centerLng + 0.0018], { icon: nearbyIcon }).addTo(leafletMap);
      nearbyCitizenMarker.bindPopup(`<b>NEARBY CITIZEN: Priya Deshmukh</b><br>DURGA Community Mesh Node<br>~350m from Victim`);

    } catch (e) {
      console.warn("Leaflet initialization failed, fallback active:", e);
      renderSvgRadarFallback(mapElem);
    }
  } else {
    renderSvgRadarFallback(mapElem);
  }
}

function updateMapLocations() {
  if (!leafletMap) return;
  const vLat = appState.victimLocation.lat;
  const vLng = appState.victimLocation.lng;

  if (victimMarker) {
    victimMarker.setLatLng([vLat, vLng]);
    victimMarker.setPopupContent(`<b>VICTIM: Ananya Sharma</b><br>${appState.victimLocation.name}<br>Lat: ${vLat.toFixed(4)}, Lon: ${vLng.toFixed(4)}`);
  }

  if (nearbyCitizenMarker) {
    nearbyCitizenMarker.setLatLng([vLat + 0.0022, vLng + 0.0018]);
    nearbyCitizenMarker.setPopupContent(`<b>NEARBY CITIZEN: Priya Deshmukh</b><br>DURGA Community Mesh Node<br>~350m from Victim (${appState.victimLocation.name})`);
  }

  if (appState.nearestStation) {
    if (dispatchPolyline) {
      leafletMap.removeLayer(dispatchPolyline);
    }
    dispatchPolyline = L.polyline([
      [vLat, vLng],
      [appState.nearestStation.latitude, appState.nearestStation.longitude]
    ], {
      color: '#E11D48',
      weight: 3,
      dashArray: '6, 8',
      opacity: 0.85
    }).addTo(leafletMap);

    leafletMap.fitBounds([
      [vLat, vLng],
      [appState.nearestStation.latitude, appState.nearestStation.longitude]
    ], { padding: [40, 40] });
  }
}

function startPatrolMovementSimulation() {
  if (!leafletMap || !appState.nearestStation) return;

  const startLat = appState.nearestStation.latitude;
  const startLng = appState.nearestStation.longitude;
  const endLat = appState.victimLocation.lat;
  const endLng = appState.victimLocation.lng;

  const patrolIcon = L.divIcon({
    className: 'patrol-cruiser-icon',
    html: `<div style="background:#10B981; width:30px; height:30px; border-radius:50%; border:2px solid white; display:flex; align-items:center; justify-content:center; box-shadow:0 0 14px #10B981; color:white; font-size:15px;">🚓</div>`,
    iconSize: [30, 30]
  });

  if (appState.patrolMarker) {
    leafletMap.removeLayer(appState.patrolMarker);
  }
  appState.patrolMarker = L.marker([startLat, startLng], { icon: patrolIcon }).addTo(leafletMap);

  let step = 0;
  const totalSteps = 60;

  if (appState.patrolInterval) clearInterval(appState.patrolInterval);
  appState.patrolInterval = setInterval(() => {
    step++;
    const progress = step / totalSteps;
    const curLat = startLat + (endLat - startLat) * progress;
    const curLng = startLng + (endLng - startLng) * progress;

    appState.patrolMarker.setLatLng([curLat, curLng]);

    if (step >= totalSteps) {
      clearInterval(appState.patrolInterval);
      appState.patrolInterval = null;
      playChimeSound();
      logAuditEvent("Patrol Cruiser arrived on scene! Perimeter secured.", "success");
    }
  }, 150);
}

function stopPatrolMovement() {
  if (appState.patrolInterval) {
    clearInterval(appState.patrolInterval);
    appState.patrolInterval = null;
  }
  if (appState.patrolMarker && leafletMap) {
    leafletMap.removeLayer(appState.patrolMarker);
    appState.patrolMarker = null;
  }
  if (dispatchPolyline && leafletMap) {
    leafletMap.removeLayer(dispatchPolyline);
    dispatchPolyline = null;
  }
}

function renderSvgRadarFallback(container) {
  container.innerHTML = `
    <div style="width:100%; height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; background:#070B13; color:#06B6D4; font-family:'JetBrains Mono', monospace; font-size:0.8rem; gap:12px;">
      <svg width="140" height="140" viewBox="0 0 100 100">
        <circle cx="50" cy="50" r="45" fill="none" stroke="#06B6D4" stroke-width="1" opacity="0.3"/>
        <circle cx="50" cy="50" r="30" fill="none" stroke="#06B6D4" stroke-width="1" opacity="0.5"/>
        <circle cx="50" cy="50" r="15" fill="none" stroke="#06B6D4" stroke-width="1" opacity="0.8"/>
        <line x1="50" y1="5" x2="50" y2="95" stroke="#06B6D4" stroke-width="0.8" opacity="0.4"/>
        <line x1="5" y1="50" x2="95" y2="50" stroke="#06B6D4" stroke-width="0.8" opacity="0.4"/>
        <circle cx="50" cy="50" r="4" fill="#E11D48"/>
      </svg>
      <div>TACTICAL GEO-RADAR MESH [OFFLINE FALLBACK ACTIVE]</div>
    </div>
  `;
}

// --- 15. Preset Automated Scenarios ---
function runFullEmergencyDemo() {
  setEmergencyState(EmergencyState.IDLE);
  logAuditEvent("=== STARTING FULL EMERGENCY DEMO SEQUENCE ===", "critical");

  setTimeout(() => {
    simulatePhoneShake();
  }, 600);
}

function runFalseAlarmDemo() {
  setEmergencyState(EmergencyState.IDLE);
  logAuditEvent("=== SIMULATING FALSE ALARM CANCEL DEMO ===", "warning");

  setTimeout(() => {
    simulatePhoneShake();
    setTimeout(() => {
      cancelCountdown();
    }, 2200);
  }, 600);
}

// --- 16. AI Acoustic Lab Modal Tester ---
function openAcousticLabModal() {
  const modal = document.getElementById("aiLabModal");
  if (modal) modal.classList.add("open");
}

function closeAcousticLabModal() {
  const modal = document.getElementById("aiLabModal");
  if (modal) modal.classList.remove("open");
}

function testSoundScenario(type) {
  const ctx = getAudioContext();
  if (!ctx) return;
  const sampleRate = ctx.sampleRate;
  const duration = 2.0;
  const buffer = ctx.createBuffer(1, sampleRate * duration, sampleRate);
  const data = buffer.getChannelData(0);

  let desc = "";
  if (type === "scream") {
    desc = "Scream / High Distress";
    for (let i = 0; i < data.length; i++) {
      const t = i / sampleRate;
      data[i] = (Math.sin(2 * Math.PI * 1100 * t) * 0.7 + (Math.random() - 0.5) * 0.4) * 0.85;
    }
  } else if (type === "shout") {
    desc = "Elevated Voice / Shouting";
    for (let i = 0; i < data.length; i++) {
      const t = i / sampleRate;
      data[i] = (Math.sin(2 * Math.PI * 450 * t) * 0.4 + (Math.random() - 0.5) * 0.2) * 0.45;
    }
  } else if (type === "ambient") {
    desc = "Ambient Street Chatter";
    for (let i = 0; i < data.length; i++) {
      data[i] = (Math.random() * 2 - 1) * 0.08;
    }
  } else {
    desc = "Whisper / Silence";
    for (let i = 0; i < data.length; i++) {
      data[i] = (Math.random() * 2 - 1) * 0.01;
    }
  }

  // Play audio sample
  if (!appState.isMuted) {
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(ctx.destination);
    src.start();
  }

  const result = computeAcousticDistressFromBuffer(data);
  const labResultBox = document.getElementById("labTestResult");
  if (labResultBox) {
    labResultBox.innerHTML = `
      <div style="background:#0F172A; border:1px solid #8B5CF6; border-radius:8px; padding:12px; margin-top:10px;">
        <div style="color:#A78BFA; font-weight:700; margin-bottom:4px;">Test: ${desc}</div>
        <div>RMS: <b>${result.dbfs.toFixed(1)} dBFS</b> | Peak: <b>${result.peak} / 32,767</b> | ZCR: <b>${result.zcr.toFixed(3)}</b></div>
        <div style="color:#6EE7B7; font-weight:700; margin-top:4px;">Result: ${result.classification} (${(result.score * 100).toFixed(0)}% Score)</div>
      </div>
    `;
  }
}

// --- 16.5 Map Community Mesh Vectoring ---
function highlightMeshLinkOnMap() {
  if (!leafletMap || !victimMarker || !nearbyCitizenMarker) return;
  const vPos = victimMarker.getLatLng();
  const nPos = nearbyCitizenMarker.getLatLng();

  if (meshPolyline) leafletMap.removeLayer(meshPolyline);
  meshPolyline = L.polyline([vPos, nPos], {
    color: '#10B981',
    weight: 3,
    dashArray: '5, 8',
    opacity: 0.95
  }).addTo(leafletMap);

  leafletMap.fitBounds([vPos, nPos], { padding: [50, 50] });
}

function clearMeshLinkOnMap() {
  if (meshPolyline && leafletMap) {
    leafletMap.removeLayer(meshPolyline);
    meshPolyline = null;
  }
}

// --- 16.6 Laptop Simulator View Switcher ---
function switchSimulatorView(view) {
  const cards = {
    user: document.getElementById("userDeviceCard"),
    nearby: document.getElementById("nearbyCitizenCard"),
    contact: document.getElementById("contactDeviceCard"),
    responder: document.getElementById("responderDeviceCard")
  };
  const grid = document.getElementById("deviceMeshGrid");
  const buttons = {
    all: document.getElementById("viewBtnAll"),
    user: document.getElementById("viewBtnVictim"),
    nearby: document.getElementById("viewBtnNearby"),
    contact: document.getElementById("viewBtnGuardian"),
    responder: document.getElementById("viewBtnPolice")
  };

  Object.values(buttons).forEach(btn => btn?.classList.remove("active"));
  if (buttons[view]) buttons[view].classList.add("active");

  if (view === "all") {
    grid?.classList.remove("single-device-view");
    Object.values(cards).forEach(card => card?.classList.remove("view-hidden"));
    logAuditEvent("Laptop View: Displaying All 4 Mesh Nodes in Grid View.", "normal");
  } else {
    grid?.classList.add("single-device-view");
    Object.entries(cards).forEach(([key, card]) => {
      if (key === view) {
        card?.classList.remove("view-hidden");
      } else {
        card?.classList.add("view-hidden");
      }
    });
    logAuditEvent(`Laptop View: Focused single-device inspection mode (${view.toUpperCase()}).`, "normal");
  }
}
window.switchSimulatorView = switchSimulatorView;

// --- 16.7 Proximity Mesh Citizen Alert Simulation ---
function triggerNearbyMeshCitizenAlert() {
  playAlarmSiren();
  logAuditEvent("🚨 [PROXIMITY MESH] Nearby citizen Priya Deshmukh received encrypted distress token (~350m away). Android OS Heads-Up Notification & In-App Dialog triggered.", "critical");

  const osNotif = document.getElementById("meshOsNotification");
  const inAppDialog = document.getElementById("meshInAppDialog");
  const nearbyCard = document.getElementById("nearbyCitizenCard");
  const nearbyPill = document.getElementById("nearbyStatusPill");

  if (osNotif) osNotif.classList.add("show");
  if (inAppDialog) inAppDialog.classList.add("show");
  if (nearbyCard) nearbyCard.classList.add("emergency-active");
  if (nearbyPill) {
    nearbyPill.textContent = "EMERGENCY (~350m)";
    nearbyPill.className = "status-pill emergency";
  }

  highlightMeshLinkOnMap();
}

function dismissNearbyMeshCitizenAlert() {
  const osNotif = document.getElementById("meshOsNotification");
  const inAppDialog = document.getElementById("meshInAppDialog");
  const nearbyCard = document.getElementById("nearbyCitizenCard");
  const nearbyPill = document.getElementById("nearbyStatusPill");
  const modal = document.getElementById("proximityMeshModal");

  if (osNotif) osNotif.classList.remove("show");
  if (inAppDialog) inAppDialog.classList.remove("show");
  if (nearbyCard) nearbyCard.classList.remove("emergency-active");
  if (nearbyPill) {
    nearbyPill.textContent = "IDLE (MESH)";
    nearbyPill.className = "status-pill idle";
  }
  if (modal) modal.classList.remove("open");

  clearMeshLinkOnMap();
  logAuditEvent("Proximity mesh citizen alert dismissed.", "normal");
}

// --- 17. Initialization & Event Binding ---
document.addEventListener("DOMContentLoaded", () => {
  initAccelerometerCanvas();
  initMap();
  acquireLocationAndComputeStation();
  setEmergencyState(EmergencyState.IDLE);

  // Top bar buttons
  document.getElementById("btnRunFullDemo")?.addEventListener("click", runFullEmergencyDemo);
  document.getElementById("btnRunNearbyMeshDemo")?.addEventListener("click", triggerNearbyMeshCitizenAlert);
  document.getElementById("btnRunFalseAlarm")?.addEventListener("click", runFalseAlarmDemo);
  document.getElementById("btnResetSim")?.addEventListener("click", () => {
    dismissNearbyMeshCitizenAlert();
    setEmergencyState(EmergencyState.IDLE);
  });
  document.getElementById("btnOpenLab")?.addEventListener("click", openAcousticLabModal);

  // Victim Device triggers
  document.getElementById("btnTriggerShake")?.addEventListener("click", simulatePhoneShake);
  document.getElementById("btnSosBig")?.addEventListener("click", () => {
    if (appState.currentState === EmergencyState.IDLE) {
      setEmergencyState(EmergencyState.TRIGGERED);
    }
  });
  document.getElementById("btnCancelCountdown")?.addEventListener("click", cancelCountdown);

  // Nearby Citizen Device triggers
  document.getElementById("btnTriggerNearbyFromDevice")?.addEventListener("click", triggerNearbyMeshCitizenAlert);
  document.getElementById("btnNotifOpenPopup")?.addEventListener("click", () => {
    const osNotif = document.getElementById("meshOsNotification");
    const inAppDialog = document.getElementById("meshInAppDialog");
    if (osNotif) osNotif.classList.remove("show");
    if (inAppDialog) inAppDialog.classList.add("show");
  });
  document.getElementById("btnNotifDismiss")?.addEventListener("click", () => {
    const osNotif = document.getElementById("meshOsNotification");
    if (osNotif) osNotif.classList.remove("show");
  });
  document.getElementById("btnNearbyDialogDismiss")?.addEventListener("click", dismissNearbyMeshCitizenAlert);
  document.getElementById("btnNearbyDialogAssist")?.addEventListener("click", () => {
    highlightMeshLinkOnMap();
    alert("Navigating to Victim coordinates: FC Road (~350m away). Dotted green response route highlighted on Tactical Leaflet Map.");
  });
  document.getElementById("btnNearbyDialogCall112")?.addEventListener("click", () => {
    alert("Dialing 112 (Police Control Room) with relayed coordinates of Victim Ananya Sharma.");
  });

  // Modal Fallback Triggers
  document.getElementById("btnCloseProximityModal")?.addEventListener("click", dismissNearbyMeshCitizenAlert);
  document.getElementById("btnModalRespondMap")?.addEventListener("click", () => {
    dismissNearbyMeshCitizenAlert();
    highlightMeshLinkOnMap();
  });
  document.getElementById("btnModalCall112")?.addEventListener("click", () => {
    alert("Dialing 112 (Police Control Room) with relayed mesh incident telemetry.");
  });

  // Contact Device player
  document.getElementById("btnPlayAudio")?.addEventListener("click", toggleRecordedAudioPlayback);

  // Police CAD Buttons
  document.getElementById("btnCadAck")?.addEventListener("click", () => {
    setEmergencyState(EmergencyState.ACKNOWLEDGED);
  });
  document.getElementById("btnCadDispatch")?.addEventListener("click", () => {
    setEmergencyState(EmergencyState.RESPONDING);
  });
  document.getElementById("btnCadResolve")?.addEventListener("click", () => {
    setEmergencyState(EmergencyState.RESOLVED);
  });

  // Location Selector
  document.getElementById("locationPresetSelect")?.addEventListener("change", (e) => {
    const val = e.target.value;
    if (LOCATION_PRESETS[val]) {
      appState.victimLocation = { ...LOCATION_PRESETS[val] };
      acquireLocationAndComputeStation();
      logAuditEvent(`Victim coordinates moved to: ${appState.victimLocation.name}`, "normal");
    }
  });

  // Sound Mute Toggle
  document.getElementById("btnToggleSound")?.addEventListener("click", () => {
    appState.isMuted = !appState.isMuted;
    const icon = document.getElementById("soundIcon");
    if (icon) icon.textContent = appState.isMuted ? "🔇" : "🔊";
  });

  // Modal Close
  document.getElementById("btnCloseLab")?.addEventListener("click", closeAcousticLabModal);
});
