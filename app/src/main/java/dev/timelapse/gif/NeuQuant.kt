package dev.timelapse.gif

/**
 * NeuQuant neural-net color quantizer (Anthony Dekker, 1994), reduces an RGB image to a 256-color palette.
 *
 * Input is packed 3-bytes-per-pixel data; the three channels are treated symmetrically, so whatever order
 * is fed in (we use R,G,B) is the order of [process]'s palette output and [map]'s arguments.
 */
class NeuQuant(private val picture: ByteArray, private val lengthCount: Int, sampleFactor: Int) {

    private companion object {
        const val NET_SIZE = 256
        const val PRIME1 = 499
        const val PRIME2 = 491
        const val PRIME3 = 487
        const val PRIME4 = 503
        const val MIN_PICTURE_BYTES = 3 * PRIME4
        const val MAX_NET_POS = NET_SIZE - 1
        const val NET_BIAS_SHIFT = 4
        const val N_CYCLES = 100
        const val INT_BIAS_SHIFT = 16
        const val INT_BIAS = 1 shl INT_BIAS_SHIFT
        const val GAMMA_SHIFT = 10
        const val BETA_SHIFT = 10
        const val BETA = INT_BIAS shr BETA_SHIFT
        const val BETA_GAMMA = INT_BIAS shl (GAMMA_SHIFT - BETA_SHIFT)
        const val INIT_RAD = NET_SIZE shr 3
        const val RADIUS_BIAS_SHIFT = 6
        const val RADIUS_BIAS = 1 shl RADIUS_BIAS_SHIFT
        const val INIT_RADIUS = INIT_RAD * RADIUS_BIAS
        const val RADIUS_DEC = 30
        const val ALPHA_BIAS_SHIFT = 10
        const val INIT_ALPHA = 1 shl ALPHA_BIAS_SHIFT
        const val RAD_BIAS_SHIFT = 8
        const val RAD_BIAS = 1 shl RAD_BIAS_SHIFT
        const val ALPHA_RAD_B_SHIFT = ALPHA_BIAS_SHIFT + RAD_BIAS_SHIFT
        const val ALPHA_RAD_BIAS = 1 shl ALPHA_RAD_B_SHIFT
    }

    private var sampleFac = sampleFactor.coerceIn(1, 30)
    private val network = Array(NET_SIZE) { IntArray(4) }
    private val netIndex = IntArray(256)
    private val bias = IntArray(NET_SIZE)
    private val freq = IntArray(NET_SIZE)
    private val radPower = IntArray(INIT_RAD)

    init {
        for (i in 0 until NET_SIZE) {
            val v = (i shl (NET_BIAS_SHIFT + 8)) / NET_SIZE
            network[i][0] = v
            network[i][1] = v
            network[i][2] = v
            freq[i] = INT_BIAS / NET_SIZE
            bias[i] = 0
        }
    }

    /** Trains the network and returns the palette: 256 entries x 3 bytes, in input channel order. */
    fun process(): ByteArray {
        learn()
        unbiasNet()
        buildIndex()
        return colorMap()
    }

