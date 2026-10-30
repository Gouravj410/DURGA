/**
 * DURGA Geospatial Telemetry & Haversine Distance Engine
 */

const EARTH_RADIUS_KM = 6371.0088;

/**
 * Converts degrees to radians.
 */
function toRadians(degrees) {
  return (degrees * Math.PI) / 180;
}

/**
 * Calculates Haversine great-circle distance between two GPS coordinates in kilometers.
 */
function calculateHaversineDistanceKm(lat1, lon1, lat2, lon2) {
  if (lat1 === null || lon1 === null || lat2 === null || lon2 === null) {
    return null;
  }
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) *
      Math.cos(toRadians(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_KM * c;
}

/**
 * Finds the nearest verified police station to a given coordinate.
 */
function findNearestPoliceStation(lat, lon, stations) {
  if (!stations || stations.length === 0 || lat === null || lon === null) {
    return { station: null, distanceKm: null };
  }

  let nearest = null;
  let minDistance = Infinity;

  for (const station of stations) {
    const dist = calculateHaversineDistanceKm(lat, lon, station.latitude, station.longitude);
    if (dist !== null && dist < minDistance) {
      minDistance = dist;
      nearest = station;
    }
  }

  return {
    station: nearest,
    distanceKm: minDistance !== Infinity ? parseFloat(minDistance.toFixed(3)) : null,
  };
}

/**
 * Filters registered devices that are within the specified radius (in km) from incident coordinates.
 * Enforces:
 * - Valid GPS coordinate bounds for incident and devices
 * - Victim / sender exclusion
 * - Non-empty valid FCM push token requirement
 * - Recipient opt-in / consent verification
 * - Location freshness (maxAgeMs, default 24h, clock-skew protected)
 * - Accuracy verification (<= 1000m and non-negative)
 * - Strict radius boundary check (distance <= radiusKm)
 * - Citizen deduplication (closest active device per user / token)
 */
function filterDevicesWithinRadius(
  incidentLat,
  incidentLon,
  devices,
  radiusKm = 1.0,
  excludeUserId = null,
  maxLocationAgeMs = 24 * 60 * 60 * 1000,
  excludeDeviceId = null
) {
  if (
    incidentLat === null ||
    incidentLat === undefined ||
    incidentLon === null ||
    incidentLon === undefined ||
    isNaN(Number(incidentLat)) ||
    isNaN(Number(incidentLon)) ||
    Number(incidentLat) < -90 ||
    Number(incidentLat) > 90 ||
    Number(incidentLon) < -180 ||
    Number(incidentLon) > 180
  ) {
    return [];
  }

  const cleanIncidentLat = Number(incidentLat);
  const cleanIncidentLon = Number(incidentLon);
  const eligibleDevices = [];
  const now = Date.now();

  for (const dev of devices || []) {
    // 1. Exclude victim / sender
    if (excludeDeviceId && dev.id === excludeDeviceId) {
      continue;
    }
    if (!excludeDeviceId && excludeUserId && String(dev.user_id) === String(excludeUserId)) {
      continue;
    }

    // 2. Validate device coordinates
    if (
      dev.last_lat === null ||
      dev.last_lat === undefined ||
      dev.last_lon === null ||
      dev.last_lon === undefined ||
      isNaN(Number(dev.last_lat)) ||
      isNaN(Number(dev.last_lon)) ||
      Number(dev.last_lat) < -90 ||
      Number(dev.last_lat) > 90 ||
      Number(dev.last_lon) < -180 ||
      Number(dev.last_lon) > 180
    ) {
      continue;
    }

    // 3. Validate valid FCM token is present
    if (
      !dev.fcm_token ||
      typeof dev.fcm_token !== "string" ||
      dev.fcm_token.trim().length === 0
    ) {
      continue;
    }

    // 4. Validate consent / opt-in status (defaults to true if unspecified)
    if (
      dev.is_opted_in === 0 ||
      dev.is_opted_in === false ||
      dev.opt_in === false ||
      dev.consent === false
    ) {
      continue;
    }

    // 5. Filter stale locations (older than maxLocationAgeMs or clock skew > 5m in future)
    if (
      !dev.last_location_time ||
      now - dev.last_location_time > maxLocationAgeMs ||
      dev.last_location_time > now + 5 * 60 * 1000
    ) {
      continue;
    }

    // 6. Filter inaccurate locations (> 1000 meters or negative)
    if (
      dev.last_accuracy !== null &&
      dev.last_accuracy !== undefined &&
      (isNaN(Number(dev.last_accuracy)) ||
        Number(dev.last_accuracy) > 1000 ||
        Number(dev.last_accuracy) < 0)
    ) {
      continue;
    }

    // 7. Calculate Haversine great-circle distance
    const distance = calculateHaversineDistanceKm(
      cleanIncidentLat,
      cleanIncidentLon,
      Number(dev.last_lat),
      Number(dev.last_lon)
    );

    if (distance !== null && distance <= radiusKm) {
      eligibleDevices.push({
        ...dev,
        distanceKm: parseFloat(distance.toFixed(3)),
      });
    }
  }

  // 8. Sort by closest first
  eligibleDevices.sort((a, b) => a.distanceKm - b.distanceKm);

  // 9. Deduplicate: exactly one notification per user and per FCM token
  const seenTokens = new Set();
  const seenUsers = new Set();
  const deduplicated = [];

  for (const dev of eligibleDevices) {
    if (seenTokens.has(dev.fcm_token)) continue;
    if (dev.user_id && seenUsers.has(String(dev.user_id))) continue;

    seenTokens.add(dev.fcm_token);
    if (dev.user_id) seenUsers.add(String(dev.user_id));
    deduplicated.push(dev);
  }

  return deduplicated;
}

module.exports = {
  calculateHaversineDistanceKm,
  findNearestPoliceStation,
  filterDevicesWithinRadius,
};
