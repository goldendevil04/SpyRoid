import React, { useEffect, useState, useRef } from "react";
import { ref, onValue, update, get } from "firebase/database";
import { database } from "../firebaseConfig"; // Adjust path
import * as noVNC from "novnc";
import "./LivePhoneTab.css";

const LivePhoneTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [isScreenSharing, setIsScreenSharing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const canvasRef = useRef(null);
  const vncClientRef = useRef(null);
  const [lastSync, setLastSync] = useState(null);

  useEffect(() => {
    const fetchDevices = async () => {
      setLoading(true);
      setError(null);
      try {
        const devicesRef = ref(database, "Device");
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

  useEffect(() => {
    if (!selectedDevice) return;

    const screenShareRef = ref(
      database,
      `Device/${selectedDevice}/screenShare`
    );
    const unsubscribe = onValue(screenShareRef, (snapshot) => {
      if (snapshot.exists()) {
        const data = snapshot.val();
        setIsScreenSharing(data.isScreenSharing || false);
        setLastSync(data.lastSync || null);

        if (data.vncAddress && !vncClientRef.current) {
          try {
            vncClientRef.current = new noVNC.RFB({
              target: canvasRef.current,
              url: `ws://${data.vncAddress}`,
              credentials: null, // No password
            });
            vncClientRef.current.addEventListener("connect", () => {
              console.log("VNC connected");
            });
            vncClientRef.current.addEventListener("disconnect", () => {
              console.log("VNC disconnected");
              vncClientRef.current = null;
            });
            vncClientRef.current.addEventListener("mouseclick", (event) => {
              sendInputCommand("click", { x: event.x, y: event.y });
            });
          } catch (err) {
            setError(`VNC connection failed: ${err.message}`);
          }
        }
      }
    });

    return () => {
      if (vncClientRef.current) {
        vncClientRef.current.disconnect();
        vncClientRef.current = null;
      }
      unsubscribe();
    };
  }, [selectedDevice]);

  const startScreenSharing = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }
    try {
      setLoading(true);
      await update(ref(database, `Device/${selectedDevice}/screenShare`), {
        startScreenShare: true,
        lastSync: Date.now(),
      });
    } catch (err) {
      setError(`Failed to start screen sharing: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  const stopScreenSharing = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }
    try {
      setLoading(true);
      await update(ref(database, `Device/${selectedDevice}/screenShare`), {
        stopScreenShare: true,
        isScreenSharing: false,
        lastSync: Date.now(),
      });
      if (vncClientRef.current) {
        vncClientRef.current.disconnect();
        vncClientRef.current = null;
      }
    } catch (err) {
      setError(`Failed to stop screen sharing: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  const sendInputCommand = async (type, data) => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }
    try {
      await update(ref(database, `Device/${selectedDevice}/inputCommands`), {
        type,
        data,
        timestamp: Date.now(),
      });
    } catch (err) {
      setError(`Failed to send input command: ${err.message}`);
    }
  };

  return (
    <div className="live-phone-container">
      <div className="control-panel">
        <h2 className="control-panel-title">Live Phone Screen</h2>
        {lastSync && (
          <div className="last-sync">
            Last synced:{" "}
            {new Date(lastSync).toLocaleString("en-US", {
              dateStyle: "medium",
              timeStyle: "short",
            })}
          </div>
        )}
        <div className="device-selector">
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
        <div className="control-buttons">
          <button
            onClick={startScreenSharing}
            disabled={loading || isScreenSharing || !selectedDevice}
          >
            {loading ? "Starting..." : "Start Screen Sharing"}
          </button>
          <button
            onClick={stopScreenSharing}
            disabled={loading || !isScreenSharing || !selectedDevice}
          >
            Stop Screen Sharing
          </button>
        </div>
      </div>
      {error && <div className="error-message">{error}</div>}
      {loading && <div className="loading">Loading...</div>}
      <div className="video-container">
        <canvas
          ref={canvasRef}
          style={{ width: "100%", height: "auto", border: "1px solid #ccc" }}
        />
      </div>
    </div>
  );
};

export default LivePhoneTab;
