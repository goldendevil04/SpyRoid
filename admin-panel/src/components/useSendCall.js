import { useState } from "react";
import { ref, push, serverTimestamp } from "firebase/database";

const useSendCall = (database, deviceId) => {
  const [isLoading, setIsLoading] = useState(false);
  const [statusMsg, setStatusMsg] = useState(null);
  const [isError, setIsError] = useState(false);

  const sendCall = async ({ selectedSim, recipient }) => {
    if (!deviceId) {
      setStatusMsg("No device selected.");
      setIsError(true);
      return;
    }

    // Validate recipient format (basic check for phone number)
    const trimmedRecipient = recipient.trim();
    // const phoneRegex = /^\+\d{1,4}\s?\d{6,14}$/; // E.g., +1234567890 or +91 9876543210
    if (!trimmedRecipient) {
      setStatusMsg(
        "Invalid recipient number. Use format: +[country code][number]"
      );
      setIsError(true);
      return;
    }

    setIsLoading(true);
    setStatusMsg(null);
    setIsError(false);

    try {
      const callCommandsRef = ref(database, `Device/${deviceId}/call_commands`);
      console.log("Sending call command:", {
        sim_number: selectedSim,
        recipient: trimmedRecipient,
      });
      await push(callCommandsRef, {
        sim_number: selectedSim, // Expected to be "SIM1" or "SIM2"
        recipient: trimmedRecipient,
        status: "pending",
        timestamp: serverTimestamp(), // Use Firebase server timestamp
      });
      setStatusMsg("Call command sent successfully.");
      setIsError(false);
    } catch (error) {
      console.error("Error sending call command:", error);
      setStatusMsg("Failed to send call command.");
      setIsError(true);
    } finally {
      setIsLoading(false);
    }
  };

  const resetStatus = () => {
    setStatusMsg(null);
    setIsError(false);
  };

  return { sendCall, isLoading, statusMsg, isError, resetStatus };
};

export default useSendCall;
