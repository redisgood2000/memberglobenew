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
    const tokenSnapshot = await db.collection("deviceTokens").get();
    const tokens = tokenSnapshot.docs
      .filter((doc) => doc.id !== message.senderUid)
      .map((doc) => doc.data().token)
      .filter((token) => typeof token === "string" && token.length > 0);
    if (!tokens.length) return;

    const body = message.text.length > 140
      ? message.text.slice(0, 137) + "..."
      : message.text;
    const result = await getMessaging().sendEachForMulticast({
      tokens,
      notification: {
        title: message.senderName || "Member Globe",
        body,
      },
      data: {
        type: "group_chat",
        messageId: event.params.messageId,
        senderUid: String(message.senderUid),
        title: message.senderName || "Member Globe",
        body,
      },
      android: { priority: "high", notification: { channelId: "member_globe_group_chat" } },
    });

    const invalid = [];
    result.responses.forEach((response, index) => {
      if (!response.success && [
        "messaging/invalid-registration-token",
        "messaging/registration-token-not-registered",
      ].includes(response.error?.code)) invalid.push(tokens[index]);
    });
    if (invalid.length) {
      const batch = db.batch();
      tokenSnapshot.docs.forEach((doc) => {
        if (invalid.includes(doc.data().token)) batch.delete(doc.ref);
      });
      await batch.commit();
    }
  }
);
