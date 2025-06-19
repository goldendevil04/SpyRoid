import React, { useEffect, useState } from "react";
import { ref, get, set, onValue } from "firebase/database";
import { database as db } from "../firebaseConfig";
import "./WebViewTab.css";

const WebViewTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [webViewData, setWebViewData] = useState({
    LaunchWebView: false,
    webUrl: "",
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [lastUpdate, setLastUpdate] = useState(null);
  const [webUrlInput, setWebUrlInput] = useState("");
  const [launchWebView, setLaunchWebView] = useState(false);

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

  // Listen for WebView data updates
  useEffect(() => {
    if (!selectedDevice) return;

    setLoading(true);
    const webViewRef = ref(db, `Device/${selectedDevice}/webview`);

    const unsubscribe = onValue(
      webViewRef,
      (snapshot) => {
        if (!snapshot.exists()) {
          setWebViewData({ LaunchWebView: false, webUrl: "" });
          setLastUpdate(null);
        } else {
          const data = snapshot.val();
          setWebViewData({
            LaunchWebView: data.LaunchWebView || false,
            webUrl: data.webUrl || "",
          });
          setLastUpdate(data.lastUpdated || null);
          setWebUrlInput(data.webUrl || "");
          setLaunchWebView(data.LaunchWebView || false);
        }
        setLoading(false);
      },
      (error) => {
        setError(`Failed to fetch WebView data: ${error.message}`);
        setLoading(false);
      }
    );

    return () => unsubscribe();
  }, [selectedDevice]);

  // Send WebView command
  const sendWebViewCommand = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }

    try {
      setLoading(true);
      const commandData = {
        LaunchWebView: launchWebView,
        webUrl: webUrlInput.trim(),
        lastUpdated: Date.now(),
      };

      const webViewRef = ref(db, `Device/${selectedDevice}/webview`);
      await set(webViewRef, commandData);
    } catch (err) {
      setError(`Failed to send WebView command: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  // Refresh WebView data
  const refreshWebViewData = async () => {
    if (!selectedDevice) return;

    setLoading(true);
    try {
      const webViewRef = ref(db, `Device/${selectedDevice}/webview`);
      const snapshot = await get(webViewRef);

      if (!snapshot.exists()) {
        setWebViewData({ LaunchWebView: false, webUrl: "" });
        setLastUpdate(null);
      } else {
        const data = snapshot.val();
        setWebViewData({
          LaunchWebView: data.LaunchWebView || false,
          webUrl: data.webUrl || "",
        });
        setLastUpdate(data.lastUpdated || null);
        setWebUrlInput(data.webUrl || "");
        setLaunchWebView(data.LaunchWebView || false);
      }
    } catch (err) {
      setError(`Failed to refresh WebView data: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="webview-tab-container">
      <div className="webview-control-panel">
        <div className="webview-control-panel-header">
          <h2 className="webview-panel-title">WebView Control</h2>
          {lastUpdate && (
            <div className="webview-last-update">
              Last updated:{" "}
              {new Date(lastUpdate).toLocaleString("en-US", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </div>
          )}
        </div>

        <div className="webview-control-panel-grid">
          <div className="webview-device-selector">
            <label htmlFor="device-select">Device:</label>
            <select
              id="device-select"
              value={selectedDevice || ""}
              onChange={(e) => setSelectedDevice(e.target.value)}
              disabled={loading || deviceIds.length === 0}
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

          <div className="webview-command-controls">
            <div className="webview-url-input">
              <label htmlFor="webview-url">Web URL:</label>
              <input
                id="webview-url"
                type="text"
                value={webUrlInput}
                onChange={(e) => setWebUrlInput(e.target.value)}
                placeholder="Enter URL (e.g., example.com)"
                disabled={loading}
              />
            </div>

            <div className="webview-launch-toggle">
              <label htmlFor="launch-webview">Launch WebView:</label>
              <input
                id="launch-webview"
                type="checkbox"
                checked={launchWebView}
                onChange={(e) => setLaunchWebView(e.target.checked)}
                disabled={loading}
              />
            </div>

            <div className="webview-buttons-container">
              <button
                className="webview-send-button"
                onClick={sendWebViewCommand}
                disabled={!selectedDevice || loading || !webUrlInput.trim()}
              >
                {loading ? "Sending..." : "Send Command"}
              </button>
              <button
                className="webview-refresh-button"
                onClick={refreshWebViewData}
                disabled={!selectedDevice || loading}
              >
                Refresh
              </button>
            </div>
          </div>
        </div>
      </div>

      {error && (
        <div className="webview-error-message">
          <div className="webview-error-icon">⚠️</div>
          <div className="webview-error-text">{error}</div>
        </div>
      )}

      {loading && (
        <div className="webview-loading">Loading WebView data...</div>
      )}

      {!loading && !webViewData.webUrl && (
        <div className="webview-no-data">No WebView data available</div>
      )}

      {!loading && webViewData.webUrl && (
        <div className="webview-status-panel">
          <h3 className="webview-status-title">Current WebView Status</h3>
          <div className="webview-status-details">
            <div className="webview-detail-row">
              <span className="webview-detail-label">URL:</span>
              <span className="webview-detail-value">{webViewData.webUrl}</span>
            </div>
            <div className="webview-detail-row">
              <span className="webview-detail-label">Launch Status:</span>
              <span className="webview-detail-value">
                {webViewData.LaunchWebView ? "Active" : "Inactive"}
              </span>
            </div>
            {lastUpdate && (
              <div className="webview-detail-row">
                <span className="webview-detail-label">Last Updated:</span>
                <span className="webview-detail-value">
                  {new Date(lastUpdate).toLocaleString("en-US", {
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default WebViewTab;
