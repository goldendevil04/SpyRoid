// AllSmsTab.js
import React, { useState, useEffect } from "react";
import { ref, get } from "firebase/database";
import SmsItem from "./SmsItem";
import "./AllSmsTab.css";

const AllSmsTab = ({ devices, database }) => {
  const [allSmsMessages, setAllSmsMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    fetchAllSmsMessages();
  }, [database, devices]);

  const fetchAllSmsMessages = async () => {
    setIsLoading(true);

    try {
      const allMessages = [];
      const devicePromises = [];

      devices.forEach((deviceId) => {
        const smsRef = ref(database, `Device/${deviceId}/sms`);

        const promise = get(smsRef)
          .then((smsSnapshot) => {
            if (smsSnapshot.exists()) {
              smsSnapshot.forEach((sms) => {
                const smsData = sms.val();
                allMessages.push({
                  id: sms.key,
                  sender: smsData.sender || "Unknown",
                  message: smsData.message || "No message content",
                  timestamp: smsData.timestamp || 0,
                  deviceId: deviceId,
                });
              });
            }
          })
          .catch((error) => {
            console.error(`Error fetching SMS for device ${deviceId}:`, error);
          });

        devicePromises.push(promise);
      });

      await Promise.all(devicePromises);
      allMessages.sort((a, b) => b.timestamp - a.timestamp);
      setAllSmsMessages(allMessages);
    } catch (error) {
      console.error("Error fetching all SMS:", error);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="all-sms-tab-content">
      <div className="sms-list2">
        {isLoading ? (
          <div className="loading-message">
            <div className="loading"></div> Loading all messages...
          </div>
        ) : allSmsMessages.length > 0 ? (
          allSmsMessages.map((msg) => (
            <SmsItem
              key={`${msg.deviceId}-${msg.id}`}
              message={msg}
              showDeviceTag={true}
            />
          ))
        ) : (
          <div className="empty-message">
            No messages found across any devices.
          </div>
        )}
      </div>
    </div>
  );
};

export default AllSmsTab;
