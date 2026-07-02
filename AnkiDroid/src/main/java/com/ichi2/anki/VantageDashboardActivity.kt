// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import anki.decks.Deck
import anki.decks.DeckKt.FilteredKt.searchTerm
import anki.decks.DeckKt.filtered
import anki.decks.filteredDeckForUpdate
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.utils.ViewGroupUtils.setRenderWorkaround
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Vantage readiness dashboard: shows the three scores with their ranges, exam
 * coverage, and the best next topic in a WebView, matching the desktop UI.
 *
 * The page is a self-contained asset (assets/vantage/index.html) rendered from
 * the same web files as the desktop add-on, so the phone and desktop look alike.
 */
class VantageDashboardActivity : AnkiActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true
        setRenderWorkaround(this)
        webView.addJavascriptInterface(Bridge(webView), "pycmdBridge")
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    // Wire the in-page Back/Refresh/Study buttons (they call pycmd) to Android.
                    view.evaluateJavascript(
                        "window.pycmd = function(c){ pycmdBridge.invoke(c); };",
                        null,
                    )
                    injectLiveScores(view)
                }
            }
        webView.loadUrl("file:///android_asset/vantage/index.html")
    }

    /**
     * Compute the three scores from THIS phone's own collection (real FSRS
     * retrievability, coverage from tags, graded-review count) and render them
     * live, replacing the baked snapshot in the asset.
     */
    private fun injectLiveScores(view: WebView) {
        lifecycleScope.launch {
            val raw =
                withCol {
                    val now = System.currentTimeMillis() / 1000
                    // Only compute retrievability for cards that actually have an
                    // FSRS memory state (a "s" field); new cards (e.g. fresh miss
                    // cards) have empty data and would panic the backend function.
                    val sql =
                        "select n.tags, case when c.data like '%\"s\"%' then extract_fsrs_retrievability(c.data, " +
                            "case when c.odue != 0 then c.odue else c.due end, c.ivl, " +
                            "${sched.today}, ${sched.dayCutoff}, $now) else null end " +
                            "from cards c join notes n on c.nid = n.id where c.queue != -1"
                    val cards = JSONArray()
                    db.query(sql).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONArray()
                            row.put(cursor.getString(0) ?: "")
                            if (cursor.isNull(1)) row.put(JSONObject.NULL) else row.put(cursor.getDouble(1))
                            cards.put(row)
                        }
                    }
                    // Graded FLASHCARD reviews only: reasoning-item attempts (#7A) also
                    // live in the revlog, but are excluded here so the give-up gate stays
                    // a measure of flashcard study (mirrors desktop _graded_reviews).
                    val reviews =
                        db.queryScalar(
                            "select count() from revlog where ease between 1 and 4 and cid not in " +
                                "(select c.id from cards c join notes n on c.nid = n.id " +
                                "where n.tags like '%vantage::reasoning::%')",
                        )
                    // Merged application-item outcomes: revlog-backed reasoning attempts
                    // (sync-safe) enriched with the confidence/miss-reason/concept kept in
                    // config, plus config entries not represented in the revlog. Mirrors
                    // desktop collect._merged_outcomes so the phone reads the same data.
                    //
                    // Metacognition config is merged across the legacy single key AND every
                    // per-device key ("vantage_perf_outcomes::<device id>"). Distinct
                    // per-device keys never clobber on sync, so two devices practicing
                    // offline both survive the merge (collect._all_perf_config). Keys are
                    // filtered in Kotlin (not via SQL LIKE, whose "_" is a wildcard) and
                    // sorted for a deterministic merge order.
                    val configArr = JSONArray()
                    run {
                        val keys = ArrayList<String>()
                        keys.add(PERF_CONFIG_KEY) // legacy single key first
                        keys.addAll(
                            db
                                .queryStringList("select key from config")
                                .filter { it.startsWith(PERF_DEVICE_PREFIX) }
                                .sorted(),
                        )
                        for (key in keys) {
                            val arr =
                                try {
                                    JSONArray(backend.getConfigJson(key).toStringUtf8())
                                } catch (e: Exception) {
                                    JSONArray()
                                }
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { configArr.put(it) }
                        }
                    }
                    val metaByRid = HashMap<Long, JSONObject>()
                    for (i in 0 until configArr.length()) {
                        val o = configArr.optJSONObject(i) ?: continue
                        if (o.has("revlog_id")) metaByRid[o.optLong("revlog_id")] = o
                    }
                    val merged = JSONArray()
                    val seenRids = HashSet<Long>()
                    db
                        .query(
                            "select r.id, r.ease, r.time, n.tags from revlog r " +
                                "join cards c on r.cid = c.id join notes n on c.nid = n.id " +
                                "where n.tags like '%vantage::reasoning::%' and r.ease between 1 and 4",
                        ).use { cur ->
                            while (cur.moveToNext()) {
                                val rid = cur.getLong(0)
                                val ease = cur.getLong(1)
                                val ms = cur.getLong(2)
                                val tags = cur.getString(3) ?: ""
                                val sec =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::reasoning::") }
                                        ?.removePrefix("vantage::reasoning::") ?: continue
                                seenRids.add(rid)
                                val meta = metaByRid[rid]
                                // Concept links the item to that concept's recall cards (the
                                // card-level paraphrase test / fluency illusion). Prefer the card
                                // tag, else a config-supplied concept. Mirrors _revlog_outcomes +
                                // _merged_outcomes ("ro.concept or meta.concept").
                                val concept =
                                    tags
                                        .split(" ")
                                        .firstOrNull { it.startsWith("vantage::concept::") }
                                        ?.removePrefix("vantage::concept::")
                                        ?: meta?.optString("concept", "")?.ifEmpty { null }
                                merged.put(
                                    JSONObject()
                                        .put("section", sec)
                                        .put("correct", ease >= 2)
                                        .put("confidence", meta?.optString("confidence", "") ?: "")
                                        .put("reason", meta?.optString("reason", "") ?: "")
                                        .put("concept", concept ?: "")
                                        .put("ms", if (meta != null && meta.has("ms")) meta.optLong("ms") else ms),
                                )
                            }
                        }
                    for (i in 0 until configArr.length()) {
                        val o = configArr.optJSONObject(i) ?: continue
                        if (o.has("revlog_id") && seenRids.contains(o.optLong("revlog_id"))) continue
                        merged.put(o)
                    }
                    var pn = 0
                    var pk = 0
                    for (i in 0 until merged.length()) {
                        pn += 1
                        if (merged.getJSONObject(i).optBoolean("correct", false)) pk += 1
                    }
                    // Rebuild the confusability signal for the Rust interleaver from the
                    // merged mistake data + the topics present on the phone's cards.
                    updateConfusability(this, merged, cards)
                    val reviewsDue =
                        db.queryScalar("select count() from cards where queue in (2,3) and due <= ${sched.today}") +
                            db.queryScalar("select count() from cards where queue = 1")
                    val newRemaining = db.queryScalar("select count() from cards where queue = 0")
                    val examDate = config.get<String>("vantage_exam_date", "") ?: ""
                    val bookSet = config.get<String>("vantage_book_set", "kaplan") ?: "kaplan"
                    // Target readiness composite (3-section scale): drives the score
                    // trajectory's on-pace check. 0 when unset, which the JS treats as
                    // "no target set yet" (abstain). Read as a number (int or float, like
                    // collect.gather's isinstance check); the JS truncates it to an int.
                    val targetScore = config.get<Double>(TARGET_CONFIG_KEY, 0.0) ?: 0.0
                    // Dated readiness snapshots the trajectory fits its slope over
                    // (HISTORY_CONFIG_KEY). Read the raw JSON array so a single malformed
                    // entry can't crash the read; the JS filters bad points defensively.
                    val readinessHistory =
                        try {
                            JSONArray(backend.getConfigJson(HISTORY_CONFIG_KEY).toStringUtf8())
                        } catch (e: Exception) {
                            JSONArray()
                        }
                    JSONObject()
                        .put("cards", cards)
                        .put("n_reviews", reviews)
                        .put("perf", JSONObject().put("n", pn).put("k", pk))
                        .put("perf_outcomes", merged)
                        .put("exam_date", examDate)
                        .put("book_set", bookSet)
                        .put("reviews_due", reviewsDue)
                        .put("new_remaining", newRemaining)
                        .put(
                            "today",
                            java.time.LocalDate
                                .now()
                                .toString(),
                        )
                        // Score-trajectory inputs (mirrors collect.gather): the target
                        // score and the dated readiness snapshots. days-to-exam is derived
                        // in JS from exam_date and today, exactly like collect's days_left.
                        .put("target", targetScore)
                        .put("readiness_history", readinessHistory)
                        .toString()
                }
            // The on-device compute (including the IRT EAP over a fixed 61-point grid)
            // is O(cards + outcomes x grid) -- a few milliseconds even for large decks
            // -- so it runs fine on the WebView main thread. If item counts ever grow
            // enough to jank the first paint, move window.vantageComputeFromRaw into a
            // Web Worker and post the result back before calling __vantageRender.
            view.evaluateJavascript(
                "window.__VANTAGE__ = window.vantageComputeFromRaw($raw); " +
                    "if (window.__vantageRender) window.__vantageRender(); " +
                    // Hand today's folded readiness-snapshot history back to the host to
                    // persist (or the literal null when nothing changed). See below.
                    "(window.__VANTAGE__ && window.__VANTAGE__.readiness_history_persist) || null;",
            ) { result ->
                persistReadinessHistory(result)
            }
        }
    }

    /**
     * Persist today's readiness snapshot that the scoring JS folded into the dated
     * history, so the score trajectory can build a trend across days.
     *
     * `result` is the WebView's JSON serialization of
     * `window.__VANTAGE__.readiness_history_persist`: the full updated
     * `[{ d, point }]` array when today's readiness is a LIVE full 3-section
     * composite that is new or has moved, or the literal string `"null"` when there
     * is nothing new to write. This mirrors collect.gather's once-per-day,
     * full-composite-only write to HISTORY_CONFIG_KEY, so a routine dashboard
     * refresh never dirties the collection (and never bumps the sync usn).
     */
    private fun persistReadinessHistory(result: String?) {
        if (result == null || result == "null") return
        val arr =
            try {
                JSONArray(result)
            } catch (e: Exception) {
                return
            }
        lifecycleScope.launch { withCol { config.set(HISTORY_CONFIG_KEY, arr) } }
    }

    private inner class Bridge(
        val webView: WebView,
    ) {
        @JavascriptInterface
        fun invoke(cmd: String) {
            // Runs on a binder thread; hop to the UI thread for view operations.
            runOnUiThread {
                when {
                    cmd == "vantage:back" -> finish()
                    cmd == "vantage:refresh" -> webView.reload()
                    // Interleaved / whole-deck study: Anki's own reviewer + queue.
                    cmd == "vantage:study" || cmd == "vantage:study:interleave" -> {
                        startActivity(Reviewer.getIntent(this@VantageDashboardActivity))
                        finish()
                    }
                    // Per-section flashcards: build a filtered deck for the section's
                    // tag, then study it through the same real reviewer.
                    cmd.startsWith("vantage:study:") -> startSectionStudy(cmd.removePrefix("vantage:study:"))
                    cmd.startsWith("vantage:practice2:") -> recordPractice2(cmd)
                    cmd.startsWith("vantage:practice:") -> recordPractice(cmd)
                    cmd.startsWith("vantage:examdate:") -> {
                        val iso = cmd.removePrefix("vantage:examdate:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (iso.isEmpty()) config.remove("vantage_exam_date") else config.set("vantage_exam_date", iso)
                            }
                            webView.reload()
                        }
                    }
                    cmd.startsWith("vantage:target:") -> {
                        val v = cmd.removePrefix("vantage:target:").trim()
                        lifecycleScope.launch {
                            withCol {
                                if (v.isEmpty()) {
                                    config.remove("vantage_target_score")
                                } else {
                                    v.toDoubleOrNull()?.let { config.set("vantage_target_score", it.toInt()) }
                                }
                            }
                            webView.reload()
                        }
                    }
                    cmd.startsWith("vantage:bookset:") -> {
                        val v = cmd.removePrefix("vantage:bookset:").trim()
                        // No reload: the page swaps the named book client-side.
                        if (v.isNotEmpty()) lifecycleScope.launch { withCol { config.set("vantage_book_set", v) } }
                    }
                    cmd.startsWith("vantage:open:") ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cmd.removePrefix("vantage:open:"))))
                        } catch (e: Exception) {
                            // no browser available; ignore
                        }
                }
            }
        }
    }

    /**
     * Study one section's flashcards on the shared engine: gather the section's
     * tagged cards into a filtered deck (via the backend, real FSRS scheduling),
     * select it, then hand off to Anki's own reviewer.
     */
    private fun startSectionStudy(section: String) {
        lifecycleScope.launch {
            withCol {
                val name = "Vantage $section"
                val existing = decks.idForName(name) ?: 0L
                val deckData =
                    filteredDeckForUpdate {
                        id = existing
                        this.name = name
                        allowEmpty = true
                        config =
                            filtered {
                                reschedule = true
                                searchTerms.add(
                                    searchTerm {
                                        search = "tag:mcat::$section::* -is:suspended"
                                        limit = 200
                                        order =
                                            Deck.Filtered.SearchTerm.Order
                                                .forNumber(0)
                                    },
                                )
                            }
                    }
                val did = sched.addOrUpdateFilteredDeck(deckData).id
                sched.rebuildFilteredDeck(did)
                decks.select(did)
            }
            startActivity(Reviewer.getIntent(this@VantageDashboardActivity))
            finish()
        }
    }

    /**
     * Fold a practice session into the performance-outcomes store on THIS phone's
     * collection, so the Performance score reflects real application-item practice
     * (mirrors the desktop add-on).
     */
    private fun recordPractice(cmd: String) {
        // cmd: vantage:practice:<section>:<correct>:<total>
        val parts = cmd.split(":")
        if (parts.size != 5) return
        val section = parts[2]
        val correct = parts[3].toIntOrNull() ?: return
        val total = parts[4].toIntOrNull() ?: return
        lifecycleScope.launch {
            withCol {
                // This device's OWN per-device key, so two devices practicing offline
                // never clobber each other on sync (mirrors collect.record_metacognition).
                val key = perfDeviceKey(this)
                val arr =
                    try {
                        JSONArray(backend.getConfigJson(key).toStringUtf8())
                    } catch (e: Exception) {
                        JSONArray()
                    }
                for (i in 0 until total) {
                    arr.put(JSONObject().put("section", section).put("correct", i < correct))
                }
                config.set(key, arr)
            }
        }
    }

    /**
     * Rich practice session (per-item confidence + miss reason). Records each
     * outcome for the metacognition and "how you lose points" panels, and turns
     * each miss into a spaced re-review card (deck "Vantage Misses", FSRS).
     */
    private fun recordPractice2(cmd: String) {
        val decoded =
            try {
                java.net.URLDecoder.decode(cmd.removePrefix("vantage:practice2:"), "UTF-8")
            } catch (e: Exception) {
                return
            }
        val json =
            try {
                JSONObject(decoded)
            } catch (e: Exception) {
                return
            }
        val section = json.optString("section", "")
        val items = json.optJSONArray("items") ?: return
        lifecycleScope.launch {
            withCol {
                // This device's OWN per-device key (see recordPractice); the correct/
                // incorrect outcome also lands in the revlog below and is linked by
                // revlog_id, so the read-side merge counts each answer once.
                val key = perfDeviceKey(this)
                val arr =
                    try {
                        JSONArray(backend.getConfigJson(key).toStringUtf8())
                    } catch (e: Exception) {
                        JSONArray()
                    }
                val seen = (config.get<List<String>>("vantage_miss_seen", emptyList()) ?: emptyList()).toMutableList()
                val basic = notetypes.byName("Basic")
                val missDeck by lazy { decks.id("Vantage Misses") }
                val reasoningDeck by lazy { decks.id("Vantage Reasoning") }
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val correct = it.optBoolean("correct", false)
                    val stem = it.optString("stem", "")
                    val answer = it.optString("answer", "")
                    val explain = it.optString("explain", "")
                    val ms = it.optLong("ms", 0L)
                    // The AAMC concept this item tests (if the practice set provides one):
                    // it links the reasoning card to that concept's recall cards for the
                    // card-level paraphrase test (fluency illusion). Mirrors desktop.
                    val concept = it.optString("concept", "")
                    // #7A: record the outcome as a real revlog row on a suspended anchor
                    // card, so correct/incorrect syncs + merges across devices (no config
                    // clobber). Mirrors desktop collect.log_reasoning_outcome.
                    var rid: Long? = null
                    if (stem.isNotEmpty() && basic != null) {
                        val qidTag = "vantage::rq::${md5hex(stem)}"
                        var cid =
                            db
                                .queryLongList(
                                    "select c.id from cards c join notes n on c.nid = n.id where n.tags like ?",
                                    "%$qidTag%",
                                ).firstOrNull()
                        if (cid == null) {
                            val note = newNote(basic)
                            note.setItem("Front", stem)
                            note.setItem("Back", if (answer.isNotEmpty()) "$answer<br><br>$explain" else explain)
                            note.addTag("vantage::reasoning::$section")
                            note.addTag(qidTag)
                            // concept tag links this item to the concept's recall cards
                            if (concept.isNotEmpty()) note.addTag("vantage::concept::$concept")
                            addNote(note, reasoningDeck)
                            val cids = cardIdsOfNote(note.id)
                            if (cids.isNotEmpty()) {
                                // anchors are never studied through the queue; the card exists
                                // only so its attempts live in the (synced) revlog.
                                sched.suspendCards(cids)
                                cid = cids[0]
                            }
                        }
                        if (cid != null) {
                            var newRid = System.currentTimeMillis()
                            while (db.queryScalar("select count() from revlog where id = ?", newRid) > 0) newRid++
                            db.execute(
                                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) " +
                                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                newRid,
                                cid,
                                -1,
                                if (correct) 3 else 1,
                                0,
                                0,
                                0,
                                ms.coerceIn(0L, 600000L),
                                1,
                            )
                            rid = newRid
                        }
                    }
                    val entry =
                        JSONObject()
                            .put("section", section)
                            .put("correct", correct)
                            .put("confidence", it.optString("confidence", ""))
                            .put("reason", it.optString("reason", ""))
                            .put("concept", concept)
                            .put("ms", ms)
                    if (rid != null) {
                        // link metacognition to its revlog outcome so the merge on read
                        // counts this answer once, not twice.
                        entry.put("revlog_id", rid)
                        entry.put("logged", true)
                    }
                    arr.put(entry)
                    // spaced re-review card for a miss (deck "Vantage Misses", FSRS)
                    if (!correct && stem.isNotEmpty() && basic != null) {
                        val md5 = md5hex(stem)
                        if (!seen.contains(md5)) {
                            val note = newNote(basic)
                            note.setItem("Front", stem)
                            note.setItem("Back", if (answer.isNotEmpty()) "$answer<br><br>$explain" else explain)
                            note.addTag("vantage::miss::$section")
                            addNote(note, missDeck)
                            seen.add(md5)
                        }
                    }
                }
                config.set(key, arr)
                config.set("vantage_miss_seen", seen)
            }
        }
    }

    /** Stable 12-hex-char id from a question's text (matches desktop _reasoning_qid). */
    private fun md5hex(s: String): String =
        java.security.MessageDigest
            .getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { b -> "%02x".format(b) }
            .substring(0, 12)

    /**
     * This device's OWN metacognition config key, "vantage_perf_outcomes::<device
     * id>". Each device writes only its own key, so two devices practicing offline
     * never clobber each other on sync; the read path unions every per-device key
     * plus the legacy single key. Mirrors desktop collect._perf_device_key.
     */
    private fun perfDeviceKey(col: Collection): String = PERF_DEVICE_PREFIX + deviceId(col)

    /**
     * A stable, LOCAL (never synced) id for this device, so its per-device key
     * differs from any other device's. Stored in a sibling file next to the
     * collection (app-private storage, which sync never touches), mirroring desktop
     * collect._device_id. Falls back to a once-seeded config key (which does sync,
     * so best-effort only) when the sibling file can't be used.
     */
    private fun deviceId(col: Collection): String {
        val path = col.db.path
        if (path.isNotEmpty() && path != ":memory:") {
            val idFile = File(path + DEVICE_ID_SUFFIX)
            try {
                if (idFile.exists()) {
                    val existing = idFile.readText(Charsets.UTF_8).trim()
                    if (existing.isNotEmpty()) return existing
                }
                val did = UUID.randomUUID().toString().replace("-", "")
                idFile.writeText(did, Charsets.UTF_8)
                return did
            } catch (e: Exception) {
                // no writable sibling file; fall through to the config fallback
            }
        }
        var did = col.config.get<String>(DEVICE_ID_CONFIG_KEY, "") ?: ""
        if (did.isEmpty()) {
            did = UUID.randomUUID().toString().replace("-", "")
            col.config.set(DEVICE_ID_CONFIG_KEY, did)
        }
        return did
    }

    /**
     * Rebuild the optional confusability signal the Rust interleaver reads from the
     * `vantage.interleave` config, from the student's mistake data + the topics on
     * their cards. Same-section topics are the confusable (similar) ones, and
     * interleaving helps most for confusable categories (Brunmair & Richter 2019),
     * so every pair of distinct topics that share a science section is weighted by
     * how many application mistakes the student has made in that section. The engine
     * only reorders the round-robin rotation (never FSRS); an empty/ineffective map
     * falls back bit-for-bit to the naive order. The user's existing mode / prefix /
     * seed are preserved (never clobbered), and we only write when a real, changed
     * signal exists, to avoid needless sync churn.
     *
     * `cards` is the [tags, retrievability] rows already gathered for scoring;
     * `outcomes` is the merged application-item outcome list.
     */
    private fun updateConfusability(
        col: Collection,
        outcomes: JSONArray,
        cards: JSONArray,
    ) {
        // Misses per science section (application outcomes graded incorrect).
        val missBySection = HashMap<String, Int>()
        for (i in 0 until outcomes.length()) {
            val o = outcomes.optJSONObject(i) ?: continue
            if (o.optBoolean("correct", false)) continue
            val s = o.optString("section", "")
            if (s.isEmpty()) continue
            missBySection[s] = (missBySection[s] ?: 0) + 1
        }
        if (missBySection.isEmpty()) return // no mistake signal yet -> leave config untouched

        // Preserve any existing settings; default to Mixed + "mcat" so a fresh phone
        // actually uses the signal, while respecting an explicit user choice.
        val existing = col.config.getObject(INTERLEAVE_CONFIG_KEY, JSONObject())
        val mode = existing.optString("mode", "Mixed").ifEmpty { "Mixed" }
        val prefix = existing.optString("topic_tag_prefix", "mcat").ifEmpty { "mcat" }
        val seed = existing.optLong("seed", 0L)

        // Distinct topics present on the phone's cards, grouped by section. The topic
        // key is the FIRST tag under "<prefix>::" (exactly how the interleaver buckets
        // a card), and its section is that tag's second "::" segment, so the keys we
        // emit are the real bucket keys the engine will match.
        val tagPrefix = "$prefix::"
        val topicsBySection = HashMap<String, java.util.TreeSet<String>>()
        for (i in 0 until cards.length()) {
            val row = cards.optJSONArray(i) ?: continue
            val tags = row.optString(0, "")
            val topic = tags.split(" ").firstOrNull { it.startsWith(tagPrefix) } ?: continue
            val parts = topic.split("::")
            if (parts.size < 2 || parts[1].isEmpty()) continue
            topicsBySection.getOrPut(parts[1]) { java.util.TreeSet() }.add(topic)
        }

        // One undirected pair per same-section topic pair, in a section with misses,
        // weighted by that section's miss count. Bounded and deterministic (sorted
        // sections + topics, i < j) so the persisted config is stable across refreshes.
        val pairs = JSONArray()
        var emitted = 0
        outer@ for ((section, missCount) in missBySection.toSortedMap()) {
            if (missCount <= 0) continue
            val all = topicsBySection[section]?.toList() ?: continue
            val topics = if (all.size > MAX_TOPICS_PER_SECTION) all.subList(0, MAX_TOPICS_PER_SECTION) else all
            for (a in topics.indices) {
                for (b in a + 1 until topics.size) {
                    if (emitted >= MAX_CONFUSABILITY_PAIRS) break@outer
                    pairs.put(
                        JSONObject()
                            .put("topic_a", topics[a])
                            .put("topic_b", topics[b])
                            .put("weight", missCount.toDouble()),
                    )
                    emitted += 1
                }
            }
        }
        if (pairs.length() == 0) return // topics not tagged under the prefix -> nothing to bias

        // Only write when the pair list actually changed (mode/prefix/seed are carried
        // over unchanged), so a routine dashboard refresh doesn't bump the sync usn.
        val prev = existing.optJSONArray("confusability")?.toString() ?: "[]"
        if (prev == pairs.toString()) return
        col.config.set(
            INTERLEAVE_CONFIG_KEY,
            JSONObject()
                .put("mode", mode)
                .put("topic_tag_prefix", prefix)
                .put("seed", seed)
                .put("confusability", pairs),
        )
    }

    companion object {
        // Metacognition stores (mirrors collect.PERF_CONFIG_KEY / PERF_DEVICE_PREFIX).
        private const val PERF_CONFIG_KEY = "vantage_perf_outcomes"
        private const val PERF_DEVICE_PREFIX = "vantage_perf_outcomes::"

        // Local (never synced) per-device id (mirrors collect.DEVICE_ID_*).
        private const val DEVICE_ID_SUFFIX = ".vantage_device_id"
        private const val DEVICE_ID_CONFIG_KEY = "vantage_device_id"

        // Score-trajectory inputs (mirrors collect.TARGET_CONFIG_KEY / HISTORY_CONFIG_KEY):
        // the target readiness composite and the dated readiness snapshots.
        private const val TARGET_CONFIG_KEY = "vantage_target_score"
        private const val HISTORY_CONFIG_KEY = "vantage_readiness_history"

        // Interleave config the Rust queue builder reads (rslib interleave.rs).
        private const val INTERLEAVE_CONFIG_KEY = "vantage.interleave"

        // Bounds so the confusability blob stays small regardless of deck size.
        private const val MAX_TOPICS_PER_SECTION = 24
        private const val MAX_CONFUSABILITY_PAIRS = 400

        fun getIntent(context: Context): Intent = Intent(context, VantageDashboardActivity::class.java)
    }
}
