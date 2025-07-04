import { useState } from "react";
import { ref, push, serverTimestamp, update } from "firebase/database";

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
        sim_number: selectedSim,
        recipient: trimmedRecipient,
        status: "pending",
        timestamp: serverTimestamp(),
        type: "ui_initiated",
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

  const endCall = async (commandId) => {
    if (!deviceId) {
      setStatusMsg("No device selected.");
      setIsError(true);
      return;
    }

    if (!commandId) {
      setStatusMsg("No active call to end.");
      setIsError(true);
      return;
    }

    setIsLoading(true);
    setStatusMsg(null);
    setIsError(false);

    try {
      const commandRef = ref(
        database,
        `Device/${deviceId}/call_commands/${commandId}`
      );
      await update(commandRef, {
        status: "cancel",
        timestamp: serverTimestamp(),
      });
      setStatusMsg("Call end command sent successfully.");
      setIsError(false);
    } catch (error) {
      console.error("Error sending end call command:", error);
      setStatusMsg("Failed to send end call command.");
      setIsError(true);
    } finally {
      setIsLoading(false);
    }
  };

  const resetStatus = () => {
    setStatusMsg(null);
    setIsError(false);
  };

  return { sendCall, endCall, isLoading, statusMsg, isError, resetStatus };
};

export default useSendCall;
