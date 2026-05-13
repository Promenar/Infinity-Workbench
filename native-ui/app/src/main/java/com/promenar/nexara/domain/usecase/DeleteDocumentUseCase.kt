package com.promenar.nexara.domain.usecase

import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IVectorRepository

class DeleteDocumentUseCase(
    private val documentRepository: IDocumentRepository,
    private val vectorRepository: IVectorRepository
) {
    suspend operator fun invoke(documentIds: List<String>) {
        for (docId in documentIds) {
            vectorRepository.deleteByDocument(docId)
            documentRepository.delete(docId)
        }
    }
}
