import React, { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { ref, onValue, push, get, remove, set } from "firebase/database";
import "./PhoneScreenTerminal.css";

const PhoneScreenTerminal = ({
  selectedDevice,
  setSelectedDevice,
  database,
  devices = [],
}) => {
  const [command, setCommand] = useState("");
  const [output, setOutput] = useState("");
  const [connected, setConnected] = useState(false);
  const [showCommands, setShowCommands] = useState(false);
  const [allCommands, setAllCommands] = useState([]);
  const [error, setError] = useState(null);
  const [isLoadingCommands, setIsLoadingCommands] = useState(false);
  const [appList, setAppList] = useState([]);
  const [showAppList, setShowAppList] = useState(false);
  const [isLoadingAppList, setIsLoadingAppList] = useState(false);
  const [appFilter, setAppFilter] = useState("all"); // New state for filter

  const navigate = useNavigate();

  useEffect(() => {
    setCommand("");
    setOutput("");
    setAllCommands([]);
    setShowCommands(false);
    setAppList([]);
    setShowAppList(false);
    setAppFilter("all");
    setError(null);

    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }

    // Listen for connection status
    const connectedRef = ref(
      database,
      `Device/${selectedDevice}/shell/connected`
    );
    const unsubscribeConnected = onValue(
      connectedRef,
      (snap) => {
        setConnected(snap.val() === true);
        setError(null);
      },
      (err) => {
        console.error("Error fetching connection status:", err);
        setError("Failed to load connection status.");
      }
    );

    // Listen for command queue results
    const queueRef = ref(database, `Device/${selectedDevice}/shell/queue`);
    const unsubscribeQueue = onValue(
      queueRef,
      (snap) => {
        if (snap.exists()) {
          const queue = snap.val();
          const lastCommandEntry = Object.entries(queue)
            .reverse()
            .find(([, v]) => v.executed === true);

          if (lastCommandEntry) {
            const [, data] = lastCommandEntry;
            const resultText =
              `Command: ${data.command}\n` +
              `Status: ${data.status || "unknown"}\n` +
              `Output:\n${data.output || ""}\n` +
              `Error:\n${data.error || ""}`;
            setOutput(resultText);
            setError(null);
          } else {
            setOutput("Waiting for command result...");
          }
        } else {
          setOutput("Waiting for command result...");
        }
      },
      (err) => {
        console.error("Error fetching command queue:", err);
        setError("Failed to load command results.");
      }
    );

    // Listen for app list
    const appListRef = ref(
      database,
      `Device/${selectedDevice}/shell/applist/apps`
    );
    const unsubscribeAppList = onValue(
      appListRef,
      (snap) => {
        if (snap.exists()) {
          const apps = snap.val();
          setAppList(apps || []);
          setError(null);
        } else {
          setAppList([]);
        }
      },
      (err) => {
        console.error("Error fetching app list:", err);
        setError("Failed to load app list.");
      }
    );

    return () => {
      unsubscribeConnected();
      unsubscribeQueue();
      unsubscribeAppList();
    };
  }, [selectedDevice, database]);

  useEffect(() => {
    const terminalOutput = document.querySelector(".terminal-output");
    if (terminalOutput) {
      terminalOutput.scrollTop = terminalOutput.scrollHeight;
    }
  }, [output]);

  const handleSendCommand = async (e) => {
    e.preventDefault();
    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }
    if (!command.trim()) {
      setError("Please enter a command.");
      return;
    }

    try {
      const commandRef = ref(database, `Device/${selectedDevice}/shell/queue`);
      await push(commandRef, {
        command: command.trim(),
        executed: false,
      });
      setCommand("");
      setError(null);
    } catch (err) {
      console.error("Failed to send command:", err);
      setError("Failed to send command. Please try again.");
    }
  };

  const toggleShowCommands = async () => {
    setShowCommands((prev) => !prev);

    if (!showCommands) {
      if (!selectedDevice) {
        setError("Please select a device.");
        return;
      }
      setIsLoadingCommands(true);
      const queueRef = ref(database, `Device/${selectedDevice}/shell/queue`);
      try {
        const snap = await get(queueRef);
        if (snap.exists()) {
          const commands = snap.val();
          const formatted = Object.entries(commands).map(([key, val]) => ({
            key,
            ...val,
          }));
          setAllCommands(formatted.reverse());
        } else {
          setAllCommands([]);
        }
        setError(null);
      } catch (err) {
        console.error("Error fetching all commands:", err);
        setError("Failed to load command history.");
      } finally {
        setIsLoadingCommands(false);
      }
    }
  };

  const handleDeleteCommand = async (key) => {
    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }
    try {
      await remove(
        ref(database, `Device/${selectedDevice}/shell/queue/${key}`)
      );
      setAllCommands((prev) => prev.filter((cmd) => cmd.key !== key));
      setError(null);
    } catch (err) {
      console.error("Failed to delete command:", err);
      setError("Could not delete command.");
    }
  };

  const handleFetchAppList = async () => {
    if (!selectedDevice) {
      setError("Please select a device.");
      return;
    }
    setIsLoadingAppList(true);
    try {
      await set(
        ref(database, `Device/${selectedDevice}/shell/applist/getApps`),
        {
          value: true,
          filter: appFilter, // Include filter in the request
        }
      );
      setError(null);
    } catch (err) {
      console.error("Failed to request app list:", err);
      setError("Failed to request app list. Please try again.");
    } finally {
      setIsLoadingAppList(false);
    }
  };

  const toggleShowAppList = () => {
    setShowAppList((prev) => !prev);
  };

  const handleDeviceChange = (e) => {
    const newDevice = e.target.value;
    setSelectedDevice(newDevice);
    setError(null);
  };

  const handleFilterChange = (e) => {
    setAppFilter(e.target.value);
  };

  return (
    <div className="phone-terminal">
      <div className="status-bar">
        <span>9:41</span>
        <span>🔋 100%</span>
      </div>

      <button
        className="back-button"
        onClick={() => navigate("/", { state: { selectedDevice } })}
      >
        ← Back
      </button>

      <div className="terminal-screen">
        {error && <div className="error-message">{error}</div>}
        <div className="terminal-header">
          <select
            value={selectedDevice}
            onChange={handleDeviceChange}
            className="device-select-terminal"
          >
            <option value="">Select a Device</option>
            {devices.map((device) => (
              <option key={device} value={device}>
                {device}
              </option>
            ))}
          </select>
          {selectedDevice && (
            <span
              className={`status-indicator ${connected ? "online" : "offline"}`}
            >
              {connected ? "Connected" : "Disconnected"}
            </span>
          )}
        </div>

        <form onSubmit={handleSendCommand} className="terminal-input">
          <label htmlFor="command">Shell Command:</label>
          <input
            id="command"
            type="text"
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            placeholder="e.g., open https://www.google.com or openapp com.whatsapp"
            disabled={!selectedDevice}
          />
          <button type="submit" disabled={!selectedDevice}>
            Run
          </button>
        </form>

        <div className="terminal-buttons">
          <button
            className="toggle-command-list"
            onClick={toggleShowCommands}
            disabled={!selectedDevice}
          >
            {showCommands ? "Hide Commands" : "Show All Commands"}
          </button>
          <div className="app-filter">
            <label htmlFor="appFilter">App Filter:</label>
            <select
              id="appFilter"
              value={appFilter}
              onChange={handleFilterChange}
              disabled={!selectedDevice}
              className="app-filter-select"
            >
              <option value="all">All Apps</option>
              <option value="system">System Apps</option>
              <option value="user">User Apps</option>
            </select>
          </div>
          <button
            className="fetch-app-list"
            onClick={handleFetchAppList}
            disabled={!selectedDevice || isLoadingAppList}
          >
            {isLoadingAppList ? "Fetching Apps..." : "Fetch App List"}
          </button>
          <button
            className="toggle-app-list"
            onClick={toggleShowAppList}
            disabled={!selectedDevice || appList.length === 0}
          >
            {showAppList ? "Hide App List" : "Show App List"}
          </button>
        </div>

        {showCommands && (
          <div className="command-list">
            {isLoadingCommands ? (
              <p>Loading commands...</p>
            ) : allCommands.length === 0 ? (
              <p>No commands available.</p>
            ) : (
              <ul>
                {allCommands.map((cmd) => (
                  <li key={cmd.key}>
                    <div>
                      <strong>{cmd.command}</strong>
                      <div>
                        Status: {cmd.executed ? "✅ Executed" : "⏳ Pending"}
                      </div>
                    </div>
                    <button
                      onClick={() => handleDeleteCommand(cmd.key)}
                      title="Delete"
                      className="delete-button"
                    >
                      🗑️
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {showAppList && (
          <div className="app-list">
            {isLoadingAppList ? (
              <p>Loading app list...</p>
            ) : appList.length === 0 ? (
              <p>No apps available.</p>
            ) : (
              <ul>
                {appList.map((app, index) => (
                  <li key={index}>
                    <div>
                      <strong>{app.appName || app.packageName}</strong>
                      <div>Package: {app.packageName}</div>
                      <div>
                        Type: {app.isSystemApp ? "System App" : "User App"}
                      </div>
                    </div>
                    <button
                      onClick={() => setCommand(`openapp ${app.packageName}`)}
                      title="Open App"
                      className="open-app-button"
                    >
                      🚀
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        <div className="terminal-output">
          <label>Output:</label>
          <pre>{output || "Waiting for command result..."}</pre>
        </div>
      </div>
    </div>
  );
};

export default PhoneScreenTerminal;
