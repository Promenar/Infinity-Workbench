package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.model.catalog.ModelMetadataOverride

data class RemoteModelDescriptor(
    val id: String,
    val ownedBy: String? = null,
    val sourceProviderId: String? = null,
    val metadata: ModelMetadataOverride = ModelMetadataOverride(),
)
