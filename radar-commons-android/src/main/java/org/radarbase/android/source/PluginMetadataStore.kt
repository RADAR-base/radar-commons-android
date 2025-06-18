package org.radarbase.android.source

import java.util.concurrent.ConcurrentHashMap

data class PluginMetadataStore  (
    val sourceIds: MutableSet<String> = mutableSetOf(),
    val pluginToSourceIdMap: MutableMap<String, String> = ConcurrentHashMap(20, 0.75f),
    val topicToPluginMap: MutableMap<String, String> = ConcurrentHashMap(30, 0.75f)
)
