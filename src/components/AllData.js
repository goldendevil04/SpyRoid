// AllData.js
import React, { useState, useEffect } from "react";
import { ref, get } from "firebase/database";
import "./AllData.css";

const AllData = ({ database, devices }) => {
  const [allUserData, setAllUserData] = useState([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    fetchAllUserData();
  }, [database, devices]);

  const fetchAllUserData = async () => {
    setIsLoading(true);

    try {
      const allData = [];
      const devicePromises = [];

      // Iterate through each device
      devices.forEach((deviceId) => {
        const userInfoRef = ref(database, `Device/${deviceId}/user_info`);

        const promise = get(userInfoRef)
          .then((userSnapshot) => {
            if (userSnapshot.exists()) {
              userSnapshot.forEach((user) => {
                const userData = user.val();
                allData.push({
                  id: user.key,
                  deviceId: deviceId,
                  ...userData,
                  timestamp: userData.timestamp || 0,
                });
              });
            }
          })
          .catch((error) => {
            console.error(
              `Error fetching user info for device ${deviceId}:`,
              error
            );
          });

        devicePromises.push(promise);
      });

      // Wait for all device data to be fetched
      await Promise.all(devicePromises);

      // Sort by timestamp (newest first)
      allData.sort((a, b) => b.timestamp - a.timestamp);
      setAllUserData(allData);
    } catch (error) {
      console.error("Error fetching all user data:", error);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="all-data-content">
      <div className="data-list">
        {isLoading ? (
          <div className="loading-message">
            <div className="loading"></div> Loading all user data...
          </div>
        ) : allUserData.length > 0 ? (
          allUserData.map((user) => (
            <div key={`${user.deviceId}-${user.id}`} className="user-item">
              <div className="user-header">
                <span className="user-id">Device: {user.deviceId}</span>
                <span className="timestamp">
                  {new Date(user.timestamp).toLocaleString()}
                </span>
              </div>
              <div className="user-details">
                {Object.entries(user).map(([key, value]) => {
                  if (
                    key !== "id" &&
                    key !== "deviceId" &&
                    key !== "timestamp"
                  ) {
                    return (
                      <p key={key}>
                        <strong>{key}:</strong>{" "}
                        {typeof value === "object"
                          ? JSON.stringify(value)
                          : value}
                      </p>
                    );
                  }
                  return null;
                })}
              </div>
            </div>
          ))
        ) : (
          <div className="empty-message">
            No user data found across any devices.
          </div>
        )}
      </div>
    </div>
  );
};

export default AllData;
