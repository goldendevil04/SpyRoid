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
  const [selectedSim, setSelectedSim] = useState(null); // Now stores the entire sim object
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
    isLoading: isCallLoading,
    statusMsg: callStatusMsg,
    isError: isCallError,
    resetStatus: resetCallStatus,
  } = useSendCall(database, selectedDevice);

  useEffect(() => {
    setSimCards([]);
    setSelectedSim(null);
    setRecipients("");
    setMessage("");
    setCallRecipient("");
    setError(null);

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
      return;
    }

    // SMS status
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

    // Call status
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

    return () => {
      smsUnsubscribe();
      callUnsubscribe();
      setSmsStatusCounts({ pending: 0, failed: 0 });
      setCallStatusCounts({ pending: 0, failed: 0 });
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
    sendCall({ selectedSim: selectedSim.slot, recipient: callRecipient }); // Use slot for sim_number
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

  return (
    <div className="phone">
      <div className="phone-status-bar">
        <span>9:41</span>
        <span>🔋 100%</span>
      </div>

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

        {/* SMS Form */}
        <form onSubmit={handleSmsSubmit} className="input-group">
          <h3>Send SMS</h3>
          <label>Recipient Number(s):</label>
          <textarea
            className="recipients-area"
            type="text"
            value={recipients}
            onChange={(e) => setRecipients(e.target.value)}
            placeholder="+91 8345678910, +91 7001234567, ..."
            rows={3}
            disabled={!selectedDevice}
          />

          <label>Message:</label>
          <textarea
            className="message-area"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder="Enter message"
            disabled={!selectedDevice}
          />

          <div className="sms-status-summary">
            <p>Pending SMS Numbers: {smsStatusCounts.pending}</p>
            <p>Failed SMS Commands: {smsStatusCounts.failed}</p>
          </div>

          {!!smsStatusMsg && (
            <p
              id="sms-status"
              className={isSmsError ? "status-error" : "status-success"}
            >
              {smsStatusMsg}
            </p>
          )}

          <button type="submit" disabled={isSmsLoading || !selectedDevice}>
            {isSmsLoading ? (
              <>
                <div className="button-loading" /> Sending SMS…
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
            type="text"
            value={callRecipient}
            onChange={(e) => setCallRecipient(e.target.value)}
            placeholder="+91 8345678910"
            disabled={!selectedDevice}
          />

          <div className="call-status-summary">
            <p>Pending Calls: {callStatusCounts.pending}</p>
            <p>Failed Calls: {callStatusCounts.failed}</p>
          </div>

          {!!callStatusMsg && (
            <p
              id="call-status"
              className={isCallError ? "status-error" : "status-success"}
            >
              {callStatusMsg}
            </p>
          )}

          <button type="submit" disabled={isCallLoading || !selectedDevice}>
            {isCallLoading ? (
              <>
                <div className="button-loading" /> Initiating Call…
              </>
            ) : (
              "Make Call"
            )}
          </button>
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
