import { initializeApp } from "firebase/app";
import { getDatabase } from "firebase/database";
import { getFirestore } from "firebase/firestore";
import { getAuth } from "firebase/auth";
import { getStorage } from "firebase/storage"; // Add this import

const firebaseConfig = {
  apiKey: "AIzaSyC9uNcnWXOW9A9egABoXVJjFh6Lf0Snj1c",
  authDomain: "appclient1-1afdb.firebaseapp.com",
  databaseURL: "https://appclient1-1afdb-default-rtdb.firebaseio.com",
  projectId: "appclient1-1afdb",
  storageBucket: "appclient1-1afdb.firebasestorage.app",
  messagingSenderId: "583867144674",
  appId: "1:583867144674:web:59612f11e80b0a3d7901bb",
  username: "myrat",
};

const app = initializeApp(firebaseConfig);

const database = getDatabase(app);
const auth = getAuth(app);
const storage = getStorage(app); // Export storage

export { app, database, auth, storage, firebaseConfig };
