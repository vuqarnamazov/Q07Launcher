package az.q07.launcher.media

import android.service.notification.NotificationListenerService

/**
 * Boş xidmət. Yalnız ona görə lazımdır ki, sistem bu launcher-ə
 * aktiv media sessiyalarını (hansı musiqi çalır) oxumağa icazə versin.
 *
 * İcazəni ADB ilə bir dəfə ver:
 *   adb shell cmd notification allow_listener az.q07.launcher/az.q07.launcher.media.MediaListenerService
 */
class MediaListenerService : NotificationListenerService()
