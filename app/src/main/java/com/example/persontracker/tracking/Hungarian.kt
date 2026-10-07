package com.example.persontracker.tracking

/** خوارزمية هنغارية O(n²m) لمشكلة الإسناد الأمثل بمصفوفة تكلفة مستطيلة. */
object Hungarian {
    private const val INF = Double.MAX_VALUE / 4

    /** @return لكل صف رقم العمود المُسنَد إليه، أو -1 إن لم يُسنَد. */
    fun solve(cost: Array<FloatArray>): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        val m = cost[0].size
        if (m == 0) return IntArray(n) { -1 }
        if (n <= m) return solveWide(cost, n, m)
        // عدد الصفوف أكبر: ننقل المصفوفة ثم نعكس النتيجة
        val t = Array(m) { j -> FloatArray(n) { i -> cost[i][j] } }
        val colToRow = solveWide(t, m, n)
        val res = IntArray(n) { -1 }
        for (j in 0 until m) {
            val i = colToRow[j]
            if (i >= 0) res[i] = j
        }
        return res
    }

    private fun solveWide(a: Array<FloatArray>, n: Int, m: Int): IntArray {
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { INF }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = INF
                var j1 = 0
                for (j in 1..m) {
                    if (!used[j]) {
                        val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val ans = IntArray(n) { -1 }
        for (j in 1..m) if (p[j] != 0) ans[p[j] - 1] = j - 1
        return ans
    }
}
