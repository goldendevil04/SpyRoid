import { useCallback, useState } from "react";
import { ref, push, set, onValue, off } from "firebase/database";

export default function useSendSms(database, deviceId) {
  const [isLoading, setIsLoading] = useState(false);
  const [statusMsg, setStatusMsg] = useState("");
  const [isError, setIsError] = useState(false);
  const [currentCommandKey, setCurrentCommandKey] = useState(null); // NEW

  const resetStatus = () => {
    setStatusMsg("");
    setCurrentCommandKey(null); // reset current command
  };

  const sendSms = useCallback(
    ({ selectedSim, recipient, message }) => {
      if (!deviceId) {
        setIsError(true);
        setStatusMsg("No device selected");
        return;
      }

      const recipients = recipient
        .split(",")
        .map((r) => r.trim())
        .filter(Boolean);

      if (!recipients.length) {
        setIsError(true);
        setStatusMsg("No valid recipient numbers");
        return;
      }

      setIsLoading(true);
      setIsError(false);

      // Create a single SMS command with multiple recipients
      const smsRef = push(ref(database, `Device/${deviceId}/send_sms_commands`));
      set(smsRef, {
        sim_number: selectedSim,
        recipients,
        message,
        timestamp: Date.now(),
        status: "pending",
      });

      setCurrentCommandKey(smsRef.key); // store command key

      // Listen for deletion (i.e., success acknowledged by device)
      const handler = (snap) => {
        if (!snap.exists()) {
          clearTimeout(timeoutId);
          off(smsRef, "value", handler);
          setIsLoading(false);
          setStatusMsg("SMS sent successfully ✔️");
          setIsError(false);
          setCurrentCommandKey(null);
        }
      };

      onValue(smsRef, handler);

      const timeoutId = setTimeout(() => {
        off(smsRef, "value", handler);
        setIsLoading(false);
        setStatusMsg("SMS not sent");
        setIsError(true);
        setCurrentCommandKey(null);
      }, 5000);
    },
    [database, deviceId]
  );

  return { sendSms, isLoading, statusMsg, isError, resetStatus, currentCommandKey };
}
