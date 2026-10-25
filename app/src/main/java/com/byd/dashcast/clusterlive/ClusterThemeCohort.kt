package com.byd.dashcast.clusterlive

/** Installed artifact cohorts, not loaded renderer/Qt identity or a firmware-label whitelist. */
internal object ClusterThemeCohort {
    private val paths = listOf("/system/lib64/libBydCluster.so", "/system/lib64/libBydDataSource.so",
        "/system/lib64/cluster_theme1.rcc", "/system/lib64/cluster_theme2.rcc", "/system/lib64/commonTranslate.rcc")
    private val legacy = paths.associateWith { checkNotNull(ClusterThemePolicy.originalHashes[it]) }
    private val observed21f7735c = paths.zip(listOf(
        "21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1",
        "02dfc7f5aa046167a3d829c69ef8ee97fd301cf9081450b86366319566c10ae8",
        "83dcdb5bf80aa1b0641622b913cb4f0dc13d3bf7a1fe510f0d10603446faee5d",
        "0041e6b81f5f2b57c9ee112f4f196a16563345214e1e9d6d7bc75625383812b8",
        "1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4"
    )).toMap()
    fun expected(variant: ClusterThemeVariant): Map<String, String> =
        if (variant == ClusterThemeVariant.CORNER_MARKER_21F7735C || variant.isDashboard) observed21f7735c else legacy
    fun id(variant: ClusterThemeVariant): String =
        if (variant == ClusterThemeVariant.CORNER_MARKER_21F7735C || variant.isDashboard) "21f7735c" else "3197abee"
    fun requireMatch(variant: ClusterThemeVariant, actual: Map<String, String>) {
        val expected = expected(variant)
        require(actual.keys == expected.keys) { "Incomplete installed artifact cohort; no new RCC load" }
        for ((path, sha) in expected) require(actual[path] == sha) {
            "Installed artifact cohort ${id(variant)} differs: $path expected=$sha actual=${actual[path]}; no new RCC load"
        }
    }
}
