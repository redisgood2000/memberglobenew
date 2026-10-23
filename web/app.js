import { initializeApp } from "https://www.gstatic.com/firebasejs/11.6.0/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect,
  getRedirectResult, onAuthStateChanged, signOut
} from "https://www.gstatic.com/firebasejs/11.6.0/firebase-auth.js";
import {
  getFirestore, doc, getDoc, setDoc, updateDoc, collection, query, where,
  orderBy, onSnapshot, serverTimestamp, deleteDoc
} from "https://www.gstatic.com/firebasejs/11.6.0/firebase-firestore.js";
import { firebaseConfig } from "./firebase-config.js";

const $ = (id) => document.getElementById(id);
const configured = Boolean(firebaseConfig.apiKey && firebaseConfig.authDomain &&
  firebaseConfig.projectId && firebaseConfig.appId &&
  !Object.values(firebaseConfig).some(v => typeof v === "string" && v.startsWith("YOUR_")));

let auth, db, currentUser = null, currentMember = null, map = null;
let profileUnsub = null, memberUnsub = null, pendingUnsub = null, locationUnsub = null;
let gpsWatch = null, mapMarkers = new Map(), mapReady = false;
const sections = ["signed-out", "pending-panel", "rejected-panel", "app-panel"];

function showSection(id) {
  sections.forEach(name => $(name).classList.toggle("hidden", name !== id));
  $("user-chip").classList.toggle("hidden", !currentUser);
}
function message(id, text, error = false) {
  const el = $(id); if (!el) return;
  el.textContent = text; el.classList.toggle("error", error);
}
function stopListeners() {
  [profileUnsub, memberUnsub, pendingUnsub, locationUnsub].forEach(fn => { if (fn) fn(); });
  profileUnsub = memberUnsub = pendingUnsub = locationUnsub = null;
}
function stopGpsWatch() {
  if (gpsWatch !== null && navigator.geolocation) navigator.geolocation.clearWatch(gpsWatch);
  gpsWatch = null;
}
function showSetupError() {
  $("setup-warning").classList.remove("hidden");
  $("google-sign-in").disabled = true;
  message("auth-message", "Add your Firebase project configuration to enable sign-in.", true);
}
function initMap() {
  if (mapReady || !window.L) return;
  map = L.map("map").setView([12.8797, 121.7740], 5);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);
  mapReady = true;
}
function formatDate(ts) {
  if (!ts) return "Just now";
  const date = ts.toDate ? ts.toDate() : new Date(ts);
  return date.toLocaleString();
}
function safeText(value) { return typeof value === "string" ? value : ""; }

