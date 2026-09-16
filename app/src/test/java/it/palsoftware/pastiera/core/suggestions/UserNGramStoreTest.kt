package it.palsoftware.pastiera.core.suggestions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserNGramStoreTest {

    private lateinit var store: UserNGramStore

    @Before
    fun setUp() {
        store = UserNGramStore(RuntimeEnvironment.getApplication())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
        store.close()
    }

    @Test
    fun learn_insertsNewBigram() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)

        val predictions = store.predict("de-DE", "ich", limit = 3)

        assertEquals(listOf("bin"), predictions.map { it.word })
        assertEquals(1, predictions.first().count)
        assertEquals(100L, predictions.first().lastUsed)
    }

    @Test
    fun learn_incrementsExistingBigram() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "ich", "bin", nowMs = 200L)

        val prediction = store.predict("de-DE", "ich", limit = 3).first()

        assertEquals("bin", prediction.word)
        assertEquals(2, prediction.count)
        assertEquals(200L, prediction.lastUsed)
    }

    @Test
    fun predict_sortsByCountThenRecency() {
        store.learn("de-DE", "ich", "werde", nowMs = 300L)
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "ich", "bin", nowMs = 200L)
        store.learn("de-DE", "ich", "habe", nowMs = 400L)

        val predictions = store.predict("de-DE", "ich", limit = 3)

        assertEquals(listOf("bin", "habe", "werde"), predictions.map { it.word })
    }

    @Test
    fun predict_keepsLocalesSeparate() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("en-US", "ich", "am", nowMs = 200L)

        assertEquals(listOf("bin"), store.predict("de-DE", "ich", limit = 3).map { it.word })
        assertEquals(listOf("am"), store.predict("en-US", "ich", limit = 3).map { it.word })
        assertTrue(store.predict("fr-FR", "ich", limit = 3).isEmpty())
    }

    @Test
    fun delete_removesOneBigramCaseInsensitively() {
        store.learn("de-DE", "ich", "Bin", nowMs = 100L)
        store.learn("de-DE", "ich", "habe", nowMs = 200L)

        val deleted = store.delete("de-DE", "ich", "bin")

        assertEquals(1, deleted)
        assertEquals(listOf("habe"), store.predict("de-DE", "ich", limit = 3).map { it.word })
    }

    @Test
    fun deleteNextWord_removesWordAcrossPrefixes() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learn("de-DE", "wir", "bin", nowMs = 200L)

        val deleted = store.deleteNextWord("de-DE", "bin")

        assertEquals(2, deleted)
        assertTrue(store.predict("de-DE", "ich", limit = 3).isEmpty())
        assertTrue(store.predict("de-DE", "wir", limit = 3).isEmpty())
    }

    // --- Trigram (two-word context) table -------------------------------------------------
    // Mirrors the bigram behavior above but on the separate trigram table, and confirms the
    // two tables don't leak into each other.

    @Test
    fun learnTrigram_insertsAndIncrementsIndependentlyOfBigrams() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L) // unrelated bigram
        store.learnTrigram("de-DE", "ich\u001Fbin", "zuhause", nowMs = 200L)
        store.learnTrigram("de-DE", "ich\u001Fbin", "zuhause", nowMs = 300L)

        val trigramPredictions = store.predictTrigram("de-DE", "ich\u001Fbin", limit = 3)
        assertEquals(listOf("zuhause"), trigramPredictions.map { it.word })
        assertEquals(2, trigramPredictions.first().count)

        // The bigram table is untouched by trigram writes.
        assertEquals(listOf("bin"), store.predict("de-DE", "ich", limit = 3).map { it.word })
        assertTrue(store.predictTrigram("de-DE", "ich", limit = 3).isEmpty())
    }

    @Test
    fun deleteTrigram_removesOnlyFromTrigramTable() {
        store.learn("de-DE", "bin", "zuhause", nowMs = 100L)
        store.learnTrigram("de-DE", "ich\u001Fbin", "zuhause", nowMs = 200L)

        val deletedTrigram = store.deleteTrigram("de-DE", "ich\u001Fbin", "zuhause")

        assertEquals(1, deletedTrigram)
        assertTrue(store.predictTrigram("de-DE", "ich\u001Fbin", limit = 3).isEmpty())
        // Bigram entry for the same word survives.
        assertEquals(listOf("zuhause"), store.predict("de-DE", "bin", limit = 3).map { it.word })
    }

    @Test
    fun deleteTrigramNextWord_removesAcrossContextsButNotBigrams() {
        store.learn("de-DE", "bin", "zuhause", nowMs = 100L)
        store.learnTrigram("de-DE", "ich\u001Fbin", "zuhause", nowMs = 200L)
        store.learnTrigram("de-DE", "wir\u001Fsind", "zuhause", nowMs = 300L)

        val deleted = store.deleteTrigramNextWord("de-DE", "zuhause")

        assertEquals(2, deleted)
        assertTrue(store.predictTrigram("de-DE", "ich\u001Fbin", limit = 3).isEmpty())
        assertTrue(store.predictTrigram("de-DE", "wir\u001Fsind", limit = 3).isEmpty())
        assertEquals(listOf("zuhause"), store.predict("de-DE", "bin", limit = 3).map { it.word })
    }

    @Test
    fun clearAll_clearsBothBigramAndTrigramTables() {
        store.learn("de-DE", "ich", "bin", nowMs = 100L)
        store.learnTrigram("de-DE", "ich\u001Fbin", "zuhause", nowMs = 200L)

        store.clearAll()

        assertTrue(store.predict("de-DE", "ich", limit = 3).isEmpty())
        assertTrue(store.predictTrigram("de-DE", "ich\u001Fbin", limit = 3).isEmpty())
    }
}
