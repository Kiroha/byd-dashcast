package com.byd.dashcast.clusterlive

/** Fixed experiment, firmware 13.1.33.2602030.1. Runtime hashes are mandatory; build text is not identity. */
object ClusterThemePolicy {
    const val ANDROID_DIRECTORY = "/sdcard/.cluster/translate/"
    const val HOST_DIRECTORY = "/data/cells/cell2-rw/data/media/0/.cluster/translate/"
    const val JAVA_PACKAGE = "com.xdja.containerservice"
    const val JAVA_APK_SHA = "1b9eef499db47d19a847b77aeaebf823f47449e9d0bc047793aa4433da9a0be9"
    const val ASSET = "cluster_live/marker.rcc"
    const val MAX_CARRIER = 4L * 1024 * 1024
    const val MAX_ARTIFACT = 256L * 1024 * 1024
    const val PREFIX = "Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_"
    fun ownedName(name: String): Boolean = Regex("${PREFIX}[0-9a-f]{32}\\.rcc").matches(name)
    fun permitsCarrierSource(mode: Int, size: Long): Boolean = mode and 0xf000 == 0x8000 && size in 1..MAX_CARRIER
    fun ownsObject(mode: Int, uid: Int, dev: Long, inode: Long, ownerUid: Int, createdDev: Long, createdInode: Long): Boolean =
        createdDev >= 0 && createdInode >= 0 && mode and 0xf000 == 0x8000 && uid == ownerUid && dev == createdDev && inode == createdInode
    fun canDeleteCarrier(defaultQueried: Boolean, loadSettled: Boolean, uncertain: Boolean): Boolean =
        defaultQueried && loadSettled && !uncertain
    fun loadAckMatches(response: ClusterThemeProtocol.Response, name: String): Boolean = ownedName(name) &&
        response.cmd == 1005 && response.sub == 0 && response.ints == listOf(2, 0, 0, 0, 0) &&
        response.strings[0] == HOST_DIRECTORY + name
    val originalHashes = linkedMapOf(
        "/system/lib64/libwindowsurface_android.so" to "c77aa04770ac18fd1e5cb9a526cba92c62559f2da13f93a9ab802ac7f883b058",
        "/system/bin/qtandroidnative" to "d539e048309ad1a7e78d0cc75b381a01bdd67234c820f598cafc9bab652f7ba8",
        "/system/lib64/libBydCluster.so" to "3197abee462e1de4ae476b8643a5570a4e90b3d0623534e9451ea810d8ee8ae8",
        "/system/lib64/libBydDataSource.so" to "1bda5155149d5131af36a8ee2c5e1b4f944738a10b5b7bbafcf4a3945d6ff91d",
        "/vendor/FissionCluster_5_15_10/lib64/libQt5Core_arm64-v8a.so" to "3b9abb05e6800fc3785f5f2b08d64afbaf0fae197aa700c6b370e806647fbe46",
        "/system/lib64/cluster_theme1.rcc" to "1096a4404e0f6f9ea7d62e07935a9b1eb9e4428eb805260f8bb88a370a62f7bb",
        "/system/lib64/cluster_theme2.rcc" to "42940e7c72530d45c0525556e440d640f5f08241e3b2bbd8cd6ff72c6e1451b4",
        "/system/lib64/commonTranslate.rcc" to "5a0f1c39d0921752e863978d85a9f5bc0e0e75f5bd8b5fefa0b0af10624669c8"
    )
}
