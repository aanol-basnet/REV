package app.rev.domain.learning

import kotlin.math.exp
import kotlin.math.sqrt

/** The personal skip model as trained on the server (`SkipModel` in the server's schemas). */
data class SkipModel(
    val trainedAt: Long,
    /** The newest listen it learned from. */
    val dataUntil: Long,
    val examples: Int,
    val bias: Double,
    val weights: Map<String, Double>,
)

/**
 * The model the phone uses and keeps training: starts from the server's [SkipModel] (or from
 * nothing) and learns from each listen as it happens, online or offline, with the same method the
 * server trains with (AdaGrad logistic regression).
 */
class OnlineSkipModel(base: SkipModel?, private val learningRate: Double = LEARNING_RATE) {
    private val weights = HashMap<String, Double>(base?.weights ?: emptyMap())
    private val squares = HashMap<String, Double>()
    private var bias = base?.bias ?: 0.0
    private var biasSquares = 0.0

    /** Listens learned from: the server's and this phone's. */
    var examples: Int = base?.examples ?: 0
        private set

    /** The chance of skipping a song with these [features], 0–1. */
    fun skipChance(features: List<String>): Double = sigmoid(bias + features.sumOf { weights[it] ?: 0.0 })

    /** Learns from one listen: [skipped] or not, [weight] 2 for feedback buttons. */
    fun learn(features: List<String>, skipped: Boolean, weight: Double = 1.0) {
        val g = (skipChance(features) - if (skipped) 1.0 else 0.0) * weight
        biasSquares += g * g
        bias -= learningRate * g / sqrt(biasSquares + EPSILON)
        for (f in features) {
            val w = weights[f] ?: 0.0
            val grad = g + L2 * w
            val sq = (squares[f] ?: 0.0) + grad * grad
            squares[f] = sq
            weights[f] = w - learningRate * grad / sqrt(sq + EPSILON)
        }
        examples += 1
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-30.0, 30.0)))

    companion object {
        const val LEARNING_RATE = 0.15
        private const val L2 = 1e-5
        private const val EPSILON = 1e-8
    }
}

/** The personal skip model on the phone: what it predicts, and learning from each listen. */
interface PersonalModel {
    /** The chance of skipping a song in this situation, 0–1; null until there's anything learned. */
    suspend fun skipChance(input: FeatureInput): Double?

    /** How much to trust the model, 0 (nothing learned) to 1 (a thousand listens or more). */
    suspend fun confidence(): Double

    /** Learns from one listen that started at [startedAt], kept on the phone so it counts offline too. */
    suspend fun learn(input: FeatureInput, skipped: Boolean, weight: Double, startedAt: Long)

    /** Picks up a newer model from the server, if there is one and it's time to look. */
    suspend fun refresh() = Unit
}
