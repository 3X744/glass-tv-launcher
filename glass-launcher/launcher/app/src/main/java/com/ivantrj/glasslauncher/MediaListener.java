package com.ivantrj.glasslauncher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Glass can be granted notification access, which is what Android requires
 * before MediaSessionManager.getActiveSessions() lists other apps' players (Now Playing pill).
 * It reads no notifications. Granted over adb:
 *   cmd notification allow_listener com.ivantrj.glasslauncher/com.ivantrj.glasslauncher.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