    /** Returns the palette index closest to the given color (after [process]). */
    fun map(c0: Int, c1: Int, c2: Int): Int {
        var bestD = 1000
        var best = -1
        var i = netIndex[c1]
        var j = i - 1
        while (i < NET_SIZE || j >= 0) {
            if (i < NET_SIZE) {
                val p = network[i]
                var dist = p[1] - c1
                if (dist >= bestD) {
                    i = NET_SIZE
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - c0
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestD) {
                        a = p[2] - c2
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestD) {
                            bestD = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = c1 - p[1]
                if (dist >= bestD) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - c0
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestD) {
                        a = p[2] - c2
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestD) {
                            bestD = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    private fun colorMap(): ByteArray {
        val map = ByteArray(3 * NET_SIZE)
        val index = IntArray(NET_SIZE)
        for (i in 0 until NET_SIZE) index[network[i][3]] = i
        var k = 0
        for (i in 0 until NET_SIZE) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    /** Selection sort of the network by channel 1 and construction of [netIndex]. */
    private fun buildIndex() {
        var previousCol = 0
        var startPos = 0
        for (i in 0 until NET_SIZE) {
            val p = network[i]
            var smallPos = i
            var smallVal = p[1]
            for (j in i + 1 until NET_SIZE) {
                val q = network[j]
                if (q[1] < smallVal) {
                    smallPos = j
                    smallVal = q[1]
                }
            }
            val q = network[smallPos]
            if (i != smallPos) {
                for (c in 0 until 4) {
                    val t = q[c]; q[c] = p[c]; p[c] = t
                }
            }
            if (smallVal != previousCol) {
                netIndex[previousCol] = (startPos + i) shr 1
                for (j in previousCol + 1 until smallVal) netIndex[j] = i
                previousCol = smallVal
                startPos = i
            }
        }
        netIndex[previousCol] = (startPos + MAX_NET_POS) shr 1
        for (j in previousCol + 1 until 256) netIndex[j] = MAX_NET_POS
    }

    private fun learn() {
        if (lengthCount < MIN_PICTURE_BYTES) sampleFac = 1
        val alphaDec = 30 + (sampleFac - 1) / 3
        var pix = 0
        val samplePixels = lengthCount / (3 * sampleFac)
        var delta = samplePixels / N_CYCLES
        var alpha = INIT_ALPHA
        var radius = INIT_RADIUS
        var rad = radius shr RADIUS_BIAS_SHIFT
        if (rad <= 1) rad = 0
        for (i in 0 until rad) radPower[i] = alpha * (((rad * rad - i * i) * RAD_BIAS) / (rad * rad))

        val step = when {
            lengthCount < MIN_PICTURE_BYTES -> 3
            lengthCount % PRIME1 != 0 -> 3 * PRIME1
            lengthCount % PRIME2 != 0 -> 3 * PRIME2
            lengthCount % PRIME3 != 0 -> 3 * PRIME3
            else -> 3 * PRIME4
        }
        if (delta == 0) delta = 1

        var i = 0
        while (i < samplePixels) {
            val c0 = (picture[pix].toInt() and 0xff) shl NET_BIAS_SHIFT
            val c1 = (picture[pix + 1].toInt() and 0xff) shl NET_BIAS_SHIFT
            val c2 = (picture[pix + 2].toInt() and 0xff) shl NET_BIAS_SHIFT
            val j = contest(c0, c1, c2)
            alterSingle(alpha, j, c0, c1, c2)
            if (rad != 0) alterNeighbours(rad, j, c0, c1, c2)

            pix += step
            if (pix >= lengthCount) pix -= lengthCount
            i++
            if (i % delta == 0) {
                alpha -= alpha / alphaDec
                radius -= radius / RADIUS_DEC
                rad = radius shr RADIUS_BIAS_SHIFT
                if (rad <= 1) rad = 0
                for (k in 0 until rad) radPower[k] = alpha * (((rad * rad - k * k) * RAD_BIAS) / (rad * rad))
            }
        }
    }

    private fun unbiasNet() {
        for (i in 0 until NET_SIZE) {
            network[i][0] = network[i][0] shr NET_BIAS_SHIFT
            network[i][1] = network[i][1] shr NET_BIAS_SHIFT
            network[i][2] = network[i][2] shr NET_BIAS_SHIFT
            network[i][3] = i
        }
    }

    private fun alterNeighbours(rad: Int, i: Int, c0: Int, c1: Int, c2: Int) {
        val lo = maxOf(i - rad, -1)
        val hi = minOf(i + rad, NET_SIZE)
        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radPower[m++]
            if (j < hi) {
                val p = network[j++]
                p[0] -= (a * (p[0] - c0)) / ALPHA_RAD_BIAS
                p[1] -= (a * (p[1] - c1)) / ALPHA_RAD_BIAS
                p[2] -= (a * (p[2] - c2)) / ALPHA_RAD_BIAS
            }
            if (k > lo) {
                val p = network[k--]
                p[0] -= (a * (p[0] - c0)) / ALPHA_RAD_BIAS
                p[1] -= (a * (p[1] - c1)) / ALPHA_RAD_BIAS
                p[2] -= (a * (p[2] - c2)) / ALPHA_RAD_BIAS
            }
        }
    }

    private fun alterSingle(alpha: Int, i: Int, c0: Int, c1: Int, c2: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - c0)) / INIT_ALPHA
        n[1] -= (alpha * (n[1] - c1)) / INIT_ALPHA
        n[2] -= (alpha * (n[2] - c2)) / INIT_ALPHA
    }

    private fun contest(c0: Int, c1: Int, c2: Int): Int {
        var bestD = Int.MAX_VALUE
        var bestBiasD = bestD
        var bestPos = -1
        var bestBiasPos = -1
        for (i in 0 until NET_SIZE) {
            val n = network[i]
            var dist = n[0] - c0
            if (dist < 0) dist = -dist
            var a = n[1] - c1
            if (a < 0) a = -a
            dist += a
            a = n[2] - c2
            if (a < 0) a = -a
            dist += a
            if (dist < bestD) {
                bestD = dist
                bestPos = i
            }
            val biasDist = dist - (bias[i] shr (INT_BIAS_SHIFT - NET_BIAS_SHIFT))
            if (biasDist < bestBiasD) {
                bestBiasD = biasDist
                bestBiasPos = i
            }
            val betaFreq = freq[i] shr BETA_SHIFT
            freq[i] -= betaFreq
            bias[i] += betaFreq shl GAMMA_SHIFT
        }
        freq[bestPos] += BETA
        bias[bestPos] -= BETA_GAMMA
        return bestBiasPos
    }
}
