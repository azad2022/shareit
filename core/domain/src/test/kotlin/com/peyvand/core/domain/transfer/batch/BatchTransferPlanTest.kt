package com.peyvand.core.domain.transfer.batch

import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferPlanTest {
    @Test
    fun \`plan preserves selection order and generates distinct batch and item identifiers\`() {
        val selected = listOf(
            shareItem("first.pdf", 10L, "content://provider/document/first"),
            shareItem("second.jpg", 20L, "content://provider/document/second"),
            shareItem("third.bin", 30L, "content://provider/document/third"),
        )

        val plan = BatchTransferPlan.create(selected)

        assertEquals(3, plan.items.size)
        assertEquals(0, plan.items[0].ordinal)
        assertEquals(1, plan.items[1].ordinal)
        assertEquals(2, plan.items[2].ordinal)
        assertEquals(selected, plan.items.map { it.sourceItem })
        assertEquals(60L, plan.aggregateSizeBytes)
        assertEquals(plan.items.size, plan.items.map { it.itemId }.toSet().size)
        assertTrue(plan.items.none { it.itemId == plan.batchId })
        assertNotEquals(UUID(0L, 0L), plan.batchId)
    }

    @Test
    fun \`one selected item is rejected because single file uses protocol v1\`() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferPlan.create(
                listOf(shareItem("one.bin", 1L, "content://provider/one")),
            )
        }
    }

    @Test
    fun \`empty and over-limit selections are rejected\`() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferPlan.create(emptyList())
        }
        val tooMany = (0..BatchTransferPlan.MAX_BATCH_ITEMS).map { index ->
            shareItem(
                displayName = "item-" + index + ".bin",
                sizeBytes = 0L,
                uri = "content://provider/" + index,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferPlan.create(tooMany)
        }
    }

    @Test
    fun \`installed packages and invalid content URIs are rejected\`() {
        val installedPackage = ShareItem(
            id = "package",
            category = ShareCategory.APPLICATIONS,
            displayName = "Example",
            mimeType = "application/vnd.android.package-archive",
            sizeBytes = 1L,
            modifiedAtEpochMillis = null,
            source = ShareSource.InstalledPackage("com.example.app"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferPlan.create(listOf(installedPackage, installedPackage))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferPlan.create(
                listOf(
                    shareItem("one.bin", 1L, "file:///sdcard/one"),
                    shareItem("two.bin", 2L, "content://provider/two"),
                ),
            )
        }
    }

    @Test
    fun \`unknown item size makes aggregate size unknown\`() {
        val plan = BatchTransferPlan.create(
            listOf(
                shareItem("known.bin", 12L, "content://provider/known"),
                shareItem("unknown.bin", null, "content://provider/unknown"),
            ),
        )

        assertEquals(null, plan.aggregateSizeBytes)
    }

    @Test
    fun \`aggregate size overflow is represented as unknown rather than wrapping\`() {
        val plan = BatchTransferPlan.create(
            listOf(
                shareItem("large.bin", Long.MAX_VALUE, "content://provider/large"),
                shareItem("one.bin", 1L, "content://provider/one"),
            ),
        )

        assertEquals(null, plan.aggregateSizeBytes)
    }

    @Test
    fun \`plan item collection cannot be mutated through a cast\`() {
        val plan = BatchTransferPlan.create(
            listOf(
                shareItem("one.bin", 1L, "content://provider/one"),
                shareItem("two.bin", 2L, "content://provider/two"),
            ),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.items as MutableList<BatchTransferPlanItem>).clear()
        }
    }

    private fun shareItem(
        displayName: String,
        sizeBytes: Long?,
        uri: String,
    ): ShareItem = ShareItem(
        id = UUID.randomUUID().toString(),
        category = ShareCategory.FILES,
        displayName = displayName,
        mimeType = "application/octet-stream",
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        source = ShareSource.ContentUri(uri),
    )
}
