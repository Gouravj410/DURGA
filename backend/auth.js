/**
 * DURGA Authentication & Access Control Module
 * Implements JWT Session Management, PBKDF2 PIN Hashing, and Server-Enforced RBAC
 */

const jwt = require("jsonwebtoken");
const crypto = require("crypto");
const { getQuery, runQuery, hashPin } = require("./database");

const JWT_SECRET = process.env.DURGA_JWT_SECRET || "DURGA_DEV_PROTOTYPE_SECRET_KEY_2026_DO_NOT_USE_IN_PROD";
const TOKEN_EXPIRY = "24h";

// In-memory OTP storage for development
const pendingOtps = new Map();

/**
 * Generates an OTP for phone number registration/login.
 * In prototype development, whitelisted numbers receive a fixed OTP.
 */
function normalizePhone(phone) {
  if (!phone) return "";
  let digits = phone.replace(/[^0-9+]/g, "").trim();
  if (!digits.startsWith("+") && digits.length === 10) {
    digits = "+91" + digits;
  }
  return digits;
}

/**
 * Generates an OTP for phone number registration/login.
 * In development mode, provides a test OTP for verifiable physical testing.
 * In production mode, generates a secure random 6-digit token and dispatches via SMS provider.
 */
function requestOtp(phone) {
  const cleanPhone = normalizePhone(phone);
  if (!cleanPhone || cleanPhone.length < 10) {
    throw new Error("Invalid phone number provided.");
  }
  const isDevMode = (process.env.DURGA_AUTH_MODE || "DEVELOPMENT") === "DEVELOPMENT";
  const otpCode = isDevMode ? "123456" : crypto.randomInt(100000, 999999).toString();
  const ttlMs = 5 * 60 * 1000; // 5 minutes validity

  pendingOtps.set(cleanPhone, {
    otp: otpCode,
    expiresAt: Date.now() + ttlMs,
    requestedAt: Date.now(),
  });

  console.log(
    `[DURGA AUTH ${isDevMode ? "DEV" : "PROD"}] Phone ${cleanPhone} requested OTP. Code: ${
      isDevMode ? otpCode : "[REDACTED]"
    }`
  );

  return {
    success: true,
    mode: isDevMode ? "DEVELOPMENT_TEST_PROVIDER" : "PRODUCTION_SMS_PROVIDER",
    message: isDevMode
      ? "Test OTP generated (Development Provider)."
      : "Verification SMS dispatched to mobile device.",
    testOtp: isDevMode ? otpCode : undefined,
    expiresInSeconds: ttlMs / 1000,
  };
}

/**
 * Verifies OTP and generates a signed JWT session.
 * Requires active, unexpired record in pendingOtps.
 */
async function verifyOtp(phone, otp, defaultName = "Citizen", defaultRole = "CITIZEN") {
  const cleanPhone = normalizePhone(phone);
  const enteredOtp = (otp || "").trim();
  const record = pendingOtps.get(cleanPhone);

  if (!record || record.expiresAt < Date.now()) {
    throw new Error("OTP expired or not requested. Please tap GET OTP.");
  }

  if (record.otp !== enteredOtp) {
    throw new Error("Invalid OTP entered. Please verify the code and try again.");
  }

  // OTP verified -> remove from memory
  pendingOtps.delete(cleanPhone);

  // Check if user exists in database
  let user = await getQuery("SELECT * FROM users WHERE phone = ?", [cleanPhone]);

  if (!user) {
    const userId = "USER-" + crypto.randomUUID().substring(0, 8).toUpperCase();
    const now = Date.now();
    await runQuery(
      "INSERT INTO users (id, phone, name, role, created_at) VALUES (?, ?, ?, ?, ?)",
      [userId, cleanPhone, defaultName, defaultRole, now]
    );
    user = await getQuery("SELECT * FROM users WHERE id = ?", [userId]);
  } else if (defaultName && defaultName !== "Protected Citizen" && defaultName !== "Citizen" && defaultName !== user.name) {
    await runQuery("UPDATE users SET name = ? WHERE id = ?", [defaultName, user.id]);
    user.name = defaultName;
  }

  // Issue JWT Token
  const token = jwt.sign(
    {
      userId: user.id,
      phone: user.phone,
      name: user.name,
      role: user.role,
    },
    JWT_SECRET,
    { expiresIn: TOKEN_EXPIRY }
  );

  return {
    token,
    user: {
      id: user.id,
      phone: user.phone,
      name: user.name,
      role: user.role,
      hasPin: Boolean(user.pin_hash),
    },
  };
}

/**
 * Sets or updates user 4-digit security PIN with cryptographic salt.
 */
async function setUserPin(userId, pin) {
  if (!pin || pin.length !== 4 || !/^\d{4}$/.test(pin)) {
    throw new Error("PIN must be exactly 4 numeric digits.");
  }

  const salt = crypto.randomBytes(16).toString("hex");
  const hashed = hashPin(pin, salt);

  await runQuery("UPDATE users SET pin_hash = ?, pin_salt = ? WHERE id = ?", [hashed, salt, userId]);
  return { success: true, message: "Security PIN updated successfully." };
}

/**
 * Validates a submitted PIN against user's stored PBKDF2 hash.
 */
async function verifyUserPin(userId, pin) {
  const user = await getQuery("SELECT pin_hash, pin_salt FROM users WHERE id = ?", [userId]);
  if (!user || !user.pin_hash || !user.pin_salt) {
    return false;
  }
  const testHash = hashPin(pin, user.pin_salt);
  return crypto.timingSafeEqual(Buffer.from(testHash), Buffer.from(user.pin_hash));
}

/**
 * Express middleware to authenticate JWT.
 */
function authenticateToken(req, res, next) {
  const authHeader = req.headers["authorization"];
  const token = authHeader && authHeader.split(" ")[1];

  if (!token) {
    return res.status(401).json({ error: "Missing authorization token." });
  }

  jwt.verify(token, JWT_SECRET, (err, decoded) => {
    if (err) {
      return res.status(403).json({ error: "Invalid or expired session token." });
    }
    req.user = decoded;
    next();
  });
}

/**
 * Express middleware to enforce Role-Based Access Control.
 */
function requireRole(requiredRole) {
  return (req, res, next) => {
    if (!req.user) {
      return res.status(401).json({ error: "Authentication required." });
    }
    if (req.user.role !== requiredRole) {
      console.warn(
        `[DURGA RBAC] Unauthorized access attempt by ${req.user.userId} (${req.user.role}) for role ${requiredRole}`
      );
      return res.status(403).json({ error: `Forbidden: Requires role '${requiredRole}'.` });
    }
    next();
  };
}

module.exports = {
  requestOtp,
  verifyOtp,
  setUserPin,
  verifyUserPin,
  authenticateToken,
  requireRole,
  JWT_SECRET,
  jwt,
};
