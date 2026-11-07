package com.omnihub.source

import android.content.Context
import com.omnihub.source.extension.InstalledSourceScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Providers list = only packages the user installed from Store.
 * No hardcoded built-in provider list in the UI.
 */
class SourceManager(private val context: Context) {
    private val _sources = MutableStateFlow<List<AiSource>>(emptyList())
    val sources: StateFlow<List<AiSource>> = _sources.asStateFlow()
    private val installedDescriptors = mutableListOf<AiSource>()

    init { reload() }

    fun reload() {
        val fromApks = InstalledSourceScanner.toSources(context)
        // Built-in keyless sources (WebView-native lanes) — APK installs win on id clash
        val builtIns = listOf(com.omnihub.source.web.DuckAiSource(context))
            .filter { b -> fromApks.none { it.info.id == b.info.id } }
        val all = fromApks + builtIns
        // Merge descriptors without duplicates
        val ids = all.map { it.info.id }.toSet()
        val extra = installedDescriptors.filter { it.info.id !in ids }
        _sources.value = all + extra
    }

    fun all(): List<AiSource> = _sources.value
    fun get(id: String): AiSource? = _sources.value.find { it.info.id == id }
    fun configured(): List<AiSource> = _sources.value.filter { it.isConfigured() }
    fun healthy(): List<AiSource> =
        _sources.value.filter { it.health() == SourceHealth.HEALTHY && it.isConfigured() }

    fun installDescriptor(descriptor: SourceDescriptor) {
        installedDescriptors.removeAll { it.info.id == descriptor.id }
        installedDescriptors.add(DescriptorSource(context, descriptor))
        reload()
    }

    fun registerExtension(source: AiSource) {
        installedDescriptors.removeAll { it.info.id == source.info.id }
        installedDescriptors.add(source)
        reload()
    }

    fun uninstall(id: String) {
        installedDescriptors.removeAll { it.info.id == id }
        reload()
    }
}
