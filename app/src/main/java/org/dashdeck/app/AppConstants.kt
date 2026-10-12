package org.dashdeck.app

object AppConstants {
    const val MENU_BUTTON_AUTO_HIDE_DELAY_MS = 3000L
    const val MENU_BUTTON_SHOW_DELAY_MS = 500L
    const val GITHUB_REPO_URL = "https://github.com/${BuildConfig.GITHUB_REPO}"
    const val RELEASES_URL = "$GITHUB_REPO_URL/releases"
    const val REQUEST_CODE_POST_NOTIFICATIONS = 1101
    const val REQUEST_CODE_RECORD_AUDIO = 1102
    const val REQUEST_CODE_ACCESS_LOCATION = 1103
}
