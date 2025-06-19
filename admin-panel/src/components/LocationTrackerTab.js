import React, { useEffect, useState, useRef } from "react";
import { ref, get, onValue, update } from "firebase/database";
import {
  MapContainer,
  TileLayer,
  Marker,
  Popup,
  Polyline,
  ScaleControl,
  LayersControl,
} from "react-leaflet";
import L from "leaflet";
import { database as db } from "../firebaseConfig";
import "leaflet/dist/leaflet.css";
import "./LocationTrackerTab.css";

// Fix Leaflet default icon configuration
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl:
    "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  iconUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

// Default icon instance for current location (grey)
const defaultIcon = new L.Icon.Default();

// Custom icon for historical locations (blue)
const historyIcon = new L.Icon({
  iconRetinaUrl:
    "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  iconUrl:
    "https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-blue.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

// Custom icon for last location (red)
const lastLocationIcon = new L.Icon({
  iconRetinaUrl:
    "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  iconUrl:
    "https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-red.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

// Custom icon for real-time location (green)
const realTimeIcon = new L.Icon({
  iconRetinaUrl:
    "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  iconUrl:
    "https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-green.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

const LocationTrackerTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [currentLocation, setCurrentLocation] = useState(null);
  const [lastLocation, setLastLocation] = useState(null);
  const [history, setHistory] = useState([]);
  const [isHistoryEnabled, setIsHistoryEnabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [fetchStatus, setFetchStatus] = useState("idle");
  const [lastSync, setLastSync] = useState(null);
  const [commandType, setCommandType] = useState("current");
  const [isRealTimeTracking, setIsRealTimeTracking] = useState(false);
  const [trackingPath, setTrackingPath] = useState([]);
  const mapRef = useRef(null);

  // Fetch device IDs
  useEffect(() => {
    const fetchDevices = async () => {
      setLoading(true);
      setError(null);
      try {
        const devicesRef = ref(db, "Device");
        const snapshot = await get(devicesRef);

        if (!snapshot.exists()) {
          setError("No devices found in database.");
          setDeviceIds([]);
        } else {
          const devices = Object.keys(snapshot.val() || {});
          setDeviceIds(devices);
          if (devices.length > 0 && !selectedDevice) {
            setSelectedDevice(devices[0]);
          }
        }
      } catch (err) {
        setError(`Failed to fetch devices: ${err.message}`);
      } finally {
        setLoading(false);
      }
    };

    fetchDevices();
  }, []);

  // Listen for location updates
  useEffect(() => {
    if (!selectedDevice) return;

    let unsubscribeCurrent = () => {};
    let unsubscribeRealTime = () => {};
    let unsubscribeLast = () => {};
    let unsubscribeHistory = () => {};
    let unsubscribeCommand = () => {};

    const fetchLocations = async () => {
      setLoading(true);
      setError(null);

      try {
        // Real-time or current location listener based on isRealTimeTracking
        const locationRef = ref(
          db,
          `Device/${selectedDevice}/location/${
            isRealTimeTracking ? "realtime" : "current"
          }`
        );
        const locationListener = onValue(
          locationRef,
          (snapshot) => {
            if (snapshot.exists()) {
              const data = snapshot.val();
              const loc = {
                latitude: Number(data.latitude),
                longitude: Number(data.longitude),
                timestamp: data.timestamp,
                uploaded: data.uploaded,
                isOffline: data.isOffline || false,
              };
              if (isNaN(loc.latitude) || isNaN(loc.longitude)) {
                setError(
                  `Invalid ${
                    isRealTimeTracking ? "real-time" : "current"
                  } location data.`
                );
                return;
              }
              setCurrentLocation(loc);
              setLastSync(data.uploaded || null);

              // Update tracking path for real-time tracking
              if (isRealTimeTracking) {
                if (trackingPath.length === 0) {
                  setTrackingPath([[loc.latitude, loc.longitude]]);
                } else {
                  setTrackingPath((prev) => [
                    ...prev,
                    [loc.latitude, loc.longitude],
                  ]);
                }
              }

              // Center map on the latest location
              if (mapRef.current) {
                mapRef.current.setView([loc.latitude, loc.longitude], 13);
              }
            } else {
              setCurrentLocation(null);
              setError(
                `No ${
                  isRealTimeTracking ? "real-time" : "current"
                } location data available.`
              );
            }
            setLoading(false);
          },
          (error) => {
            setError(
              `Failed to fetch ${
                isRealTimeTracking ? "real-time" : "current"
              } location: ${error.message}`
            );
            setLoading(false);
          }
        );

        // Assign the listener to the appropriate unsubscribe function
        if (isRealTimeTracking) {
          unsubscribeRealTime = locationListener;
        } else {
          unsubscribeCurrent = locationListener;
        }

        // Last location listener
        const lastRef = ref(
          db,
          `Device/${selectedDevice}/location/lastLocation`
        );
        unsubscribeLast = onValue(
          lastRef,
          (snapshot) => {
            if (snapshot.exists()) {
              const data = snapshot.val();
              const loc = {
                latitude: Number(data.latitude),
                longitude: Number(data.longitude),
                timestamp: data.timestamp,
                uploaded: data.uploaded,
                isOffline: data.isOffline || false,
              };
              if (isNaN(loc.latitude) || isNaN(loc.longitude)) {
                setError("Invalid last location data.");
                return;
              }
              setLastLocation(loc);
              if (!lastSync || (data.uploaded && data.uploaded > lastSync)) {
                setLastSync(data.uploaded);
              }
            } else {
              setLastLocation(null);
            }
          },
          (error) => {
            setError(`Failed to fetch last location: ${error.message}`);
          }
        );

        // Command status listener
        const commandRef = ref(db, `Device/${selectedDevice}/location`);
        unsubscribeCommand = onValue(commandRef, (snapshot) => {
          if (snapshot.exists()) {
            const data = snapshot.val();
            setIsRealTimeTracking(data.getRealTime || false);
            setIsHistoryEnabled(data.getHistory || false);
            setFetchStatus(
              data.getRealTime || data.getCurrent || data.getHistory
                ? "fetching"
                : "idle"
            );
            if (
              data.lastUploadCompleted &&
              (!lastSync || data.lastUploadCompleted > lastSync)
            ) {
              setLastSync(data.lastUploadCompleted);
            }
          }
        });

        // History listener
        const historyRef = ref(db, `Device/${selectedDevice}/location/history`);
        unsubscribeHistory = onValue(
          historyRef,
          (snapshot) => {
            if (snapshot.exists()) {
              const now = Date.now();
              const oneDayAgo = now - 24 * 60 * 60 * 1000;
              const historyData = Object.values(snapshot.val() || {})
                .filter(
                  (loc) =>
                    loc.timestamp >= oneDayAgo &&
                    Number.isFinite(Number(loc.latitude)) &&
                    Number.isFinite(Number(loc.longitude))
                )
                .map((loc) => ({
                  latitude: Number(loc.latitude),
                  longitude: Number(loc.longitude),
                  timestamp: loc.timestamp,
                }));

              setHistory(historyData);
            } else {
              setHistory([]);
            }
          },
          (error) => {
            setError(`Failed to fetch location history: ${error.message}`);
          }
        );
      } catch (err) {
        setError(`Failed to set up location fetching: ${err.message}`);
        setLoading(false);
      }
    };

    fetchLocations();

    return () => {
      unsubscribeCurrent();
      unsubscribeRealTime();
      unsubscribeLast();
      unsubscribeHistory();
      unsubscribeCommand();
    };
  }, [selectedDevice, isRealTimeTracking]);

  // Reset tracking path when real-time tracking changes
  useEffect(() => {
    if (!isRealTimeTracking && currentLocation) {
      setTrackingPath([[currentLocation.latitude, currentLocation.longitude]]);
    } else if (!isRealTimeTracking) {
      setTrackingPath([]);
    }
  }, [isRealTimeTracking, currentLocation]);

  // Toggle real-time tracking
  const toggleRealTimeTracking = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }

    try {
      setFetchStatus("sending");
      const newTrackingState = !isRealTimeTracking;
      const commandData = {
        getRealTime: newTrackingState,
        getHistory: isHistoryEnabled,
      };

      const commandRef = ref(db, `Device/${selectedDevice}/location`);
      await update(commandRef, commandData);
      setIsRealTimeTracking(newTrackingState);
      setFetchStatus(
        newTrackingState || isHistoryEnabled ? "fetching" : "idle"
      );

      if (!newTrackingState && currentLocation) {
        setTrackingPath([
          [currentLocation.latitude, currentLocation.longitude],
        ]);
      } else if (!newTrackingState) {
        setTrackingPath([]);
      }
    } catch (err) {
      setError(`Failed to toggle real-time tracking: ${err.message}`);
      setFetchStatus("error");
    }
  };

  // Trigger location fetch for current or history
  const triggerLocationFetch = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }

    try {
      setFetchStatus("sending");
      const commandRef = ref(db, `Device/${selectedDevice}/location`);

      if (commandType === "history") {
        await update(commandRef, { getHistory: true });
        setIsHistoryEnabled(true);

        setTimeout(async () => {
          try {
            await update(commandRef, { getHistory: false });
            setIsHistoryEnabled(false);
          } catch (err) {
            setError(`Failed to reset getHistory: ${err.message}`);
          }
        }, 1000);
      } else if (commandType === "current") {
        await update(commandRef, { getCurrent: true });
      }

      setFetchStatus("fetching");
    } catch (err) {
      setError(`Failed to send fetch command: ${err.message}`);
      setFetchStatus("error");
    }
  };

  // Map center
  const mapCenter = currentLocation
    ? [currentLocation.latitude, currentLocation.longitude]
    : lastLocation
    ? [lastLocation.latitude, lastLocation.longitude]
    : history.length > 0
    ? [history[0].latitude, history[0].longitude]
    : [21.0, 78.0]; // Center of India

  return (
    <div className="location-tracker-container">
      <div className="location-control-panel">
        <div className="location-control-panel-header">
          <h2 className="location-control-panel-title">Location Tracker</h2>
          {lastSync && (
            <div className="location-last-sync">
              Last synced:{" "}
              {new Date(lastSync).toLocaleString("en-US", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </div>
          )}
        </div>

        <div className="location-control-panel-grid">
          <div className="location-device-selector">
            <label
              htmlFor="device-select"
              className="location-device-selector-label"
            >
              Device:
            </label>
            <select
              id="device-select"
              value={selectedDevice || ""}
              onChange={(e) => setSelectedDevice(e.target.value)}
              disabled={loading || deviceIds.length === 0}
              className="location-device-selector-select"
            >
              {deviceIds.length === 0 ? (
                <option value="">No devices found</option>
              ) : (
                deviceIds.map((deviceId) => (
                  <option key={deviceId} value={deviceId}>
                    {deviceId}
                  </option>
                ))
              )}
            </select>
          </div>

          <div className="location-fetch-controls">
            <div className="location-fetch-mode-toggle">
              <button
                className={`location-fetch-mode-button ${
                  commandType === "current" ? "location-active" : ""
                }`}
                onClick={() => setCommandType("current")}
                disabled={fetchStatus === "fetching"}
              >
                Current
              </button>
              <button
                className={`location-fetch-mode-button ${
                  commandType === "history" ? "location-active" : ""
                }`}
                onClick={() => setCommandType("history")}
                disabled={fetchStatus === "fetching"}
              >
                24-Hour History
              </button>
            </div>

            <div className="location-toggle-container">
              <label className="location-toggle-label">
                Real-Time Tracking
                <input
                  type="checkbox"
                  checked={isRealTimeTracking}
                  onChange={toggleRealTimeTracking}
                  disabled={!selectedDevice}
                  className="location-toggle-switch"
                />
                <span className="location-toggle-slider"></span>
              </label>
            </div>

            <button
              className="location-fetch-button"
              onClick={triggerLocationFetch}
              disabled={!selectedDevice || fetchStatus === "fetching"}
            >
              {fetchStatus === "fetching"
                ? "Fetching..."
                : fetchStatus === "sending"
                ? "Sending..."
                : "Fetch Location"}
            </button>
          </div>
        </div>
      </div>

      {error && (
        <div className="location-error-message">
          <span className="location-error-icon">⚠️</span>
          <span className="location-error-text">{error}</span>
        </div>
      )}

      {loading && <div className="location-loading">Loading...</div>}

      <div className="location-map-container">
        <MapContainer
          center={mapCenter}
          zoom={currentLocation || lastLocation || history.length > 0 ? 13 : 5}
          style={{ height: "100%", width: "100%" }}
          ref={mapRef}
          zoomControl={true}
        >
          <LayersControl position="topright">
            <LayersControl.BaseLayer name="Street Map">
              <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
            </LayersControl.BaseLayer>
            <LayersControl.BaseLayer checked name="Satellite">
              <TileLayer url="https://tiles.stadiamaps.com/tiles/alidade_satellite/{z}/{x}/{y}{r}.png" />
            </LayersControl.BaseLayer>
          </LayersControl>
          <ScaleControl position="bottomleft" />
          {currentLocation && (
            <Marker
              position={[currentLocation.latitude, currentLocation.longitude]}
              icon={isRealTimeTracking ? realTimeIcon : defaultIcon}
            >
              <Popup
                className={
                  isRealTimeTracking ? "realtime-popup" : "current-popup"
                }
              >
                {isRealTimeTracking
                  ? "Real-Time Location"
                  : currentLocation.isOffline
                  ? "Last Known Current Location"
                  : "Current Location"}
                <br />
                Time:{" "}
                {new Date(currentLocation.timestamp).toLocaleString("en-US", {
                  dateStyle: "medium",
                  timeStyle: "short",
                })}
                <br />
                Lat: {currentLocation.latitude.toFixed(6)}
                <br />
                Lon: {currentLocation.longitude.toFixed(6)}
                <br />
                <a
                  href={`https://www.google.com/maps?q=${currentLocation.latitude},${currentLocation.longitude}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="google-maps-link"
                >
                  View in Google Maps
                </a>
              </Popup>
            </Marker>
          )}
          {lastLocation && (
            <Marker
              position={[lastLocation.latitude, lastLocation.longitude]}
              icon={lastLocationIcon}
            >
              <Popup className="last-location-popup">
                {lastLocation.isOffline
                  ? "Last Known Location (Offline)"
                  : "Last Known Location"}
                <br />
                Time:{" "}
                {new Date(lastLocation.timestamp).toLocaleString("en-US", {
                  dateStyle: "medium",
                  timeStyle: "short",
                })}
                <br />
                Lat: {lastLocation.latitude.toFixed(6)}
                <br />
                Lon: {lastLocation.longitude.toFixed(6)}
                <br />
                <a
                  href={`https://www.google.com/maps?q=${lastLocation.latitude},${lastLocation.longitude}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="google-maps-link"
                >
                  View in Google Maps
                </a>
              </Popup>
            </Marker>
          )}
          {history.length > 0 && (
            <>
              <Polyline
                positions={history.map((loc) => [loc.latitude, loc.longitude])}
                color="blue"
              />
              {history.map((loc, index) => (
                <Marker
                  key={index}
                  position={[loc.latitude, loc.longitude]}
                  icon={historyIcon}
                >
                  <Popup className="history-popup">
                    Historical Location
                    <br />
                    Time:{" "}
                    {new Date(loc.timestamp).toLocaleString("en-US", {
                      dateStyle: "medium",
                      timeStyle: "short",
                    })}
                    <br />
                    Lat: {loc.latitude.toFixed(6)}
                    <br />
                    Lon: {loc.longitude.toFixed(6)}
                    <br />
                    <a
                      href={`https://www.google.com/maps?q=${loc.latitude},${loc.longitude}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="google-maps-link"
                    >
                      View in Google Maps
                    </a>
                  </Popup>
                </Marker>
              ))}
            </>
          )}
          {trackingPath.length > 1 && (
            <Polyline positions={trackingPath} color="green" />
          )}
        </MapContainer>
      </div>

      {(history.length > 0 || lastLocation) && (
        <div className="location-history-panel">
          <h3 className="location-history-title">Location Data</h3>
          <ul className="location-history-list">
            {lastLocation && (
              <li className="location-history-item">
                <strong>
                  {lastLocation.isOffline
                    ? "Last Known Location (Offline)"
                    : "Last Known Location"}
                </strong>
                :{" "}
                {new Date(lastLocation.timestamp).toLocaleString("en-US", {
                  dateStyle: "medium",
                  timeStyle: "short",
                })}
                , Lat {lastLocation.latitude.toFixed(6)}, Lon{" "}
                {lastLocation.longitude.toFixed(6)}
              </li>
            )}
            {history.map((loc, index) => (
              <li key={index} className="location-history-item">
                Historical Location:{" "}
                {new Date(loc.timestamp).toLocaleString("en-US", {
                  dateStyle: "medium",
                  timeStyle: "short",
                })}
                , Lat {loc.latitude.toFixed(6)}, Lon {loc.longitude.toFixed(6)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

export default LocationTrackerTab;
