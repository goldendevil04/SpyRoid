import React, { useEffect, useState, useMemo } from "react";
import {
  ref,
  get,
  set,
  onValue,
  query,
  orderByChild,
  limitToLast,
} from "firebase/database";
import { database as db } from "../firebaseConfig";
import "./AllCallsTab.css";

// Utility functions
function formatDuration(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}m ${secs}s`;
}

function formatDate(timestamp) {
  if (typeof timestamp === "number") {
    return new Date(timestamp).toLocaleString();
  }
  if (timestamp && timestamp.toDate) {
    return timestamp.toDate().toLocaleString();
  }
  if (typeof timestamp === "string" && !isNaN(Number(timestamp))) {
    return new Date(Number(timestamp)).toLocaleString();
  }
  return "Invalid date";
}

function formatPhoneNumber(number) {
  if (!number || typeof number !== "string") return number || "Unknown";
  const cleaned = number.replace(/\D/g, "");
  if (cleaned.length === 10) {
    return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(
      6
    )}`;
  }
  return number;
}

const AllCallsTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState("");
  const [calls, setCalls] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeCallId, setActiveCallId] = useState(null);
  const [stats, setStats] = useState({ total: 0, unique: 0 });
  const [fetchStatus, setFetchStatus] = useState("idle");
  const [lastSync, setLastSync] = useState(null);
  const [fetchMode, setFetchMode] = useState("count");
  const [callsToFetch, setCallsToFetch] = useState(50);
  const [dateFrom, setDateFrom] = useState("");
  const [dateFromTimestamp, setDateFromTimestamp] = useState(0);
  const [filteredCalls, setFilteredCalls] = useState([]);
  const [filterType, setFilterType] = useState("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [dateFilter, setDateFilter] = useState({
    start: "",
    end: "",
    enabled: false,
  });

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
          setSelectedDevice("");
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
  }, [selectedDevice]); // Added selectedDevice to dependency array

  // Fetch calls for selected device
  useEffect(() => {
    if (!selectedDevice) {
      setCalls([]);
      setFilteredCalls([]);
      setLoading(false);
      return;
    }

    let unsubscribe = () => {};
    let commandUnsubscribe = () => {};

    const fetchCalls = async () => {
      setLoading(true);
      setError(null);
      try {
        const callsRef = ref(db, `Device/${selectedDevice}/calls/data`);
        const callsQuery = query(
          callsRef,
          orderByChild("date"),
          limitToLast(100)
        );
        unsubscribe = onValue(
          callsQuery,
          (snapshot) => {
            if (!snapshot.exists()) {
              setCalls([]);
              setFilteredCalls([]);
              setStats({ total: 0, unique: 0 });
              setLastSync(null);
            } else {
              const callsData = [];
              let latestUploadTime = 0;
              snapshot.forEach((childSnapshot) => {
                const callData = {
                  id: childSnapshot.key,
                  ...childSnapshot.val(),
                };
                if (callData.uploaded && callData.uploaded > latestUploadTime) {
                  latestUploadTime = callData.uploaded;
                }
                callsData.push(callData);
              });
              callsData.sort((a, b) => b.date - a.date);
              setCalls(callsData);
              setFilteredCalls(callsData);
              if (latestUploadTime > 0) {
                setLastSync(latestUploadTime);
              }
            }
            setLoading(false);
          },
          (error) => {
            setError(`Failed to fetch calls: ${error.message}`);
            setLoading(false);
          }
        );

        const commandRef = ref(db, `Device/${selectedDevice}/calls`);
        commandUnsubscribe = onValue(commandRef, (snapshot) => {
          if (snapshot.exists()) {
            const data = snapshot.val();
            setFetchStatus(data.getCallLog === true ? "fetching" : "idle");
            if (data.lastUploadCompleted) {
              setLastSync(data.lastUploadCompleted);
            }
          }
        });
      } catch (err) {
        setError(`Failed to fetch calls: ${err.message}`);
        setLoading(false);
      }
    };

    fetchCalls();

    return () => {
      unsubscribe();
      commandUnsubscribe();
    };
  }, [selectedDevice]);

  // Filter calls
  useEffect(() => {
    let filtered = [...calls];
    if (filterType !== "all") {
      filtered = filtered.filter((call) => call.type === filterType);
    }
    if (searchQuery) {
      filtered = filtered.filter((call) =>
        call.number?.toLowerCase().includes(searchQuery.toLowerCase())
      );
    }
    if (dateFilter.enabled && dateFilter.start && dateFilter.end) {
      const startDate = new Date(dateFilter.start).getTime();
      const endDate = new Date(dateFilter.end).getTime() + 86400000;
      filtered = filtered.filter((call) => {
        const callDate =
          typeof call.date === "number"
            ? call.date
            : Number(call.date) || call.date?.toDate?.() || 0;
        return callDate >= startDate && callDate <= endDate;
      });
    }
    setFilteredCalls(filtered);
  }, [calls, filterType, searchQuery, dateFilter]);

  // Memoize callsByNumber to optimize performance
  const callsByNumber = useMemo(() => {
    return filteredCalls.reduce((acc, call) => {
      if (!call.number) return acc;
      acc[call.number] = acc[call.number] || [];
      acc[call.number].push(call);
      return acc;
    }, {});
  }, [filteredCalls]);

  // Update stats
  useEffect(() => {
    setStats({
      total: filteredCalls.length,
      unique: Object.keys(callsByNumber).length,
    });
  }, [filteredCalls, callsByNumber]); // Added callsByNumber to dependency array

  // Trigger call log fetch
  const triggerCallLogFetch = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }
    try {
      setFetchStatus("sending");
      const commandData = {
        getCallLog: true,
        HowManyNumToUpload: fetchMode === "date" ? 500 : callsToFetch,
        DateFrom: fetchMode === "date" ? dateFromTimestamp : 0,
      };
      const commandRef = ref(db, `Device/${selectedDevice}/calls`);
      await set(commandRef, commandData);
      setFetchStatus("fetching");
    } catch (err) {
      setError(`Failed to send fetch command: ${err.message}`);
      setFetchStatus("error");
    }
  };

  // Refresh call logs
  const refreshCallLogs = async () => {
    if (!selectedDevice) return;
    setLoading(true);
    try {
      const commandRef = ref(db, `Device/${selectedDevice}/calls`);
      const commandSnap = await get(commandRef);
      if (commandSnap.exists() && commandSnap.val().getCallLog === true) {
        setFetchStatus("fetching");
      } else {
        setFetchStatus("idle");
      }
      const callsRef = ref(db, `Device/${selectedDevice}/calls/data`);
      const snapshot = await get(callsRef);
      if (!snapshot.exists()) {
        setCalls([]);
        setFilteredCalls([]);
        setStats({ total: 0, unique: 0 });
        setLastSync(null);
      } else {
        const callsData = [];
        let latestUploadTime = 0;
        snapshot.forEach((childSnapshot) => {
          const callData = {
            id: childSnapshot.key,
            ...childSnapshot.val(),
          };
          if (callData.uploaded && callData.uploaded > latestUploadTime) {
            latestUploadTime = callData.uploaded;
          }
          callsData.push(callData);
        });
        callsData.sort((a, b) => b.date - a.date);
        setCalls(callsData);
        if (latestUploadTime > 0) {
          setLastSync(latestUploadTime);
        }
      }
    } catch (err) {
      setError(`Failed to refresh call logs: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  // Handle date input changes
  const handleDateChange = (e) => {
    setDateFrom(e.target.value);
    const date = new Date(e.target.value);
    setDateFromTimestamp(!isNaN(date.getTime()) ? date.getTime() : 0);
  };

  // Handle date filter changes
  const handleDateFilterChange = (e) => {
    const { name, value } = e.target;
    setDateFilter((prev) => ({ ...prev, [name]: value }));
  };

  // Toggle date filter
  const toggleDateFilter = () => {
    setDateFilter((prev) => ({ ...prev, enabled: !prev.enabled }));
  };

  // Toggle fetch mode
  const toggleFetchMode = (mode) => {
    setFetchMode(mode);
  };

  // Sort numbers
  const sortedNumbers = useMemo(() => {
    return Object.keys(callsByNumber).sort((a, b) => {
      const aDate = Math.max(
        ...callsByNumber[a].map((call) =>
          typeof call.date === "number"
            ? call.date
            : Number(call.date) || call.date?.toDate?.().getTime() || 0
        )
      );
      const bDate = Math.max(
        ...callsByNumber[b].map((call) =>
          typeof call.date === "number"
            ? call.date
            : Number(call.date) || call.date?.toDate?.().getTime() || 0
        )
      );
      return bDate - aDate;
    });
  }, [callsByNumber]);

  const toggleCallDetails = (callId) => {
    setActiveCallId(activeCallId === callId ? null : callId);
  };

  const getCallTypeIcon = (type) => {
    switch (type) {
      case "Outgoing":
        return "→";
      case "Incoming":
        return "←";
      case "Missed":
        return "✗";
      case "Rejected":
        return "✘";
      case "Voicemail":
        return "✉";
      default:
        return "?";
    }
  };

  const getCallTypeClass = (type) => {
    switch (type) {
      case "Outgoing":
        return "outgoing";
      case "Incoming":
        return "incoming";
      case "Missed":
        return "missed";
      case "Rejected":
        return "rejected";
      case "Voicemail":
        return "voicemail";
      default:
        return "";
    }
  };

  return (
    <div className="all-calls-container">
      <div className="control-panel">
        <div className="control-panel-header">
          <h2 className="panel-title">Call Monitor</h2>
          {lastSync && (
            <div className="last-sync">
              Last synced: {new Date(lastSync).toLocaleString()}
            </div>
          )}
        </div>
        <div className="control-panel-grid">
          <div className="device-selector">
            <label htmlFor="device-select">Device:</label>
            <select
              id="device-select"
              value={selectedDevice}
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
          <div className="fetch-controls">
            <div className="fetch-mode-toggle">
              <button
                className={`mode-button ${
                  fetchMode === "count" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("count")}
                disabled={fetchStatus === "fetching"}
              >
                Fetch by Count
              </button>
              <button
                className={`mode-button ${
                  fetchMode === "date" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("date")}
                disabled={fetchStatus === "fetching"}
              >
                Fetch by Date
              </button>
            </div>
            {fetchMode === "count" ? (
              <div className="count-input">
                <label htmlFor="calls-count">Number of calls:</label>
                <input
                  id="calls-count"
                  type="number"
                  min="1"
                  max="500"
                  value={callsToFetch}
                  onChange={(e) =>
                    setCallsToFetch(parseInt(e.target.value) || 50)
                  }
                  disabled={fetchStatus === "fetching"}
                />
              </div>
            ) : (
              <div className="date-input">
                <label htmlFor="date-from">From date:</label>
                <input
                  id="date-from"
                  type="datetime-local"
                  value={dateFrom}
                  onChange={handleDateChange}
                  disabled={fetchStatus === "fetching"}
                />
              </div>
            )}
            <div className="buttons-container">
              <button
                className="fetch-button"
                onClick={triggerCallLogFetch}
                disabled={
                  !selectedDevice ||
                  fetchStatus === "fetching" ||
                  (fetchMode === "date" && dateFromTimestamp === 0)
                }
              >
                {fetchStatus === "fetching"
                  ? "Fetching..."
                  : fetchStatus === "sending"
                  ? "Sending command..."
                  : "Fetch Calls"}
              </button>
              <button
                className="refresh-button"
                onClick={refreshCallLogs}
                disabled={!selectedDevice || loading}
              >
                Refresh
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="filters-panel">
        <h3 className="filters-title">Filters</h3>
        <div className="filters-grid">
          <div className="filter-section">
            <h4 className="filter-section-title">Call Type</h4>
            <div className="filter-type">
              {[
                "all",
                "Incoming",
                "Outgoing",
                "Missed",
                "Rejected",
                "Voicemail",
              ].map((type) => (
                <button
                  key={type}
                  className={`filter-button ${
                    filterType === type ? "active" : ""
                  }`}
                  onClick={() => setFilterType(type)}
                >
                  {type}
                </button>
              ))}
            </div>
          </div>
          <div className="filter-section">
            <h4 className="filter-section-title">Phone Number Search</h4>
            <div className="search-filter">
              <input
                type="text"
                placeholder="Search by phone number..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
              {searchQuery && (
                <button
                  className="clear-search"
                  onClick={() => setSearchQuery("")}
                >
                  Clear
                </button>
              )}
            </div>
          </div>
          <div className="filter-section date-filter-section">
            <div className="date-filter-header">
              <h4 className="filter-section-title">Date Range Filter</h4>
              <label className="toggle-switch">
                <input
                  type="checkbox"
                  checked={dateFilter.enabled}
                  onChange={toggleDateFilter}
                />
                <span className="toggle-slider"></span>
                <span className="toggle-label">
                  {dateFilter.enabled ? "Enabled" : "Disabled"}
                </span>
              </label>
            </div>
            <div
              className={`date-range-inputs ${
                dateFilter.enabled ? "enabled" : "disabled"
              }`}
            >
              <div className="date-range-input">
                <label htmlFor="start-date">Start Date:</label>
                <input
                  id="start-date"
                  type="date"
                  name="start"
                  value={dateFilter.start}
                  onChange={handleDateFilterChange}
                  disabled={!dateFilter.enabled}
                />
              </div>
              <div className="date-range-input">
                <label htmlFor="end-date">End Date:</label>
                <input
                  id="end-date"
                  type="date"
                  name="end"
                  value={dateFilter.end}
                  onChange={handleDateFilterChange}
                  disabled={!dateFilter.enabled}
                />
              </div>
            </div>
          </div>
        </div>
      </div>
      <div className="call-stats-dashboard">
        <div className="stat-card">
          <div className="stat-icon total-icon">📊</div>
          <div className="stat-content">
            <span className="stat-value">{stats.total}</span>
            <span className="stat-label">Total Calls</span>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon unique-icon">👤</div>
          <div className="stat-content">
            <span className="stat-value">{stats.unique}</span>
            <span className="stat-label">Unique Numbers</span>
          </div>
        </div>
        {dateFilter.enabled && (
          <div className="stat-card date-range-card">
            <div className="stat-icon date-icon">📅</div>
            <div className="stat-content">
              <span className="date-range-text">
                {dateFilter.start
                  ? new Date(dateFilter.start).toLocaleDateString()
                  : "..."}{" "}
                -{" "}
                {dateFilter.end
                  ? new Date(dateFilter.end).toLocaleDateString()
                  : "..."}
              </span>
              <span className="stat-label">Date Range</span>
            </div>
          </div>
        )}
      </div>
      {error && (
        <div className="error-message">
          <div className="error-icon">⚠️</div>
          <div className="error-text">{error}</div>
        </div>
      )}
      {loading && <div className="call-loading">Loading call logs...</div>}
      {!loading && filteredCalls.length === 0 && (
        <div className="no-calls">No call logs found</div>
      )}
      {!loading && filteredCalls.length > 0 && (
        <div className="call-list">
          <div className="call-list-by-number">
            {sortedNumbers.map((number) => (
              <div key={number} className="number-group">
                <div className="number-header">
                  <h3 className="phone-number">{formatPhoneNumber(number)}</h3>
                  <span className="call-count">
                    {callsByNumber[number].length} calls
                  </span>
                </div>
                <div className="calls-for-number">
                  {callsByNumber[number].map((call) => (
                    <div
                      key={call.id}
                      className={`call-item ${getCallTypeClass(call.type)}`}
                      onClick={() => toggleCallDetails(call.id)}
                    >
                      <div className="call-summary">
                        <span
                          className={`call-type ${getCallTypeClass(call.type)}`}
                        >
                          {getCallTypeIcon(call.type)} {call.type}
                        </span>
                        <span className="call-date">
                          {formatDate(call.date)}
                        </span>
                        <span className="call-duration">
                          {formatDuration(call.duration)}
                        </span>
                        <span className="expand-indicator">
                          {activeCallId === call.id ? "▼" : "▶"}
                        </span>
                      </div>
                      {activeCallId === call.id && (
                        <div className="call-details">
                          <div className="detail-row">
                            <span className="detail-label">Number:</span>
                            <span className="detail-value">
                              {formatPhoneNumber(call.number)}
                            </span>
                          </div>
                          <div className="detail-row">
                            <span className="detail-label">Type:</span>
                            <span className="detail-value">{call.type}</span>
                          </div>
                          <div className="detail-row">
                            <span className="detail-label">Date:</span>
                            <span className="detail-value">
                              {formatDate(call.date)}
                            </span>
                          </div>
                          <div className="detail-row">
                            <span className="detail-label">Duration:</span>
                            <span className="detail-value">
                              {formatDuration(call.duration)}
                            </span>
                          </div>
                          <div className="detail-row">
                            <span className="detail-label">Upload Time:</span>
                            <span className="detail-value">
                              {call.uploaded
                                ? formatDate(call.uploaded)
                                : "Unknown"}
                            </span>
                          </div>
                          <div className="detail-row">
                            <span className="detail-label">Call ID:</span>
                            <span className="detail-value">{call.id}</span>
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default AllCallsTab;
