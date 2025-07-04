import React from "react";
import "./SmsItem.css";

const SmsItem = ({ message, showDeviceTag }) => {
  const { sender, message: content, timestamp, deviceId } = message;

  return (
    <div className="sms-item">
      {showDeviceTag && <span className="device-tag">{deviceId}</span>}
      <strong>From:</strong> {sender}
      <br />
      <strong>Message:</strong> {content}
      <br />
      <small>{new Date(timestamp).toLocaleString()}</small>
    </div>
  );
};

export default SmsItem;
