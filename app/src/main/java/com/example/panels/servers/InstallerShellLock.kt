package com.example.panels.servers

/**
 * Cooperative lock for Maximus-owned installer scripts on one SSH account. The lock guards the
 * *whole script execution*, not the earlier remote inspection or the later Android Room write.
 * Other installers (including 3rd-party panels) are not guaranteed to honor this lock.
 *
 * Fail closed when flock is unavailable or when the lock cannot be acquired. This deliberately
 * avoids a predictable world-writable /tmp lock and rejects unsafe lock directories.
 */
object InstallerShellLock {
    const val BUSY_EXIT = 79
    const val UNAVAILABLE_EXIT = 78

    fun wrap(script: String): String = """
        # MAXIMUS_INSTALLER_LOCK_V1
        set -eu
        umask 077
        if ! command -v flock >/dev/null 2>&1; then
          echo 'Maximus installer lock unavailable: install util-linux/flock before proceeding' >&2
          exit $UNAVAILABLE_EXIT
        fi
        mx_lock_dir="§{HOME:?}/.maximus-vpn"
        if [ -L "§mx_lock_dir" ]; then
          echo 'Unsafe Maximus installer lock directory' >&2
          exit $UNAVAILABLE_EXIT
        fi
        if [ ! -d "§mx_lock_dir" ]; then mkdir -m 700 "§mx_lock_dir" || exit $UNAVAILABLE_EXIT; fi
        if [ ! -O "§mx_lock_dir" ]; then
          echo 'Maximus installer lock directory belongs to another account' >&2
          exit $UNAVAILABLE_EXIT
        fi
        chmod 700 "§mx_lock_dir" || exit $UNAVAILABLE_EXIT
        if [ -L "§mx_lock_dir/installer.lock" ]; then
          echo 'Unsafe Maximus installer lock file' >&2
          exit $UNAVAILABLE_EXIT
        fi
        exec 9>"§mx_lock_dir/installer.lock"
        if ! flock -x -w 10 9; then
          echo 'Another Maximus installer is active; no changes started' >&2
          exit $BUSY_EXIT
        fi
        # This file descriptor remains locked until the script and its children exit.
    """.trimIndent().replace('§', '$') + "\n" + script
}
