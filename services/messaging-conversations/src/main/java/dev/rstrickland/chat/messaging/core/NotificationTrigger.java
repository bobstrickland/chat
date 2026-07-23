package dev.rstrickland.chat.messaging.core;

/**
 * Signals that a message could not be delivered live because the recipient has
 * no active connection — i.e. they're offline. The Notification service consumes
 * this (topic notification.trigger) and pushes to their devices.
 *
 * Emitted per-recipient by DeliveryService, so it already carries the specific
 * offline user; Notification never has to resolve conversation membership
 * (which is Messaging's data — CLAUDE.md No shared databases).
 */
public interface NotificationTrigger {
  void offlineRecipient(String recipientId, Message message);
}
