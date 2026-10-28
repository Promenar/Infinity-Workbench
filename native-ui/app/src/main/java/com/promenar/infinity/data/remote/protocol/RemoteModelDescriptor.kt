package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.model.catalog.ModelMetadataOverride

data class RemoteModelDescriptor(
    val id: String,
    val ownedBy: String? = null,
    val sourceProviderId: String? = null,
    val metadata: ModelMetadataOverride = ModelMetadataOverride(),
)
