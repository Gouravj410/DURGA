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
 * Excludes the victim's own device.
 */
function filterDevicesWithinRadius(incidentLat, incidentLon, devices, radiusKm = 1.0, excludeUserId = null) {
  const eligibleDevices = [];

  for (const dev of devices) {
    if (excludeUserId && dev.user_id === excludeUserId) {
      continue;
    }
    if (dev.last_lat === null || dev.last_lon === null) {
      continue;
    }

    const distance = calculateHaversineDistanceKm(incidentLat, incidentLon, dev.last_lat, dev.last_lon);
    if (distance !== null && distance <= radiusKm) {
      eligibleDevices.push({
        ...dev,
        distanceKm: parseFloat(distance.toFixed(3)),
      });
    }
  }

  // Sort by nearest first
  return eligibleDevices.sort((a, b) => a.distanceKm - b.distanceKm);
}

module.exports = {
  calculateHaversineDistanceKm,
  findNearestPoliceStation,
  filterDevicesWithinRadius,
};