async function ensureMemberProfile(user) {
  const ref = doc(db, "members", user.uid);
  const snap = await getDoc(ref);
  if (!snap.exists()) {
    await setDoc(ref, {
      uid: user.uid,
      displayName: user.displayName || user.email?.split("@")[0] || "Member",
      email: user.email || "",
      photoURL: user.photoURL || "",
      phone: "",
      bio: "",
      status: "pending",
      locationSharing: false,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    return;
  }
  const existing = snap.data();
  const changes = {};
  if (!existing.email && user.email) changes.email = user.email;
  if (!existing.photoURL && user.photoURL) changes.photoURL = user.photoURL;
  if (!existing.displayName && user.displayName) changes.displayName = user.displayName;
  if (Object.keys(changes).length) {
    changes.updatedAt = serverTimestamp();
    await updateDoc(ref, changes);
  }
}

function watchProfile(uid) {
  profileUnsub = onSnapshot(doc(db, "members", uid), async (snap) => {
    if (!snap.exists()) {
      message("auth-message", "Your profile has not been created yet. Please sign out and sign in again.", true);
      return;
    }
    currentMember = snap.data();
    $("user-chip").textContent = currentUser?.displayName || currentUser?.email || "Signed in";
    if (currentMember.status === "pending") {
      stopListenersExceptProfile();
      showSection("pending-panel");
      $("pending-email").textContent = currentUser?.email || "";
      return;
    }
    if (currentMember.status !== "approved") {
      stopListenersExceptProfile();
      showSection("rejected-panel");
      return;
    }
    showSection("app-panel");
    initMap();
    $("welcome-name").textContent = "Welcome, " + (currentMember.displayName || currentUser?.displayName || "Member");
    $("profile-name").value = currentMember.displayName || "";
    $("profile-phone").value = currentMember.phone || "";
    $("profile-bio").value = currentMember.bio || "";
    $("location-toggle").checked = currentMember.locationSharing === true;
    if (currentMember.locationSharing) startGpsWatch();
    else stopGpsWatch();
    watchApprovedMembers();
    watchAdmin();
  }, (error) => {
    message("auth-message", "Could not load your profile: " + error.message, true);
  });
}
function stopListenersExceptProfile() {
  [memberUnsub, pendingUnsub, locationUnsub].forEach(fn => { if (fn) fn(); });
  memberUnsub = pendingUnsub = locationUnsub = null;
  stopGpsWatch();
}
function watchApprovedMembers() {
  if (memberUnsub) return;
  const q = query(collection(db, "members"), where("status", "==", "approved"), orderBy("displayName"));
  memberUnsub = onSnapshot(q, (snapshot) => {
    const approvedIds = new Set(snapshot.docs.map(d => d.id));
    for (const [uid, marker] of mapMarkers) {
      if (!approvedIds.has(uid)) { map.removeLayer(marker); mapMarkers.delete(uid); }
    }
    message("map-message", snapshot.empty ? "No approved members yet." : "");
    $("member-count").textContent = "Live member locations";
  }, (error) => message("map-message", "Could not load members: " + error.message, true));
  locationUnsub = onSnapshot(query(collection(db, "locations"), where("sharing", "==", true)), (snapshot) => {
    const liveIds = new Set();
    snapshot.forEach((locationDoc) => {
      const data = locationDoc.data(), uid = locationDoc.id;
      liveIds.add(uid);
      if (typeof data.lat !== "number" || typeof data.lng !== "number") return;
      const memberRef = doc(db, "members", uid);
      getDoc(memberRef).then((memberSnap) => {
        if (!memberSnap.exists() || memberSnap.data().status !== "approved" ||
            memberSnap.data().locationSharing !== true || !mapReady) return;
        const member = memberSnap.data();
        const popup = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = safeText(member.displayName) || "Member";
        popup.appendChild(title);
        if (member.phone) {
          const phone = document.createElement("p"); phone.textContent = "Phone: " + safeText(member.phone); popup.appendChild(phone);
        }
        const updated = document.createElement("small");
        updated.textContent = "Updated: " + formatDate(data.updatedAt); popup.appendChild(updated);
        const position = [data.lat, data.lng];
        let marker = mapMarkers.get(uid);
        if (!marker) {
          marker = L.marker(position).addTo(map);
          mapMarkers.set(uid, marker);
        } else marker.setLatLng(position);
        marker.bindPopup(popup);
      }).catch(() => {});
    });
    for (const [uid, marker] of mapMarkers) {
      if (!liveIds.has(uid)) { map.removeLayer(marker); mapMarkers.delete(uid); }
    }
    $("member-count").textContent = snapshot.size + " sharing";
  }, (error) => message("map-message", "Could not load shared locations: " + error.message, true));
}
async function watchAdmin() {
  const adminSnap = await getDoc(doc(db, "admins", currentUser.uid));
  if (!adminSnap.exists()) { $("admin-panel").classList.add("hidden"); return; }
  $("admin-panel").classList.remove("hidden");
  if (pendingUnsub) return;
  const q = query(collection(db, "members"), where("status", "==", "pending"), orderBy("createdAt", "asc"));
  pendingUnsub = onSnapshot(q, (snapshot) => {
    $("pending-count").textContent = snapshot.size + " pending";
    const list = $("pending-list"); list.replaceChildren();
    if (snapshot.empty) { list.textContent = "No pending membership requests."; return; }
    snapshot.forEach(memberDoc => {
      const data = memberDoc.data(), card = document.createElement("div");
      card.className = "request-card";
      const details = document.createElement("div");
      const name = document.createElement("strong"); name.textContent = safeText(data.displayName) || "New member";
      const email = document.createElement("small"); email.textContent = safeText(data.email);
      details.append(name, email);
      const actions = document.createElement("div"); actions.className = "request-actions";
      const approve = document.createElement("button"); approve.className = "approve"; approve.textContent = "Approve";
      approve.addEventListener("click", () => reviewMember(memberDoc.id, "approved", approve));
      const reject = document.createElement("button"); reject.className = "reject"; reject.textContent = "Reject";
      reject.addEventListener("click", () => reviewMember(memberDoc.id, "rejected", reject));
      actions.append(approve, reject); card.append(details, actions); list.append(card);
    });
  }, (error) => { $("pending-list").textContent = "Unable to load requests: " + error.message; });
}
async function reviewMember(uid, status, button) {
  button.disabled = true;
  try {
    await updateDoc(doc(db, "members", uid), {
      status, reviewedAt: serverTimestamp(), reviewedBy: currentUser.uid, updatedAt: serverTimestamp()
    });
  } catch (error) {
    alert("Could not update membership: " + error.message);
  } finally { button.disabled = false; }
}

async function setSharing(enabled) {
  const toggle = $("location-toggle");
  toggle.disabled = true;
  try {
    await updateDoc(doc(db, "members", currentUser.uid), {
      locationSharing: enabled, updatedAt: serverTimestamp()
    });
    if (!enabled) {
      stopGpsWatch();
      await deleteDoc(doc(db, "locations", currentUser.uid));
      message("location-message", "Location sharing is off. Your shared location was removed.");
    } else {
      if (!navigator.geolocation) throw new Error("This browser does not support GPS location.");
      startGpsWatch();
      message("location-message", "Requesting location permission…");
    }
  } catch (error) {
    toggle.checked = !enabled;
    message("location-message", "Could not change location sharing: " + error.message, true);
  } finally { toggle.disabled = false; }
}
function startGpsWatch() {
  if (gpsWatch !== null || !navigator.geolocation || currentMember?.status !== "approved" || !currentMember.locationSharing) return;
  gpsWatch = navigator.geolocation.watchPosition(async (position) => {
    try {
      await setDoc(doc(db, "locations", currentUser.uid), {
        uid: currentUser.uid,
        lat: position.coords.latitude,
        lng: position.coords.longitude,
        accuracy: position.coords.accuracy,
        sharing: true,
        updatedAt: serverTimestamp()
      });
      message("location-message", "Sharing your current location. Updated " + new Date().toLocaleTimeString() + ".");
    } catch (error) {
      message("location-message", "GPS was received but could not be saved: " + error.message, true);
    }
  }, (error) => {
    stopGpsWatch();
    $("location-toggle").checked = false;
    updateDoc(doc(db, "members", currentUser.uid), { locationSharing: false, updatedAt: serverTimestamp() }).catch(() => {});
    message("location-message", "Location not shared: " + error.message + ". Check site permission and try again.", true);
  }, { enableHighAccuracy: true, maximumAge: 15000, timeout: 20000 });
}

$("google-sign-in").addEventListener("click", async () => {
  if (!configured) return showSetupError();
  message("auth-message", "Opening Google sign-in…");
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  try {
    await signInWithPopup(auth, provider);
  } catch (error) {
    if (["auth/popup-blocked", "auth/operation-not-supported-in-this-environment"].includes(error.code)) {
      await signInWithRedirect(auth, provider);
      return;
    }
    message("auth-message", error.message || "Google sign-in failed.", true);
  }
});
for (const id of ["sign-out", "pending-sign-out", "rejected-sign-out"]) {
  $(id).addEventListener("click", async () => {
    stopListeners(); stopGpsWatch(); mapMarkers.forEach(m => map?.removeLayer(m)); mapMarkers.clear();
    await signOut(auth);
  });
}
$("save-profile").addEventListener("click", async () => {
  const displayName = $("profile-name").value.trim();
  if (!displayName) return message("profile-message", "Please enter a display name.", true);
  try {
    await updateDoc(doc(db, "members", currentUser.uid), {
      displayName, phone: $("profile-phone").value.trim(),
      bio: $("profile-bio").value.trim(), updatedAt: serverTimestamp()
    });
    message("profile-message", "Profile saved.");
  } catch (error) { message("profile-message", "Could not save profile: " + error.message, true); }
});
$("location-toggle").addEventListener("change", (event) => setSharing(event.target.checked));

if (!configured) {
  showSetupError();
} else {
  try {
    const app = initializeApp(firebaseConfig);
    auth = getAuth(app);
    db = getFirestore(app);
    getRedirectResult(auth).catch(error => message("auth-message", error.message, true));
    onAuthStateChanged(auth, async (user) => {
      stopListeners(); stopGpsWatch();
      mapMarkers.forEach(m => map?.removeLayer(m)); mapMarkers.clear();
      currentUser = user; currentMember = null;
      if (!user) { showSection("signed-out"); $("user-chip").classList.add("hidden"); return; }
      $("user-chip").textContent = user.displayName || user.email || "Signed in";
      try { await ensureMemberProfile(user); watchProfile(user.uid); }
      catch (error) {
        showSection("signed-out");
        message("auth-message", "Could not create/load your membership. Check Firestore rules and setup: " + error.message, true);
      }
    });
  } catch (error) {
    $("setup-warning").classList.remove("hidden");
    message("auth-message", "Firebase initialization failed: " + error.message, true);
  }
}
