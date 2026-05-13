package com.promenar.nexara.domain.usecase

import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DeleteDocumentUseCaseTest {

    private val documentRepository = mockk<IDocumentRepository>(relaxed = true)
    private val vectorRepository = mockk<IVectorRepository>(relaxed = true)
    private val useCase = DeleteDocumentUseCase(documentRepository, vectorRepository)

    @Test
    fun `deletes vectors then document for each id`() = runTest {
        val ids = listOf("doc1", "doc2", "doc3")
        useCase(ids)

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            vectorRepository.deleteByDocument("doc1")
            documentRepository.delete("doc1")
            vectorRepository.deleteByDocument("doc2")
            documentRepository.delete("doc2")
            vectorRepository.deleteByDocument("doc3")
            documentRepository.delete("doc3")
        }
    }

    @Test
    fun `handles empty list`() = runTest {
        useCase(emptyList())
        coVerify(exactly = 0) { vectorRepository.deleteByDocument(any()) }
        coVerify(exactly = 0) { documentRepository.delete(any()) }
    }

    @Test
    fun `handles single document`() = runTest {
        useCase(listOf("only"))
        coVerify(exactly = 1) { vectorRepository.deleteByDocument("only") }
        coVerify(exactly = 1) { documentRepository.delete("only") }
    }
}
