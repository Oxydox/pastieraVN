package it.palsoftware.pastiera.core.suggestions

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

/**
 * Covers the trigram(two-word context)-over-bigram(one-word context) backoff/blend added to
 * support richer contextual next-word prediction, and the prefix-matching mode used to boost
 * current-word-in-progress suggestions with that context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NextWordPredictorContextTest {

    private lateinit var store: UserNGramStore
    private lateinit var predictor: NextWordPredictor

    @Before
    fun setUp() {
        store = UserNGramStore(RuntimeEnvironment.getApplication()).apply { clearAll() }
        // Synchronous learning so assertions don't need to flush/wait.
        predictor = NextWordPredictor(store, asyncLearning = false)
    }

    @After
    fun tearDown() {
        store.clearAll()
        store.close()
    }

    @Test
    fun twoWordContextPromotesSpecificTrigramAboveGenericBigram() {
        // "am" is generally followed by "fine" (learned 3x as plain bigram usage elsewhere),
        // but this exact "I am" context was specifically followed by "happy" once.
        repeat(3) { predictor.learn(Locale.ENGLISH, listOf("am"), "fine") }
        predictor.learn(Locale.ENGLISH, listOf("I", "am"), "happy")

        val withFullContext = predictor.predict(Locale.ENGLISH, listOf("I", "am"), limit = 3)
        assertEquals(listOf("happy", "fine"), withFullContext.map { it.candidate })

        // Without the specific two-word context, it falls back to the generic bigram ranking
        // (both candidates are known to follow "am" in general; "fine" was used 3x so it wins).
        val bigramOnly = predictor.predict(Locale.ENGLISH, listOf("am"), limit = 3)
        assertEquals(listOf("fine", "happy"), bigramOnly.map { it.candidate })
    }

    @Test
    fun unseenTrigramFallsBackToBigram() {
        predictor.learn(Locale.ENGLISH, listOf("to"), "school")

        // "go to" has never been learned as a trigram, so it should fall back to whatever the
        // bigram ("to" -> ...) knows.
        val predictions = predictor.predict(Locale.ENGLISH, listOf("go", "to"), limit = 3)
        assertEquals(listOf("school"), predictions.map { it.candidate })
    }

    @Test
    fun predictMatchingPrefixFiltersToTypedLetters() {
        predictor.learn(Locale.ENGLISH, listOf("I", "am"), "happy")
        predictor.learn(Locale.ENGLISH, listOf("am"), "fine")

        val matches = predictor.predictMatchingPrefix(Locale.ENGLISH, listOf("I", "am"), typedPrefix = "h", limit = 3)
        assertEquals(listOf("happy"), matches.map { it.candidate })

        val noMatches = predictor.predictMatchingPrefix(Locale.ENGLISH, listOf("I", "am"), typedPrefix = "z", limit = 3)
        assertTrue(noMatches.isEmpty())
    }

    @Test
    fun forgetWithTwoWordContextRemovesBothBigramAndTrigramEntries() {
        predictor.learn(Locale.ENGLISH, listOf("am"), "fine")
        predictor.learn(Locale.ENGLISH, listOf("I", "am"), "happy")

        val removed = predictor.forget(Locale.ENGLISH, listOf("I", "am"), "happy")

        assertTrue(removed)
        // "happy" is gone from both the trigram context and the "am" bigram it was also
        // recorded under, but the unrelated "fine" bigram association survives untouched.
        assertEquals(listOf("fine"), predictor.predict(Locale.ENGLISH, listOf("I", "am"), limit = 3).map { it.candidate })
        assertEquals(listOf("fine"), predictor.predict(Locale.ENGLISH, listOf("am"), limit = 3).map { it.candidate })
    }

    @Test
    fun accentAndCaseInsensitiveAcrossContextWords() {
        predictor.learn(Locale.FRENCH, listOf("Je", "SUIS"), "content")

        val predictions = predictor.predict(Locale.FRENCH, listOf("je", "suis"), limit = 3)
        assertEquals(listOf("content"), predictions.map { it.candidate })
    }
}
