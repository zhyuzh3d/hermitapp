package io.github.zhyuzh3d.hermit.model

data class CodeRelease(
    val releaseId: String,
    val appId: String,
    val treeHash: String,
    val provenance: String,
    val versionCode: Long?,
    val versionName: String?,
    val sourceRevision: String?,
    val entryPath: String,
    val relativeRoot: String,
    val createdAt: Long,
)
