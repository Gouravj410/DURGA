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
function requestOtp(phone) {
  // Normalize phone
  const cleanPhone = phone.trim();

  // Test numbers for reliable physical device testing
  if (cleanPhone === "+919876543210" || cleanPhone === "+919999999999" || cleanPhone === "+918888888888") {
    pendingOtps.set(cleanPhone, { otp: "123456", expiresAt: Date.now() + 10 * 60 * 1000 });
    console.log(`[DURGA AUTH] Whitelisted test number ${cleanPhone} requested OTP. Use fixed OTP: 123456`);
    return {
      success: true,
      mode: "DEVELOPMENT_TEST_PROVIDER",
      message: "Test OTP generated. (Development Provider)",
      testOtp: "123456",
    };
  }

  // Generate 6-digit cryptographic OTP
  const generatedOtp = crypto.randomInt(100000, 999999).toString();
  pendingOtps.set(cleanPhone, { otp: generatedOtp, expiresAt: Date.now() + 5 * 60 * 1000 });

  console.log(`[DURGA AUTH] OTP for ${cleanPhone}: ${generatedOtp} (Valid for 5 minutes)`);
  return {
    success: true,
    mode: "DEVELOPMENT_LOCAL_OTP",
    message: "OTP generated for development testing. Inspect console logs.",
    testOtp: generatedOtp,
  };
}

/**
 * Verifies OTP and generates a signed JWT session.
 */
async function verifyOtp(phone, otp, defaultName = "Citizen", defaultRole = "CITIZEN") {
  const cleanPhone = phone.trim();
  const record = pendingOtps.get(cleanPhone);

  if (!record || record.expiresAt < Date.now()) {
    throw new Error("OTP expired or not requested.");
  }

  if (record.otp !== otp.trim()) {
    throw new Error("Invalid OTP entered.");
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
};
