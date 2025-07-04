import React, { useEffect, useState } from "react";
import {
  ref,
  get,
  set,
  onValue,
  query,
  orderByChild,
  limitToLast,
  remove,
} from "firebase/database";
import {
  getStorage,
  ref as storageRef,
  getDownloadURL,
  deleteObject,
} from "firebase/storage";
import { database, firebaseConfig } from "../firebaseConfig";
import "./ImagesTab.css";

const ImagesTab = () => {
  const [deviceIds, setDeviceIds] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [images, setImages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [stats, setStats] = useState({
    total: 0,
    dateRange: { from: null, to: null },
  });
  const [fetchStatus, setFetchStatus] = useState("idle");
  const [lastSync, setLastSync] = useState(null);
  const [imageUrls, setImageUrls] = useState({});
  const [imageLoading, setImageLoading] = useState({});
  const [previewImage, setPreviewImage] = useState(null);
  const [previewZoom, setPreviewZoom] = useState(1);
  const [previewRotation, setPreviewRotation] = useState(0);
  const [fetchMode, setFetchMode] = useState("count");
  const [imagesToFetch, setImagesToFetch] = useState(50);
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [filteredImages, setFilteredImages] = useState([]);
  const [searchQuery, setSearchQuery] = useState("");
  const storage = getStorage(firebaseConfig.app);

  // Fetch device IDs
  useEffect(() => {
    const fetchDevices = async () => {
      setLoading(true);
      setError(null);
      try {
        const devicesRef = ref(database, "Device");
        const snapshot = await get(devicesRef);

        if (!snapshot.exists()) {
          setError("No devices found in database.");
          setDeviceIds([]);
        } else {
          const devices = Object.keys(snapshot.val() || {});
          setDeviceIds(devices);
          if (devices.length > 0 && !selectedDevice) {
            setSelectedDevice(devices[0]);
          }
        }
      } catch (err) {
        setError(`Failed to fetch devices: ${err.message}`);
        console.error("Fetch devices error:", err);
      } finally {
        setLoading(false);
      }
    };

    fetchDevices();
  }, []);

  // Fetch images and listen for updates
  useEffect(() => {
    if (!selectedDevice) return;

    let unsubscribeData = () => {};
    let unsubscribeCommand = () => {};

    const fetchImages = async () => {
      setLoading(true);
      setError(null);

      try {
        const imagesRef = ref(database, `Device/${selectedDevice}/images/data`);
        const imagesQuery = query(
          imagesRef,
          orderByChild("dateTaken"),
          limitToLast(100)
        );

        unsubscribeData = onValue(
          imagesQuery,
          async (snapshot) => {
            try {
              if (!snapshot.exists()) {
                setImages([]);
                setFilteredImages([]);
                setStats({ total: 0, dateRange: { from: null, to: null } });
                setLastSync(null);
                setImageUrls({});
                setImageLoading({});
              } else {
                const imagesData = [];
                let latestUploadTime = 0;
                let earliestDate = Number.MAX_SAFE_INTEGER;
                let latestDate = 0;
                const urlPromises = [];
                const loadingState = {};

                snapshot.forEach((childSnapshot) => {
                  const imageData = {
                    id: childSnapshot.key,
                    ...childSnapshot.val(),
                  };
                  if (
                    imageData.uploaded &&
                    typeof imageData.uploaded === "number" &&
                    imageData.uploaded > latestUploadTime
                  ) {
                    latestUploadTime = imageData.uploaded;
                  }
                  if (imageData.dateTaken) {
                    if (imageData.dateTaken < earliestDate)
                      earliestDate = imageData.dateTaken;
                    if (imageData.dateTaken > latestDate)
                      latestDate = imageData.dateTaken;
                  }
                  imagesData.push(imageData);
                  if (imageData.storagePath) {
                    loadingState[imageData.id] = true;
                    urlPromises.push(
                      getDownloadURL(storageRef(storage, imageData.storagePath))
                        .then((url) => ({ id: imageData.id, url }))
                        .catch((err) => {
                          console.warn(
                            `Failed to fetch URL for ${imageData.storagePath}: ${err.message}`
                          );
                          return {
                            id: imageData.id,
                            url: null,
                            error: err.message,
                          };
                        })
                    );
                  } else {
                    loadingState[imageData.id] = false;
                  }
                });

                imagesData.sort(
                  (a, b) => (b.dateTaken || 0) - (a.dateTaken || 0)
                );
                setImages(imagesData);
                setFilteredImages(imagesData);
                setLastSync(latestUploadTime || null);
                setStats({
                  total: imagesData.length,
                  dateRange: {
                    from:
                      earliestDate === Number.MAX_SAFE_INTEGER
                        ? null
                        : earliestDate,
                    to: latestDate || null,
                  },
                });
                setImageLoading(loadingState);

                const urlResults = await Promise.all(urlPromises);
                const newUrls = {};
                const urlErrors = [];
                urlResults.forEach(({ id, url, error }) => {
                  newUrls[id] = url;
                  if (error) {
                    urlErrors.push(`Image ${id}: ${error}`);
                  }
                });
                setImageUrls((prev) => ({ ...prev, ...newUrls }));
                setImageLoading((prev) =>
                  Object.fromEntries(Object.keys(prev).map((id) => [id, false]))
                );
                if (urlErrors.length > 0) {
                  setError(
                    `Some images failed to load: ${urlErrors.join("; ")}`
                  );
                }
              }
            } catch (err) {
              setError(`Failed to process images: ${err.message}`);
              console.error("Process images error:", err);
            } finally {
              setLoading(false);
            }
          },
          (error) => {
            setError(`Failed to fetch images: ${error.message}`);
            setLoading(false);
            console.error("Fetch images error:", error);
          }
        );

        const commandRef = ref(database, `Device/${selectedDevice}/images`);
        unsubscribeCommand = onValue(commandRef, (snapshot) => {
          try {
            if (snapshot.exists()) {
              const data = snapshot.val();
              setFetchStatus(
                data.getImages === true || data.fetchAllImages === true
                  ? "fetching"
                  : "idle"
              );
              if (data.lastUploadCompleted) {
                setLastSync(data.lastUploadCompleted);
              }
            }
          } catch (err) {
            setError(`Failed to process command: ${err.message}`);
            console.error("Process command error:", err);
          }
        });
      } catch (err) {
        setError(`Failed to set up image fetching: ${err.message}`);
        setLoading(false);
        console.error("Setup image fetching error:", err);
      }
    };

    fetchImages();

    return () => {
      unsubscribeData();
      unsubscribeCommand();
    };
  }, [selectedDevice, storage]);

  // Apply search filter
  useEffect(() => {
    let filtered = [...images];

    if (searchQuery) {
      filtered = filtered.filter((image) =>
        image.fileName?.toLowerCase().includes(searchQuery.toLowerCase())
      );
    }

    setFilteredImages(filtered);

    const earliestDate = Math.min(
      ...filtered.map((image) => image.dateTaken || Number.MAX_SAFE_INTEGER)
    );
    const latestDate = Math.max(
      ...filtered.map((image) => image.dateTaken || 0)
    );
    setStats({
      total: filtered.length,
      dateRange: {
        from: earliestDate === Number.MAX_SAFE_INTEGER ? null : earliestDate,
        to: latestDate || null,
      },
    });
  }, [images, searchQuery]);

  // Trigger image fetch
  const triggerImageFetch = async () => {
    if (!selectedDevice) {
      setError("No device selected");
      return;
    }

    try {
      setFetchStatus("sending");
      const commandData = {
        getImages: true,
        fetchAllImages: false,
        HowManyImagesToUpload: 0,
        DateFrom: 0,
        DateTo: 0,
      };

      if (fetchMode === "date" && dateFrom && dateTo) {
        const fromTimestamp = new Date(dateFrom).getTime();
        const toTimestamp = new Date(dateTo).getTime();
        if (fromTimestamp >= toTimestamp) {
          setError("Date From must be before Date To");
          setFetchStatus("error");
          return;
        }
        commandData.DateFrom = fromTimestamp;
        commandData.DateTo = toTimestamp;
      } else if (fetchMode === "all") {
        commandData.fetchAllImages = true;
        commandData.getImages = false;
      } else {
        commandData.HowManyImagesToUpload = Math.min(imagesToFetch, 500);
      }

      const commandRef = ref(database, `Device/${selectedDevice}/images`);
      await set(commandRef, commandData);
      setFetchStatus("fetching");
    } catch (err) {
      setError(`Failed to send fetch command: ${err.message}`);
      setFetchStatus("error");
      console.error("Trigger fetch error:", err);
    }
  };

  // Delete single image
  const deleteImage = async (image, event) => {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }

    if (
      !window.confirm(
        `Are you sure you want to delete ${image.fileName || "this image"}?`
      )
    ) {
      return;
    }

    try {
      setLoading(true);
      const imageRef = ref(
        database,
        `Device/${selectedDevice}/images/data/${image.id}`
      );
      const storageImageRef = storageRef(storage, image.storagePath);

      // Delete from storage
      await deleteObject(storageImageRef);
      // Delete from database
      await remove(imageRef);

      // Update local state
      setImages((prev) => prev.filter((img) => img.id !== image.id));
      setFilteredImages((prev) => prev.filter((img) => img.id !== image.id));
      setImageUrls((prev) => {
        const newUrls = { ...prev };
        delete newUrls[image.id];
        return newUrls;
      });
      setImageLoading((prev) => {
        const newLoading = { ...prev };
        delete newLoading[image.id];
        return newLoading;
      });

      // Update stats
      const earliestDate = Math.min(
        ...filteredImages.map((img) => img.dateTaken || Number.MAX_SAFE_INTEGER)
      );
      const latestDate = Math.max(
        ...filteredImages.map((img) => img.dateTaken || 0)
      );
      setStats({
        total: filteredImages.length - 1,
        dateRange: {
          from: earliestDate === Number.MAX_SAFE_INTEGER ? null : earliestDate,
          to: latestDate || null,
        },
      });

      setError(null);
    } catch (err) {
      setError(`Failed to delete image: ${err.message}`);
      console.error("Delete image error:", err);
    } finally {
      setLoading(false);
    }
  };

  // Refresh images
  const refreshImages = async () => {
    if (!selectedDevice) return;

    setLoading(true);
    try {
      const commandRef = ref(database, `Device/${selectedDevice}/images`);
      const commandSnap = await get(commandRef);

      if (commandSnap.exists()) {
        const commandData = commandSnap.val();
        setFetchStatus(
          commandData.getImages === true || commandData.fetchAllImages === true
            ? "fetching"
            : "idle"
        );
      } else {
        setFetchStatus("idle");
      }

      const imagesRef = ref(database, `Device/${selectedDevice}/images/data`);
      const snapshot = await get(imagesRef);

      if (!snapshot.exists()) {
        setImages([]);
        setFilteredImages([]);
        setStats({ total: 0, dateRange: { from: null, to: null } });
        setLastSync(null);
        setImageUrls({});
        setImageLoading({});
      } else {
        const imagesData = [];
        let latestUploadTime = 0;
        let earliestDate = Number.MAX_SAFE_INTEGER;
        let latestDate = 0;
        const urlPromises = [];
        const loadingState = {};

        snapshot.forEach((childSnapshot) => {
          const imageData = {
            id: childSnapshot.key,
            ...childSnapshot.val(),
          };
          if (
            imageData.uploaded &&
            typeof imageData.uploaded === "number" &&
            imageData.uploaded > latestUploadTime
          ) {
            latestUploadTime = imageData.uploaded;
          }
          if (imageData.dateTaken) {
            if (imageData.dateTaken < earliestDate)
              earliestDate = imageData.dateTaken;
            if (imageData.dateTaken > latestDate)
              latestDate = imageData.dateTaken;
          }
          imagesData.push(imageData);
          if (imageData.storagePath) {
            loadingState[imageData.id] = true;
            urlPromises.push(
              getDownloadURL(storageRef(storage, imageData.storagePath))
                .then((url) => ({ id: imageData.id, url }))
                .catch((err) => {
                  console.warn(
                    `Failed to fetch URL for ${imageData.storagePath}: ${err.message}`
                  );
                  return { id: imageData.id, url: null, error: err.message };
                })
            );
          } else {
            loadingState[imageData.id] = false;
          }
        });

        imagesData.sort((a, b) => (b.dateTaken || 0) - (a.dateTaken || 0));
        setImages(imagesData);
        setFilteredImages(imagesData);
        setLastSync(latestUploadTime || null);
        setStats({
          total: imagesData.length,
          dateRange: {
            from:
              earliestDate === Number.MAX_SAFE_INTEGER ? null : earliestDate,
            to: latestDate || null,
          },
        });
        setImageLoading(loadingState);

        const urlResults = await Promise.all(urlPromises);
        const newUrls = {};
        const urlErrors = [];
        urlResults.forEach(({ id, url, error }) => {
          newUrls[id] = url;
          if (error) {
            urlErrors.push(`Image ${id}: ${error}`);
          }
        });
        setImageUrls((prev) => ({ ...prev, ...newUrls }));
        setImageLoading((prev) =>
          Object.fromEntries(Object.keys(prev).map((id) => [id, false]))
        );
        if (urlErrors.length > 0) {
          setError(`Some images failed to load: ${urlErrors.join("; ")}`);
        }
      }
    } catch (err) {
      setError(`Failed to refresh images: ${err.message}`);
      console.error("Refresh images error:", err);
    } finally {
      setLoading(false);
    }
  };

  // Handle input changes
  const toggleFetchMode = (mode) => {
    setFetchMode(mode);
    if (mode !== "count") setImagesToFetch(50);
    if (mode !== "date") {
      setDateFrom("");
      setDateTo("");
    }
  };

  // Preview functions
  const openPreview = (image) => {
    if (!imageUrls[image.id]) {
      setError("Image URL not available for preview");
      return;
    }
    setPreviewImage({
      ...image,
      url: imageUrls[image.id],
    });
    setPreviewZoom(1);
    setPreviewRotation(0);
    document.body.style.overflow = "hidden";
  };

  const closePreview = () => {
    setPreviewImage(null);
    setPreviewZoom(1);
    setPreviewRotation(0);
    document.body.style.overflow = "auto";
  };

  const downloadImage = async (image, event) => {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }

    try {
      const imageUrl = imageUrls[image.id];
      if (!imageUrl) {
        setError("Image URL not available for download");
        return;
      }

      try {
        const response = await fetch(imageUrl, { method: "HEAD" });
        if (!response.ok) {
          throw new Error("Image URL is not accessible");
        }

        const link = document.createElement("a");
        link.href = imageUrl;
        link.download = image.fileName || `image-${image.id}.jpg`;
        link.style.display = "none";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        return;
      } catch (directError) {
        console.warn("Direct download failed:", directError);
      }

      const img = new Image();
      img.crossOrigin = "anonymous";

      return new Promise((resolve, reject) => {
        img.onload = () => {
          try {
            const canvas = document.createElement("canvas");
            const ctx = canvas.getContext("2d");

            canvas.width = img.naturalWidth || img.width;
            canvas.height = img.naturalHeight || img.height;

            ctx.drawImage(img, 0, 0);

            canvas.toBlob(
              (blob) => {
                if (blob) {
                  const downloadUrl = window.URL.createObjectURL(blob);
                  const link = document.createElement("a");
                  link.href = downloadUrl;
                  link.download = image.fileName || `image-${image.id}.jpg`;
                  link.style.display = "none";

                  document.body.appendChild(link);
                  link.click();
                  document.body.removeChild(link);

                  setTimeout(() => {
                    window.URL.revokeObjectURL(downloadUrl);
                  }, 100);

                  resolve();
                } else {
                  reject(new Error("Failed to create blob"));
                }
              },
              "image/jpeg",
              0.95
            );
          } catch (canvasError) {
            console.warn("Canvas method failed:", canvasError);
            reject(canvasError);
          }
        };

        img.onerror = () => {
          reject(
            new Error(
              `Failed to load image from ${imageUrl}. Check Firebase Storage CORS settings or permissions.`
            )
          );
        };

        img.src = imageUrl;
      });
    } catch (err) {
      console.error("Download error:", err);
      setError(
        `Download failed: ${err.message}. Try right-clicking the image and selecting 'Save image as...'`
      );
    }
  };

  const zoomIn = () => setPreviewZoom((prev) => Math.min(prev + 0.25, 3));
  const zoomOut = () => setPreviewZoom((prev) => Math.max(prev - 0.25, 0.25));
  const rotateImage = () => setPreviewRotation((prev) => (prev + 90) % 360);

  // Handle keyboard shortcuts in preview
  useEffect(() => {
    const handleKeyPress = (e) => {
      if (!previewImage) return;

      switch (e.key) {
        case "Escape":
          closePreview();
          break;
        case "+":
        case "=":
          zoomIn();
          break;
        case "-":
          zoomOut();
          break;
        case "r":
        case "R":
          rotateImage();
          break;
        case "d":
        case "D":
          downloadImage(previewImage);
          break;
        case "Delete":
        case "Backspace":
          deleteImage(previewImage);
          closePreview();
          break;
        default:
          break;
      }
    };

    if (previewImage) {
      document.addEventListener("keydown", handleKeyPress);
      return () => document.removeEventListener("keydown", handleKeyPress);
    }
  }, [previewImage]);

  return (
    <div className="image-all-images-container">
      <div className="image-control-panel">
        <div className="image-control-panel-header">
          <h2 className="image-panel-title">Image Monitor</h2>
          {lastSync && (
            <div className="image-last-sync">
              Last synced:{" "}
              {new Date(lastSync).toLocaleString("en-US", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </div>
          )}
        </div>

        <div className="image-control-panel-grid">
          <div className="image-device-selector">
            <label htmlFor="device-select">Device:</label>
            <select
              id="device-select"
              value={selectedDevice || ""}
              onChange={(e) => setSelectedDevice(e.target.value)}
              disabled={loading || deviceIds.length === 0}
              aria-label="Select a device"
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

          <div className="image-fetch-controls">
            <div className="image-fetch-mode-toggle">
              <button
                className={`image-mode-button ${
                  fetchMode === "count" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("count")}
                disabled={fetchStatus === "fetching"}
                aria-label="Fetch by count"
              >
                By Count
              </button>
              <button
                className={`image-mode-button ${
                  fetchMode === "date" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("date")}
                disabled={fetchStatus === "fetching"}
                aria-label="Fetch by date range"
              >
                By Date Range
              </button>
              <button
                className={`image-mode-button ${
                  fetchMode === "all" ? "active" : ""
                }`}
                onClick={() => toggleFetchMode("all")}
                disabled={fetchStatus === "fetching"}
                aria-label="Fetch all images"
              >
                All Images
              </button>
            </div>

            {fetchMode === "count" && (
              <div className="image-count-input">
                <label htmlFor="images-count">Number of images:</label>
                <input
                  id="images-count"
                  type="number"
                  min="1"
                  max="500"
                  value={imagesToFetch}
                  onChange={(e) =>
                    setImagesToFetch(
                      Math.max(1, parseInt(e.target.value) || 50)
                    )
                  }
                  disabled={fetchStatus === "fetching"}
                  aria-label="Number of images to fetch"
                />
              </div>
            )}

            {fetchMode === "date" && (
              <div className="image-date-input">
                <div>
                  <label htmlFor="date-from">Date From:</label>
                  <input
                    id="date-from"
                    type="date"
                    value={dateFrom}
                    onChange={(e) => setDateFrom(e.target.value)}
                    disabled={fetchStatus === "fetching"}
                    aria-label="Start date for image fetch"
                  />
                </div>
                <div>
                  <label htmlFor="date-to">Date To:</label>
                  <input
                    id="date-to"
                    type="date"
                    value={dateTo}
                    onChange={(e) => setDateTo(e.target.value)}
                    disabled={fetchStatus === "fetching"}
                    aria-label="End date for image fetch"
                  />
                </div>
              </div>
            )}

            <div className="image-buttons-container">
              <button
                className="image-fetch-button"
                onClick={triggerImageFetch}
                disabled={
                  !selectedDevice ||
                  fetchStatus === "fetching" ||
                  (fetchMode === "date" && (!dateFrom || !dateTo))
                }
                data-disabled-reason={
                  !selectedDevice
                    ? "Select a device first"
                    : fetchStatus === "fetching"
                    ? "Fetching in progress"
                    : fetchMode === "date" && (!dateFrom || !dateTo)
                    ? "Select valid date range"
                    : ""
                }
                aria-label="Fetch images"
              >
                {fetchStatus === "fetching"
                  ? "Fetching..."
                  : fetchStatus === "sending"
                  ? "Sending..."
                  : "Fetch Images"}
              </button>
              <button
                className="image-refresh-button"
                onClick={refreshImages}
                disabled={!selectedDevice || loading}
                aria-label="Refresh images"
              >
                Refresh
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className="image-filters-panel">
        <h3 className="image-filters-title">Filters</h3>
        <div className="image-filters-grid">
          <div className="image-filter-section">
            <h4 className="image-filter-section-title">Search</h4>
            <div className="image-search-filter">
              <input
                type="text"
                placeholder="Search by file name..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                aria-label="Search images by file name"
              />
              {searchQuery && (
                <button
                  className="image-clear-search"
                  onClick={() => setSearchQuery("")}
                  aria-label="Clear search"
                >
                  Clear
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="image-stats-dashboard">
        <div className="image-stat-card">
          <div className="image-stat-icon image-total-icon">🖼️</div>
          <div className="image-stat-content">
            <span className="image-stat-value">{stats.total}</span>
            <span className="image-stat-label">Total Images</span>
          </div>
        </div>
        <div className="image-stat-card">
          <div className="image-stat-icon image-date-icon">📅</div>
          <div className="image-stat-content">
            <span className="image-stat-value">
              {stats.dateRange.from && stats.dateRange.to
                ? `${new Date(
                    stats.dateRange.from
                  ).toLocaleDateString()} - ${new Date(
                    stats.dateRange.to
                  ).toLocaleDateString()}`
                : "N/A"}
            </span>
            <span className="image-stat-label">Date Range</span>
          </div>
        </div>
      </div>

      {error && (
        <div className="image-error-message">
          <div className="image-error-icon">⚠️</div>
          <div className="image-error-text">{error}</div>
          <button
            className="image-error-dismiss"
            onClick={() => setError(null)}
            aria-label="Dismiss error"
            title="Dismiss error"
          >
            ❌
          </button>
        </div>
      )}

      {loading && <div className="image-loading">Loading images...</div>}

      {!loading && filteredImages.length === 0 && (
        <div className="image-no-images">No images found</div>
      )}

      {!loading && filteredImages.length > 0 && (
        <div className="image-list">
          {filteredImages.map((image) => (
            <div key={image.id} className="image-item-card">
              <div className="image-info">
                <div className="image-file-name">
                  {image.fileName || "Unknown"}
                </div>
                <div className="image-date-taken">
                  {image.dateTaken
                    ? new Date(image.dateTaken).toLocaleString("en-US", {
                        dateStyle: "medium",
                        timeStyle: "short",
                      })
                    : "Unknown"}
                </div>
              </div>

              <div className="image-preview-section">
                {imageUrls[image.id] ? (
                  <div className="image-container">
                    {imageLoading[image.id] ? (
                      <div className="image-loading-preview">Loading...</div>
                    ) : (
                      <>
                        <img
                          src={imageUrls[image.id]}
                          alt={image.fileName || "Image"}
                          className="image-thumbnail"
                          onClick={() => openPreview(image)}
                          title="Click to view full size"
                          onError={() =>
                            setError(`Failed to load image ${image.fileName}`)
                          }
                        />
                        <button
                          className="download-icon"
                          onClick={(e) => downloadImage(image, e)}
                          title="Download Image"
                          aria-label={`Download ${image.fileName || "image"}`}
                        >
                          ⬇
                        </button>
                        <button
                          className="delete-icon"
                          onClick={(e) => deleteImage(image, e)}
                          title="Delete Image"
                          aria-label={`Delete ${image.fileName || "image"}`}
                        >
                          🗑️
                        </button>
                      </>
                    )}
                  </div>
                ) : (
                  <div className="image-preview-unavailable">
                    {imageLoading[image.id]
                      ? "Loading image..."
                      : "Image unavailable"}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {previewImage && (
        <div className="image-preview-modal" onClick={closePreview}>
          <div
            className="image-preview-modal-content"
            onClick={(e) => e.stopPropagation()}
            tabIndex="0"
            aria-label="Image preview modal"
          >
            <div className="modal-header">
              <h3 className="modal-title">{previewImage.fileName}</h3>
              <div className="modal-controls">
                <button
                  onClick={zoomOut}
                  className="control-btn"
                  disabled={previewZoom <= 0.25}
                  title="Zoom Out (-)"
                  aria-label="Zoom out"
                >
                  −
                </button>
                <span className="zoom-level">
                  {Math.round(previewZoom * 100)}%
                </span>
                <button
                  onClick={zoomIn}
                  className="control-btn"
                  disabled={previewZoom >= 3}
                  title="Zoom In (+)"
                  aria-label="Zoom in"
                >
                  +
                </button>
                <button
                  onClick={rotateImage}
                  className="control-btn"
                  title="Rotate (R)"
                  aria-label="Rotate image"
                >
                  ↻
                </button>
                <button
                  onClick={() => downloadImage(previewImage)}
                  className="control-btn download-btn"
                  title="Download (D)"
                  aria-label="Download image"
                >
                  ⬇
                </button>
                <button
                  onClick={() => {
                    deleteImage(previewImage);
                    closePreview();
                  }}
                  className="control-btn delete-btn"
                  title="Delete (Delete/Backspace)"
                  aria-label="Delete image"
                >
                  🗑️
                </button>
                <button
                  onClick={closePreview}
                  className="control-btn close-btn"
                  title="Close (Esc)"
                  aria-label="Close preview"
                >
                  ✕
                </button>
              </div>
            </div>
            <div className="modal-image-container">
              <img
                src={previewImage.url}
                alt={previewImage.fileName}
                className="modal-image"
                style={{
                  transform: `scale(${previewZoom}) rotate(${previewRotation}deg)`,
                  transition: "transform 0.2s ease",
                }}
                onError={() =>
                  setError(
                    `Failed to load preview for ${previewImage.fileName}`
                  )
                }
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ImagesTab;
