package com.droidhost.launcher.vm

/**
 * What an import is doing right now.
 *
 * The layer that unpacks stays free of wording, so the interface can say it in the user's own
 * language and a test has nothing to translate.
 */
enum class VmStage {
    /** The archive is being copied into the app's own storage. */
    COPYING,

    /** The archive is being unpacked into the machine's rootfs. */
    UNPACKING,

    /** The system is being read and the session's files are being put in place. */
    PREPARING,

    /** The machine is registered and ready to be used. */
    READY,
}
