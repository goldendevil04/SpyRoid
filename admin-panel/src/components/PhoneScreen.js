import React, { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { ref, onValue } from "firebase/database";
import useSendSms from "./useSendSms";
import useSendCall from "./useSendCall";
import "./PhoneScreen.css";
import AdminNumberSettings from "./AdminNumberSettings";

const PhoneScreen = ({
  devices = [],
  selectedDevice,
  setSelectedDevice,
  database,
}) => {
  const [simCards, setSimCards] = useState([]);
  const [selectedSim, setSelectedSim] = useState(null);
  const [recipients, setRecipients] = useState("");
  const [message, setMessage] = useState("");
  const [callRecipient, setCallRecipient] = useState("");
  const [error, setError] = useState(null);
  const [smsStatusCounts, setSmsStatusCounts] = useState({
    pending: 0,
    failed: 0,
  });
  const [callStatusCounts, setCallStatusCounts] = useState({
    pending: 0,
    failed: 0,
  });
  const [callStatus, setCallStatus] = useState({
    status: "idle",
    number: null,
    commandId: null,
    startTime: null,
    duration: null,
  });

  const navigate = useNavigate();
  const {
    sendSms,
    isLoading: isSmsLoading,
    statusMsg: smsStatusMsg,
    isError: isSmsError,
    resetStatus: resetSmsStatus,
  } = useSendSms(database, selectedDevice);
  const {
    sendCall,
    endCall,
    isLoading: isCallLoading,
    statusMsg: callStatusMsg,
    isError: isCallError,
    resetStatus: resetCallStatus,
  } = useSendCall(database, selectedDevice);

  // Format call duration
  const formatDuration = (seconds) => {
    if (!seconds) return "00:00";
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  };

  // Calculate current call duration
  const [currentDuration, setCurrentDuration] = useState(0);

  useEffect(() => {
    let interval;
    if (callStatus.status === "active" && callStatus.startTime) {
      interval = setInterval(() => {
        const duration = Math.floor((Date.now() - callStatus.startTime) / 1000);
        setCurrentDuration(duration);
      }, 1000);
    } else {
      setCurrentDuration(0);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [callStatus.status, callStatus.startTime]);

  useEffect(() => {
    setSimCards([]);
    setSelectedSim(null);
    setRecipients("");
    setMessage("");
    setCallRecipient("");
    setError(null);
    setCallStatus({
      status: "idle",
      number: null,
      commandId: null,
      startTime: null,
      duration: null,
    });

    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }

    const simRef = ref(database, `Device/${selectedDevice}/sim_cards`);
    const unsubscribe = onValue(
      simRef,
      (snap) => {
        if (!snap.exists()) {
          setSimCards([]);
          setError("No SIM cards found for this device.");
        } else {
          const parsed = Object.entries(snap.val()).map(([slot, number]) => ({
            slot,
            number,
          }));
          setSimCards(parsed);
          setError(null);
        }
      },
      (err) => {
        console.error("Error loading SIM numbers:", err);
        setError("Failed to load SIM cards.");
      }
    );

    return () => {
      unsubscribe();
      setSelectedSim(null);
    };
  }, [selectedDevice, database]);

  useEffect(() => {
    if (!selectedDevice) {
      setSmsStatusCounts({ pending: 0, failed: 0 });
      setCallStatusCounts({ pending: 0, failed: 0 });
      setCallStatus({
        status: "idle",
        number: null,
        commandId: null,
        startTime: null,
        duration: null,
      });
      return;
    }

    // SMS status monitoring
    const smsCommandsRef = ref(
      database,
      `Device/${selectedDevice}/send_sms_commands`
    );
    const smsUnsubscribe = onValue(
      smsCommandsRef,
      (snapshot) => {
        if (!snapshot.exists()) {
          setSmsStatusCounts({ pending: 0, failed: 0 });
          return;
        }
        const commands = snapshot.val();
        let pendingNumbers = 0,
          failed = 0;

        Object.values(commands).forEach((cmd) => {
          if (cmd.status === "pending" && Array.isArray(cmd.recipients)) {
            pendingNumbers += cmd.recipients.length;
          } else if (cmd.status === "failed") {
            failed++;
          }
        });

        setSmsStatusCounts({ pending: pendingNumbers, failed });
      },
      (err) => {
        console.error("Error fetching SMS commands:", err);
        setError("Failed to load SMS status.");
      }
    );

    // Call commands monitoring
    const callCommandsRef = ref(
      database,
      `Device/${selectedDevice}/call_commands`
    );
    const callUnsubscribe = onValue(
      callCommandsRef,
      (snapshot) => {
        if (!snapshot.exists()) {
          setCallStatusCounts({ pending: 0, failed: 0 });
          return;
        }
        const commands = snapshot.val();
        let pending = 0,
          failed = 0;

        Object.values(commands).forEach((cmd) => {
          if (cmd.status === "pending") {
            pending++;
          } else if (cmd.status === "failed") {
            failed++;
          }
        });

        setCallStatusCounts({ pending, failed });
      },
      (err) => {
        console.error("Error fetching call commands:", err);
        setError("Failed to load call status.");
      }
    );

    // Real-time call status monitoring
    const callStatusRef = ref(database, `Device/${selectedDevice}/call_status`);
    const callStatusUnsubscribe = onValue(
      callStatusRef,
      (snapshot) => {
        if (snapshot.exists()) {
          const status = snapshot.val();
          setCallStatus({
            status: status.status || "idle",
            number: status.number || null,
            commandId: status.commandId || null,
            startTime: status.startTime || null,
            duration: status.lastCallDuration || null,
          });
        } else {
          setCallStatus({
            status: "idle",
            number: null,
            commandId: null,
            startTime: null,
            duration: null,
          });
        }
      },
      (err) => {
        console.error("Error fetching call status:", err);
      }
    );

    return () => {
      smsUnsubscribe();
      callUnsubscribe();
      callStatusUnsubscribe();
      setSmsStatusCounts({ pending: 0, failed: 0 });
      setCallStatusCounts({ pending: 0, failed: 0 });
      setCallStatus({
        status: "idle",
        number: null,
        commandId: null,
        startTime: null,
        duration: null,
      });
    };
  }, [selectedDevice, database]);

  const handleSmsSubmit = (e) => {
    e.preventDefault();
    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }
    if (!selectedSim || !recipients || !message) {
      setError("Please fill all SMS fields.");
      return;
    }
    sendSms({
      selectedSim: selectedSim.number,
      recipient: recipients,
      message,
    });
  };

  const handleCallSubmit = (e) => {
    e.preventDefault();
    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }
    if (!selectedSim || !callRecipient) {
      setError("Please fill all call fields.");
      return;
    }
    if (callStatus.status !== "idle") {
      setError("A call is already in progress.");
      return;
    }
    sendCall({ selectedSim: selectedSim.slot, recipient: callRecipient });
  };

  const handleEndCall = () => {
    if (!callStatus.commandId) {
      setError("No active call to end.");
      return;
    }
    endCall(callStatus.commandId);
  };

  useEffect(() => {
    if (smsStatusMsg && !isSmsError) {
      setRecipients("");
      setMessage("");
      setSelectedSim(null);
      setError(null);
    }
    if (callStatusMsg && !isCallError) {
      setCallRecipient("");
      setSelectedSim(null);
      setError(null);
    }
  }, [smsStatusMsg, isSmsError, callStatusMsg, isCallError]);

  useEffect(() => {
    if (!smsStatusMsg && !callStatusMsg) return;
    const t = setTimeout(() => {
      resetSmsStatus();
      resetCallStatus();
    }, 3000);
    return () => clearTimeout(t);
  }, [smsStatusMsg, callStatusMsg, resetSmsStatus, resetCallStatus]);

  const handleDeviceChange = (e) => {
    const newDevice = e.target.value;
    setSelectedDevice(newDevice);
    setError(null);
  };

  const getCallStatusDisplay = () => {
    switch (callStatus.status) {
      case "dialing":
        return `📞 Dialing ${callStatus.number || "Unknown"}...`;
      case "ringing":
        return `📞 Ringing ${callStatus.number || "Unknown"}...`;
      case "active":
        return `📞 Call Active: ${callStatus.number || "Unknown"} - ${formatDuration(currentDuration)}`;
      case "idle":
      default:
        return callStatus.duration 
          ? `Last call duration: ${formatDuration(callStatus.duration)}`
          : "No active call";
    }
  };

  const getCallStatusClass = () => {
    switch (callStatus.status) {
      case "dialing":
        return "call-status-dialing";
      case "ringing":
        return "call-status-ringing";
      case "active":
        return "call-status-active";
      case "idle":
      default:
        return "call-status-idle";
    }
  };

  return (
    <div className="phone">
      <div className="phone-status-bar">
        <span>11:45 PM</span>
        <span>🔋 100%</span>
      </div>
      <div className="dynamic-island" />
      <button
        className="phone-back-button"
        onClick={() => navigate("/", { state: { selectedDevice } })}
      >
        ← Back
      </button>

      <div className="screen">
        {error && <div className="error-message">{error}</div>}
        
        <div className="select-group">
          <select
            value={selectedDevice}
            onChange={handleDeviceChange}
            className="device-select"
          >
            <option value="">Select a Device</option>
            {devices.map((device) => (
              <option key={device} value={device}>
                {device}
              </option>
            ))}
          </select>
          <select
            value={selectedSim ? selectedSim.number : ""}
            onChange={(e) => {
              const sim = simCards.find((s) => s.number === e.target.value);
              setSelectedSim(sim || null);
            }}
            className="sim-select"
            disabled={!selectedDevice}
          >
            <option value="">Select SIM</option>
            {simCards.map((sim) => (
              <option key={sim.slot} value={sim.number}>
                {sim.slot}: {sim.number}
              </option>
            ))}
          </select>
        </div>

        {/* Real-time Call Status Display */}
        <div className={`call-status-display ${getCallStatusClass()}`}>
          <h3>📞 Call Status</h3>
          <div className="call-status-info">
            <p className="call-status-text">{getCallStatusDisplay()}</p>
            {callStatus.status === "active" && (
              <div className="call-controls">
                <button
                  onClick={handleEndCall}
                  className="end-call-button-large"
                  disabled={!callStatus.commandId}
                >
                  🔴 End Call
                </button>
              </div>
            )}
          </div>
        </div>

        {/* SMS Form */}
        <form onSubmit={handleSmsSubmit} className="input-group">
          <h3>Send SMS</h3>
          <label>Recipient Number(s):</label>
          <textarea
            className="recipients-area"
            value={recipients}
            onChange={(e) => setRecipients(e.target.value)}
            placeholder="+91 1234567890, +91 9876543210, ..."
            rows={3}
            disabled={!selectedDevice}
          />

          <label>Message:</label>
          <textarea
            className="message-area"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder="Enter message"
            rows={3}
            disabled={!selectedDevice}
          />

          <div className="sms-status-summary">
            <p>Pending: {smsStatusCounts.pending}</p>
            <p>Failed: {smsStatusCounts.failed}</p>
          </div>

          {!!smsStatusMsg && (
            <p
              id="sms-status"
              className={
                isSmsError ? "status-error" : "status-success status-animation"
              }
            >
              {smsStatusMsg}
            </p>
          )}

          <button type="submit" disabled={isSmsLoading || !selectedDevice}>
            {isSmsLoading ? (
              <>
                <span className="button-loading" /> Sending SMS…
              </>
            ) : (
              "Send SMS"
            )}
          </button>
        </form>

        {/* Call Form */}
        <form onSubmit={handleCallSubmit} className="input-group">
          <h3>Make Call</h3>
          <label>Recipient Number:</label>
          <input
            className="recipient-input"
            type="tel"
            value={callRecipient}
            onChange={(e) => setCallRecipient(e.target.value)}
            placeholder="+91 1234567890"
            disabled={!selectedDevice || callStatus.status !== "idle"}
          />

          <div className="call-status-summary">
            <p>Pending Calls: {callStatusCounts.pending}</p>
            <p>Failed Calls: {callStatusCounts.failed}</p>
          </div>

          {!!callStatusMsg && (
            <p
              id="call-status"
              className={
                isCallError ? "status-error" : "status-success status-animation"
              }
            >
              {callStatusMsg}
            </p>
          )}

          <div className="call-button-group">
            <button
              type="submit"
              disabled={isCallLoading || !selectedDevice || callStatus.status !== "idle"}
              className="make-call-button"
            >
              {isCallLoading ? (
                <>
                  <span className="button-loading" /> Initiating Call…
                </>
              ) : (
                "📞 Make Call"
              )}
            </button>
            
            {callStatus.status !== "idle" && (
              <button
                type="button"
                onClick={handleEndCall}
                disabled={!callStatus.commandId}
                className="end-call-button"
              >
                🔴 End Call
              </button>
            )}
          </div>
        </form>

        <AdminNumberSettings
          selectedDevice={selectedDevice}
          database={database}
        />
      </div>

      {(isSmsLoading || isCallLoading) && (
        <div className="loading-overlay visible">
          <div>
            <div className="loading" />
            <span>Processing…</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default PhoneScreen;