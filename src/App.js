import React, { useState, useEffect } from "react";
import { ref, onValue } from "firebase/database";
import {
  BrowserRouter as Router,
  Routes,
  Route,
  Navigate,
} from "react-router-dom";

import HomeScreen from "./components/HomeScreen";
import PhoneScreen from "./components/PhoneScreen";
import PhoneScreenTerminal from "./components/PhoneScreenTerminal";
import { database } from "./firebaseConfig";

const App = () => {
  const [devices, setDevices] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const deviceRef = ref(database, "Device");
    const unsubscribe = onValue(
      deviceRef,
      (snapshot) => {
        if (!snapshot.exists()) {
          setDevices([]);
          setError("No devices found in database.");
        } else {
          const deviceList = Object.keys(snapshot.val() || {});
          setDevices(deviceList);
          setError(null);
        }
        setIsLoading(false);
      },
      (err) => {
        console.error("Error fetching devices:", err);
        setError("Failed to fetch devices: " + err.message);
        setIsLoading(false);
      }
    );

    return () => unsubscribe();
  }, []);

  if (isLoading) {
    return (
      <div className="loading-container">
        <div className="loading"></div>
        <p>Loading...</p>
      </div>
    );
  }

  if (error && devices.length === 0) {
    return (
      <div className="error-container">
        <p>{error}</p>
      </div>
    );
  }

  return (
    <div className="app-container">
      <Router>
        <Routes>
          <Route
            path="/"
            element={
              <HomeScreen
                devices={devices}
                selectedDevice={selectedDevice}
                setSelectedDevice={setSelectedDevice}
                database={database}
              />
            }
          />
          <Route
            path="/send-sms"
            element={
              <PhoneScreen
                devices={devices}
                selectedDevice={selectedDevice}
                setSelectedDevice={setSelectedDevice}
                database={database}
              />
            }
          />
          <Route
            path="/terminal"
            element={
              <PhoneScreenTerminal
                devices={devices}
                selectedDevice={selectedDevice}
                setSelectedDevice={setSelectedDevice}
                database={database}
              />
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Router>
    </div>
  );
};

export default App;
