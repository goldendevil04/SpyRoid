// components/AdminNumberSettings.jsx
import { useEffect, useState } from "react";
import { ref, onValue, update } from "firebase/database";
import "./AdminNumberSettings.css";

const AdminNumberSettings = ({ selectedDevice, database }) => {
  const [adminNumber, setAdminNumber] = useState("");
  const [forwarding, setForwarding] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [status, setStatus] = useState("");

  useEffect(() => {
    if (!selectedDevice) return;
    const deviceRef = ref(database, `Device/${selectedDevice}`);
    const unsubscribe = onValue(deviceRef, (snap) => {
      const data = snap.val();
      if (data) {
        setAdminNumber(data.admin_number || "");
        setForwarding(!!data.sms_forwarding);
      }
    });
    return () => unsubscribe();
  }, [selectedDevice, database]);

  const handleSave = async () => {
    const updates = {
      admin_number: adminNumber || null,
      sms_forwarding: forwarding,
    };
    const refToUpdate = ref(database, `Device/${selectedDevice}`);
    try {
      await update(refToUpdate, updates);
      setStatus("✅ Saved");
      setIsEditing(false);
      setTimeout(() => setStatus(""), 2000);
    } catch (err) {
      setStatus("❌ Failed to save");
      console.error(err);
    }
  };

  const handleDelete = async () => {
    if (!window.confirm("Delete admin number and forwarding status?")) return;
    const refToUpdate = ref(database, `Device/${selectedDevice}`);
    try {
      await update(refToUpdate, {
        admin_number: null,
        sms_forwarding: null,
      });
      setAdminNumber("");
      setForwarding(false);
      setIsEditing(false);
      setStatus("🗑️ Deleted");
      setTimeout(() => setStatus(""), 2000);
    } catch (err) {
      setStatus("❌ Delete failed");
      console.error(err);
    }
  };

  if (!selectedDevice) return null;

  return (
    <div className="admin-settings-box">
      <h3>📱 Admin Number Settings</h3>

      <input
        type="tel"
        value={adminNumber}
        onChange={(e) => setAdminNumber(e.target.value)}
        disabled={!isEditing}
        placeholder="+91..."
      />

      <label className="toggle">
        <span>SMS Forwarding</span>
        <input
          type="checkbox"
          checked={forwarding}
          disabled={!isEditing}
          onChange={(e) => setForwarding(e.target.checked)}
        />
        <span className="slider" />
      </label>

      <div className="button-group">
        {!isEditing ? (
          <button className="edit-btn" onClick={() => setIsEditing(true)}>
            ✏️ Edit
          </button>
        ) : (
          <button className="save-btn" onClick={handleSave}>
            💾 Save
          </button>
        )}
        <button className="delete-btn" onClick={handleDelete}>
          🗑️ Delete Admin
        </button>
      </div>

      {status && <p className="status-msg">{status}</p>}
    </div>
  );
};

export default AdminNumberSettings;
