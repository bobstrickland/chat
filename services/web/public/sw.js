/* Push service worker (Phase 5). Not the Angular PWA service worker — a minimal
   one whose only job is to show a system notification when a Web Push arrives
   (i.e. when the tab is closed/backgrounded and the recipient was "offline").

   The payload is what Notification builds in sendPushToRecipient:
   { title, body, data: { conversationId, senderId } }. */

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = { title: 'New message', body: event.data ? event.data.text() : '' };
  }
  const title = payload.title || 'New message';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: payload.body || '',
      data: payload.data || {},
      tag: payload.data?.conversationId, // collapse repeats from one conversation
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  // Focus an existing app tab if there is one, else open the chat.
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) return client.focus();
      }
      return self.clients.openWindow('/chat');
    }),
  );
});
