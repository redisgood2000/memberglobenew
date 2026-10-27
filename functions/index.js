const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.notifyGroupChat = onDocumentCreated(
  { document: "groupMessages/{messageId}", region: "asia-southeast1" },
  async (event) => {
    const message = event.data?.data();
    if (!message || typeof message.text !== "string" || !message.senderUid) return;

    const db = getFirestore();
    const membersSnapshot = await db.collection("members")
      .where("status", "==", "approved").get();

    const tokens = [];
    for (const memberDoc of membersSnapshot.docs) {
      if (memberDoc.id === message.senderUid) continue;
      const tokenDoc = await db.collection("deviceTokens").doc(memberDoc.id).get();
      const token = tokenDoc.exists ? tokenDoc.get("token") : null;
      if (typeof token === "string" && token.length > 0) tokens.push(token);
    }

    if (tokens.length === 0) return;
    const senderName = String(message.senderName || "Member").slice(0, 80);
    const body = String(message.text).replace(/\\s+/g, " ").slice(0, 180);
    for (let offset = 0; offset < tokens.length; offset += 500) {
      const batch = tokens.slice(offset, offset + 500);
      const response = await getMessaging().sendEachForMulticast({
        tokens: batch,
        notification: {
          title: senderName + " · Member Globe",
          body
        },
        data: {
          type: "group_chat",
          senderUid: String(message.senderUid),
          senderName,
          body
        },
        android: { priority: "high", notification: { channelId: "member_globe_chat" } }
      });
      const invalid = [];
      response.responses.forEach((result, index) => {
        if (!result.success && result.error &&
            ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(result.error.code)) {
          invalid.push(batch[index]);
        }
      });
      if (invalid.length) {
        const tokenDocs = await db.collection("deviceTokens").get();
        const deletions = tokenDocs.docs
          .filter(doc => invalid.includes(doc.get("token")))
          .map(doc => doc.ref.delete());
        await Promise.all(deletions);
      }
    }
  }
);
