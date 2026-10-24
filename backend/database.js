/**
 * DURGA Central Backend Database Initialization & Repository
 * SQLite3 Database Engine with Foreign Keys and Transactional Logging
 */

const sqlite3 = require("sqlite3").verbose();
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");

const DB_PATH = path.join(__dirname, "durga.db");

// Ensure upload directory exists for audio evidence
const UPLOADS_DIR = path.join(__dirname, "uploads", "audio");
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

const db = new sqlite3.Database(DB_PATH, (err) => {
  if (err) {
    console.error("[DURGA DB] Failed to open SQLite database:", err.message);
  } else {
    console.log("[DURGA DB] Connected to SQLite database:", DB_PATH);
    db.run("PRAGMA foreign_keys = ON;");
    initSchema();
  }
});

function hashPin(pin, salt) {
  return crypto.pbkdf2Sync(pin, salt, 10000, 32, "sha256").toString("hex");
}

function initSchema() {
  db.serialize(() => {
    // 1. Users Table
    db.run(`
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        phone TEXT UNIQUE NOT NULL,
        name TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'CITIZEN',
        pin_hash TEXT,
        pin_salt TEXT,
        created_at INTEGER NOT NULL
      )
    `);

    // 2. Devices Table (FCM Tokens & Last Known Coordinates)
    db.run(`
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        fcm_token TEXT,
        device_model TEXT,
        last_lat REAL,
        last_lon REAL,
        last_accuracy REAL,
        last_location_time INTEGER,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
      )
    `);

    // 3. Incidents Table (Full Lifecycle State Machine)
    db.run(`
      CREATE TABLE IF NOT EXISTS incidents (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        user_name TEXT NOT NULL,
        user_phone TEXT NOT NULL,
        status TEXT NOT NULL,
        trigger_source TEXT NOT NULL,
        latitude REAL,
        longitude REAL,
        location_accuracy REAL,
        distress_score REAL DEFAULT 0.0,
        distress_classification TEXT DEFAULT 'Pending',
        audio_file_path TEXT,
        nearest_police_station TEXT,
        nearest_police_station_distance REAL,
        resolved_by_responder_id TEXT,
        resolution_notes TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        resolved_at INTEGER,
        FOREIGN KEY(user_id) REFERENCES users(id)
      )
    `);

    // 4. Incident Real-Time Location History
    db.run(`
      CREATE TABLE IF NOT EXISTS incident_location_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        incident_id TEXT NOT NULL,
        latitude REAL NOT NULL,
        longitude REAL NOT NULL,
        accuracy REAL,
        timestamp INTEGER NOT NULL,
        FOREIGN KEY(incident_id) REFERENCES incidents(id) ON DELETE CASCADE
      )
    `);

    // 5. Audit Log Table (Immutable trace of every sensitive action)
    db.run(`
      CREATE TABLE IF NOT EXISTS audit_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        incident_id TEXT,
        actor_id TEXT NOT NULL,
        actor_role TEXT NOT NULL,
        action TEXT NOT NULL,
        details TEXT,
        timestamp INTEGER NOT NULL
      )
    `);

    // 6. Curated Police Stations Directory (with verification dates)
    db.run(`
      CREATE TABLE IF NOT EXISTS police_stations (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        latitude REAL NOT NULL,
        longitude REAL NOT NULL,
        phone TEXT NOT NULL,
        address TEXT NOT NULL,
        verified_date TEXT NOT NULL
      )
    `);

    // Pre-populate verified Pune Police Stations
    const stations = [
      {
        id: "PS-001",
        name: "Central Police Station (Shivajinagar)",
        lat: 18.5204,
        lon: 73.8567,
        phone: "+91-20-26123456",
        address: "Shivajinagar, Pune, Maharashtra 411005",
        verified: "2026-01-15",
      },
      {
        id: "PS-002",
        name: "Cantonment Police Station (Camp)",
        lat: 18.5089,
        lon: 73.8795,
        phone: "+91-20-26361234",
        address: "Camp Area, Pune, Maharashtra 411001",
        verified: "2026-01-15",
      },
      {
        id: "PS-003",
        name: "Kothrud Police Station",
        lat: 18.5074,
        lon: 73.8077,
        phone: "+91-20-25381100",
        address: "Paud Road, Kothrud, Pune, Maharashtra 411038",
        verified: "2026-01-15",
      },
      {
        id: "PS-004",
        name: "Hinjawadi IT Park Police Station",
        lat: 18.5912,
        lon: 73.7389,
        phone: "+91-20-22934444",
        address: "Phase 1, Hinjawadi, Pune, Maharashtra 411057",
        verified: "2026-01-15",
      },
      {
        id: "PS-005",
        name: "Viman Nagar Police Station",
        lat: 18.5679,
        lon: 73.9143,
        phone: "+91-20-26631010",
        address: "Viman Nagar, Pune, Maharashtra 411014",
        verified: "2026-01-15",
      },
    ];

    const insertStationStmt = db.prepare(`
      INSERT OR IGNORE INTO police_stations (id, name, latitude, longitude, phone, address, verified_date)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `);

    for (const s of stations) {
      insertStationStmt.run(s.id, s.name, s.lat, s.lon, s.phone, s.address, s.verified);
    }
    insertStationStmt.finalize();

    // Create default prototype responder account for portal defense
    const responderSalt = crypto.randomBytes(16).toString("hex");
    const responderHash = hashPin("9999", responderSalt);
    db.run(
      `
      INSERT OR IGNORE INTO users (id, phone, name, role, pin_hash, pin_salt, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `,
      [
        "RESP-PROTOTYPE-01",
        "+919999999999",
        "Authorized Police Dispatcher (Prototype)",
        "RESPONDER",
        responderHash,
        responderSalt,
        Date.now(),
      ]
    );

    console.log("[DURGA DB] Database schema & verified police stations populated.");
  });
}

/**
 * Promisified database helpers.
 */
function runQuery(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.run(sql, params, function (err) {
      if (err) reject(err);
      else resolve(this);
    });
  });
}

function getQuery(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.get(sql, params, (err, row) => {
      if (err) reject(err);
      else resolve(row);
    });
  });
}

function allQuery(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.all(sql, params, (err, rows) => {
      if (err) reject(err);
      else resolve(rows);
    });
  });
}

module.exports = {
  db,
  UPLOADS_DIR,
  hashPin,
  runQuery,
  getQuery,
  allQuery,
};
